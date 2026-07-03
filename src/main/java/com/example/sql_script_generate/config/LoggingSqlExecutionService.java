package com.example.sql_script_generate.config;

import javax.sql.DataSource;

import com.example.sql_script_generate.service.SqlExecutionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Primary
public class LoggingSqlExecutionService extends SqlExecutionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(LoggingSqlExecutionService.class);

    private final PendingUserSequenceManager pendingUserSequenceManager;
    private final MigrationUniquenessManager migrationUniquenessManager;

    public LoggingSqlExecutionService(ObjectProvider<DataSource> dataSourceProvider,
            PendingUserSequenceManager pendingUserSequenceManager,
            MigrationUniquenessManager migrationUniquenessManager) {
        super(dataSourceProvider);
        this.pendingUserSequenceManager = pendingUserSequenceManager;
        this.migrationUniquenessManager = migrationUniquenessManager;
    }

    @Override
    @Transactional
    public SqlExecutionResult executeGeneratedSql(String sql, int expectedInsertCount) {
        LOGGER.info("Database execution started: expectedInsertCount={}", expectedInsertCount);
        long startedAt = System.nanoTime();
        try {
            migrationUniquenessManager.ensureReady();
            pendingUserSequenceManager.ensureReady();
            SqlExecutionResult result = super.executeGeneratedSql(sql, expectedInsertCount);
            LOGGER.info("Database execution completed: expectedInsertCount={}, durationMs={}",
                    expectedInsertCount, (System.nanoTime() - startedAt) / 1_000_000);
            return result;
        } catch (RuntimeException ex) {
            String errorSummary = safeErrorSummary(ex);
            LOGGER.error("Database execution failed: expectedInsertCount={}, exceptionType={}, reason={}, durationMs={}",
                    expectedInsertCount, ex.getClass().getSimpleName(), errorSummary,
                    (System.nanoTime() - startedAt) / 1_000_000);
            throw new SqlExecutionFailureException(errorSummary, ex);
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
