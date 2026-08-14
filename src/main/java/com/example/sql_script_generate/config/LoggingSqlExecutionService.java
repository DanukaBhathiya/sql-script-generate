package com.example.sql_script_generate.config;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import com.example.sql_script_generate.service.SqlExecutionService;
import com.example.sql_script_generate.config.SuccessfulMigrationCsvWriter.SuccessCsvResult;
import com.example.sql_script_generate.config.MigrationExecutionContext.MigrationSourceFiles;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
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
    private static final List<String> SOURCE_FILE_ORDER = List.of("users CSV", "beneficiaries CSV", "templates CSV");

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
        Map<String, FileExecutionStats> fileStats = new LinkedHashMap<>();
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
            Map<Integer, StatementSource> statementSources = readStatementSources();
            fileStats.putAll(fileExecutionStats(statementSources, statements.size()));
            fileStats.values().forEach(stats -> LOGGER.info(
                    "Migration source file execution started: sourceFile={}, sourceRows={}, expectedInsertCount={}",
                    stats.sourceFile, stats.sourceRowCount(), stats.expectedInsertCount));

            JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
            List<Integer> successfulInsertIndexes = new ArrayList<>();
            for (int i = 0; i < statements.size(); i++) {
                String statement = statements.get(i);
                int insertIndex = i + 1;
                StatementSource statementSource = statementSources.getOrDefault(insertIndex, StatementSource.unknown());
                FileExecutionStats stats = fileStats.computeIfAbsent(statementSource.sourceFile(), FileExecutionStats::new);
                int updateCount;
                try {
                    updateCount = jdbcTemplate.update(statement);
                } catch (RuntimeException ex) {
                    stats.recordFailure();
                    LOGGER.error("Migration insert line failed: insertIndex={}, sourceFile={}, sourceRow={}, targetTable={}, exceptionType={}, reason={}",
                            insertIndex, statementSource.sourceFile(), statementSource.sourceRowLogValue(),
                            statementSource.targetTableOr(targetTable(statement)), ex.getClass().getSimpleName(),
                            safeErrorSummary(ex));
                    throw ex;
                }
                String status = updateCount > 0 ? "INSERTED" : "CONFLICT_SKIPPED";
                stats.record(updateCount);
                if (updateCount > 0) {
                    successfulInsertIndexes.add(insertIndex);
                }
                LOGGER.info("Migration insert line processed: insertIndex={}, sourceFile={}, sourceRow={}, targetTable={}, status={}, updateCount={}",
                        insertIndex, statementSource.sourceFile(), statementSource.sourceRowLogValue(),
                        statementSource.targetTableOr(targetTable(statement)), status, updateCount);
            }

            DetailedSqlExecutionResult result = new DetailedSqlExecutionResult(
                    expectedInsertCount,
                    successfulInsertIndexes.size(),
                    expectedInsertCount - successfulInsertIndexes.size(),
                    List.copyOf(successfulInsertIndexes));
            logFileExecutionCompleted(fileStats, startedAt);
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
            logFileExecutionFailed(fileStats, startedAt);
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
        String batchPart = executionContext.currentBatchId()
                .filter(id -> !"default".equals(id))
                .map(id -> sanitizeFilePart(id) + "_")
                .orElse("");
        String baseFileName = "migration_success_" + batchPart + LocalDateTime.now().format(FILE_TIMESTAMP);
        MigrationSourceFiles sourceFiles = executionContext.currentSourceFiles()
                .orElse(new MigrationSourceFiles(
                        inputProperties.usersPath(),
                        inputProperties.beneficiariesPath(),
                        inputProperties.templatesPath()));
        try {
            return successfulMigrationCsvWriter.write(
                    outputProperties.directoryPath(),
                    baseFileName,
                    sourceFiles.usersPath(),
                    sourceFiles.beneficiariesPath(),
                    sourceFiles.templatesPath(),
                    executionContext.requireMigrationDataCsv(),
                    result.successfulInsertIndexes());
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to write successful migration CSV files", ex);
        }
    }

    private String sanitizeFilePart(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    public record DetailedSqlExecutionResult(int expectedInsertCount, int insertedCount, int conflictSkippedCount,
            List<Integer> successfulInsertIndexes) {
    }

    private Map<Integer, StatementSource> readStatementSources() {
        String migrationDataCsv = executionContext.currentMigrationDataCsv();
        if (migrationDataCsv == null || migrationDataCsv.isBlank()) {
            LOGGER.warn("Migration execution metadata is unavailable; source file execution logs will use sourceFile=unknown");
            return Map.of();
        }

        Map<Integer, StatementSource> statementSources = new LinkedHashMap<>();
        try (Reader reader = new StringReader(migrationDataCsv);
                CSVParser parser = CSVFormat.DEFAULT.builder()
                        .setHeader().setSkipHeaderRecord(true).build().parse(reader)) {
            for (CSVRecord record : parser) {
                int insertIndex = Integer.parseInt(record.get("insert_index"));
                statementSources.putIfAbsent(insertIndex, new StatementSource(
                        record.get("source_file"),
                        Long.parseLong(record.get("source_row")),
                        record.get("target_table")));
            }
        } catch (IOException | IllegalArgumentException ex) {
            throw new IllegalStateException("Failed to read migration execution metadata", ex);
        }
        return statementSources;
    }

    private Map<String, FileExecutionStats> fileExecutionStats(Map<Integer, StatementSource> statementSources,
            int statementCount) {
        Map<String, FileExecutionStats> statsBySource = new LinkedHashMap<>();
        if (statementSources.isEmpty()) {
            if (statementCount > 0) {
                FileExecutionStats unknown = new FileExecutionStats("unknown");
                unknown.expectedInsertCount = statementCount;
                statsBySource.put(unknown.sourceFile, unknown);
            }
            return statsBySource;
        }
        for (String sourceFile : SOURCE_FILE_ORDER) {
            statsBySource.put(sourceFile, new FileExecutionStats(sourceFile));
        }
        for (StatementSource source : statementSources.values()) {
            FileExecutionStats stats = statsBySource.computeIfAbsent(source.sourceFile(), FileExecutionStats::new);
            stats.expect(source);
        }
        statsBySource.entrySet().removeIf(entry -> entry.getValue().expectedInsertCount == 0);
        return statsBySource;
    }

    private void logFileExecutionCompleted(Map<String, FileExecutionStats> fileStats, long batchStartedAt) {
        long durationMs = (System.nanoTime() - batchStartedAt) / 1_000_000;
        fileStats.values().forEach(stats -> LOGGER.info(
                "Migration source file execution completed: sourceFile={}, sourceRows={}, expectedInsertCount={}, attemptedCount={}, insertedCount={}, conflictSkippedCount={}, failedCount={}, durationMs={}",
                stats.sourceFile, stats.sourceRowCount(), stats.expectedInsertCount, stats.attemptedCount,
                stats.insertedCount, stats.conflictSkippedCount, stats.failedCount, durationMs));
    }

    private void logFileExecutionFailed(Map<String, FileExecutionStats> fileStats, long batchStartedAt) {
        long durationMs = (System.nanoTime() - batchStartedAt) / 1_000_000;
        fileStats.values().stream()
                .filter(stats -> stats.attemptedCount > 0 || stats.failedCount > 0)
                .forEach(stats -> LOGGER.error(
                        "Migration source file execution failed: sourceFile={}, sourceRows={}, expectedInsertCount={}, attemptedCount={}, insertedCount={}, conflictSkippedCount={}, failedCount={}, durationMs={}",
                        stats.sourceFile, stats.sourceRowCount(), stats.expectedInsertCount, stats.attemptedCount,
                        stats.insertedCount, stats.conflictSkippedCount, stats.failedCount, durationMs));
    }

    private record StatementSource(String sourceFile, Long sourceRow, String targetTable) {
        static StatementSource unknown() {
            return new StatementSource("unknown", null, "unknown");
        }

        String sourceRowLogValue() {
            return sourceRow == null ? "unknown" : sourceRow.toString();
        }

        String targetTableOr(String fallback) {
            return targetTable == null || targetTable.isBlank() ? fallback : targetTable;
        }
    }

    private static final class FileExecutionStats {
        private final String sourceFile;
        private final Set<Long> sourceRows = new LinkedHashSet<>();
        private int expectedInsertCount;
        private int attemptedCount;
        private int insertedCount;
        private int conflictSkippedCount;
        private int failedCount;

        private FileExecutionStats(String sourceFile) {
            this.sourceFile = sourceFile;
        }

        private void expect(StatementSource source) {
            expectedInsertCount++;
            if (source.sourceRow() != null) {
                sourceRows.add(source.sourceRow());
            }
        }

        private void record(int updateCount) {
            attemptedCount++;
            if (updateCount > 0) {
                insertedCount++;
            } else {
                conflictSkippedCount++;
            }
        }

        private void recordFailure() {
            attemptedCount++;
            failedCount++;
        }

        private int sourceRowCount() {
            return sourceRows.size();
        }
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
