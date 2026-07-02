package com.example.sql_script_generate.config;

import java.io.IOException;
import java.io.InputStream;

import com.example.sql_script_generate.service.SqlGenerationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

@Service
@Primary
public class SequenceBackedSqlGenerationService extends SqlGenerationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(SequenceBackedSqlGenerationService.class);

    @Override
    public SqlGenerationResult generateSqlWithSummary(InputStream usersCsvStream, InputStream beneficiariesCsvStream,
            InputStream templatesCsvStream, int ignoredUserIdStart) throws IOException {
        LOGGER.info("SQL generation started");
        SqlGenerationResult result = super.generateSqlWithSummary(
                usersCsvStream, beneficiariesCsvStream, templatesCsvStream, 1);

        String sql = result.sql().replaceAll(
                "(?m)(^INSERT INTO \"pending_user\" .*? VALUES \\()\\d+(, )",
                "$1DEFAULT$2");
        String migrationDataCsv = result.migrationDataCsv().replaceAll(
                "(?m)^(\\d+,users CSV,\\d+,pending_user,id,)\\d+(\\r?)$",
                "$1DEFAULT$2");

        SqlGenerationResult sequenceBackedResult = new SqlGenerationResult(sql, result.failureSummary(), migrationDataCsv,
                result.failureCount(), result.insertCount());
        if (sequenceBackedResult.failureCount() > 0) {
            LOGGER.warn("SQL generation skipped rows: skippedCount={}", sequenceBackedResult.failureCount());
        }
        LOGGER.info("SQL generation completed: insertCount={}, skippedCount={}",
                sequenceBackedResult.insertCount(), sequenceBackedResult.failureCount());
        return sequenceBackedResult;
    }
}
