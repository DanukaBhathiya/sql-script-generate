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

    public LoggingSqlExecutionService(ObjectProvider<DataSource> dataSourceProvider,
            PendingUserSequenceManager pendingUserSequenceManager) {
        super(dataSourceProvider);
        this.pendingUserSequenceManager = pendingUserSequenceManager;
    }

    @Override
    @Transactional
    public SqlExecutionResult executeGeneratedSql(String sql, int expectedInsertCount) {
        LOGGER.info("Database execution started: expectedInsertCount={}", expectedInsertCount);
        long startedAt = System.nanoTime();
        try {
            pendingUserSequenceManager.ensureReady();
            SqlExecutionResult result = super.executeGeneratedSql(sql, expectedInsertCount);
            LOGGER.info("Database execution completed: expectedInsertCount={}, durationMs={}",
                    expectedInsertCount, (System.nanoTime() - startedAt) / 1_000_000);
            return result;
        } catch (RuntimeException ex) {
            LOGGER.error("Database execution failed: expectedInsertCount={}, exceptionType={}, durationMs={}",
                    expectedInsertCount, ex.getClass().getSimpleName(),
                    (System.nanoTime() - startedAt) / 1_000_000);
            throw ex;
        }
    }
}
