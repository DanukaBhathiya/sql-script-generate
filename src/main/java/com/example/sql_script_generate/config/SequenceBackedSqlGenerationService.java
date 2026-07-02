package com.example.sql_script_generate.config;

import java.io.IOException;
import java.io.InputStream;

import com.example.sql_script_generate.service.SqlGenerationService;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

@Service
@Primary
public class SequenceBackedSqlGenerationService extends SqlGenerationService {

    @Override
    public SqlGenerationResult generateSqlWithSummary(InputStream usersCsvStream, InputStream beneficiariesCsvStream,
            InputStream templatesCsvStream, int ignoredUserIdStart) throws IOException {
        SqlGenerationResult result = super.generateSqlWithSummary(
                usersCsvStream, beneficiariesCsvStream, templatesCsvStream, 1);

        String sql = result.sql().replaceAll(
                "(?m)(^INSERT INTO \"pending_user\" .*? VALUES \\()\\d+(, )",
                "$1DEFAULT$2");
        String migrationDataCsv = result.migrationDataCsv().replaceAll(
                "(?m)^(\\d+,users CSV,\\d+,pending_user,id,)\\d+(\\r?)$",
                "$1DEFAULT$2");

        return new SqlGenerationResult(sql, result.failureSummary(), migrationDataCsv,
                result.failureCount(), result.insertCount());
    }
}
