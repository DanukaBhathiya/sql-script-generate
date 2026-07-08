package com.example.sql_script_generate.config;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import com.example.sql_script_generate.service.SqlExecutionService;
import com.example.sql_script_generate.config.SuccessfulMigrationCsvWriter.SuccessCsvResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Primary
public class LoggingSqlExecutionService extends SqlExecutionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(LoggingSqlExecutionService.class);
    private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final PendingUserSequenceManager pendingUserSequenceManager;
    private final MigrationUniquenessManager migrationUniquenessManager;
    private final ObjectProvider<DataSource> dataSourceProvider;
    private final MigrationExecutionContext executionContext;
    private final MigrationInputProperties inputProperties;
    private final MigrationOutputProperties outputProperties;
    private final SuccessfulMigrationCsvWriter successfulMigrationCsvWriter;

    public LoggingSqlExecutionService(ObjectProvider<DataSource> dataSourceProvider,
            PendingUserSequenceManager pendingUserSequenceManager,
            MigrationUniquenessManager migrationUniquenessManager,
            MigrationExecutionContext executionContext,
            MigrationInputProperties inputProperties,
            MigrationOutputProperties outputProperties,
            SuccessfulMigrationCsvWriter successfulMigrationCsvWriter) {
        super(dataSourceProvider);
        this.dataSourceProvider = dataSourceProvider;
        this.pendingUserSequenceManager = pendingUserSequenceManager;
        this.migrationUniquenessManager = migrationUniquenessManager;
        this.executionContext = executionContext;
        this.inputProperties = inputProperties;
        this.outputProperties = outputProperties;
        this.successfulMigrationCsvWriter = successfulMigrationCsvWriter;
    }

    @Override
    @Transactional
    public SqlExecutionResult executeGeneratedSql(String sql, int expectedInsertCount) {
        DetailedSqlExecutionResult result = executeGeneratedSqlDetailed(sql, expectedInsertCount);
        return new SqlExecutionResult(result.expectedInsertCount());
    }

    @Transactional
    public DetailedSqlExecutionResult executeGeneratedSqlDetailed(String sql, int expectedInsertCount) {
        LOGGER.info("Database execution started: expectedInsertCount={}", expectedInsertCount);
        long startedAt = System.nanoTime();
        try {
            migrationUniquenessManager.ensureReady();
            pendingUserSequenceManager.ensureReady();

            DataSource dataSource = dataSourceProvider.getIfAvailable();
            if (dataSource == null) {
                throw new IllegalStateException("No datasource configured for migration execution");
            }
            List<String> statements = GeneratedSqlStatementParser.insertStatements(sql);
            if (statements.size() != expectedInsertCount) {
                throw new IllegalStateException("Generated SQL insert count mismatch: expected="
                        + expectedInsertCount + ", parsed=" + statements.size());
            }

            JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
            List<Integer> successfulInsertIndexes = new ArrayList<>();
            for (int i = 0; i < statements.size(); i++) {
                String statement = statements.get(i);
                int insertIndex = i + 1;
                int updateCount = jdbcTemplate.update(statement);
                String status = updateCount > 0 ? "INSERTED" : "CONFLICT_SKIPPED";
                if (updateCount > 0) {
                    successfulInsertIndexes.add(i + 1);
                }
                LOGGER.info("Migration insert line processed: insertIndex={}, targetTable={}, status={}",
                        insertIndex, targetTable(statement), status);
            }

            DetailedSqlExecutionResult result = new DetailedSqlExecutionResult(
                    expectedInsertCount,
                    successfulInsertIndexes.size(),
                    expectedInsertCount - successfulInsertIndexes.size(),
                    List.copyOf(successfulInsertIndexes));
            SuccessCsvResult successFiles = writeSuccessFiles(result);
            LOGGER.info("Database execution completed: expectedInsertCount={}, insertedCount={}, conflictSkippedCount={}, durationMs={}",
                    expectedInsertCount, result.insertedCount(), result.conflictSkippedCount(),
                    (System.nanoTime() - startedAt) / 1_000_000);
            LOGGER.info("Successful migration CSV files created: usersFile={}, usersCount={}, beneficiariesFile={}, beneficiariesCount={}, templatesFile={}, templatesCount={}",
                    successFiles.usersPath(), successFiles.usersCount(),
                    successFiles.beneficiariesPath(), successFiles.beneficiariesCount(),
                    successFiles.templatesPath(), successFiles.templatesCount());
            return result;
        } catch (RuntimeException ex) {
            String errorSummary = safeErrorSummary(ex);
            LOGGER.error("Database execution failed: expectedInsertCount={}, exceptionType={}, reason={}, durationMs={}",
                    expectedInsertCount, ex.getClass().getSimpleName(), errorSummary,
                    (System.nanoTime() - startedAt) / 1_000_000);
            throw new SqlExecutionFailureException(errorSummary, ex);
        }
    }

    private String targetTable(String statement) {
        if (statement.contains("INSERT INTO \"pending_user\"")) {
            return "pending_user";
        }
        if (statement.contains("INSERT INTO \"migrate_beneficiary\"")) {
            return "migrate_beneficiary";
        }
        if (statement.contains("INSERT INTO \"migrate_template\"")) {
            return "migrate_template";
        }
        return "unknown";
    }

    private SuccessCsvResult writeSuccessFiles(DetailedSqlExecutionResult result) {
        String baseFileName = "migration_success_" + LocalDateTime.now().format(FILE_TIMESTAMP);
        try {
            return successfulMigrationCsvWriter.write(
                    outputProperties.directoryPath(),
                    baseFileName,
                    inputProperties.usersPath(),
                    inputProperties.beneficiariesPath(),
                    inputProperties.templatesPath(),
                    executionContext.requireMigrationDataCsv(),
                    result.successfulInsertIndexes());
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to write successful migration CSV files", ex);
        }
    }

    public record DetailedSqlExecutionResult(int expectedInsertCount, int insertedCount, int conflictSkippedCount,
            List<Integer> successfulInsertIndexes) {
    }

    private String safeErrorSummary(Throwable error) {
        Throwable rootCause = error;
        while (rootCause.getCause() != null && rootCause.getCause() != rootCause) {
            rootCause = rootCause.getCause();
        }
        String message = rootCause.getMessage();
        if (message == null || message.isBlank()) {
            return rootCause.getClass().getSimpleName();
        }
        String firstLine = message.split("\\R", 2)[0].trim();
        return firstLine.length() > 500 ? firstLine.substring(0, 500) : firstLine;
    }
}
