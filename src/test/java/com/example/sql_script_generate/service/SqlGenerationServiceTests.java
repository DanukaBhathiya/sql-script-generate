package com.example.sql_script_generate.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.example.sql_script_generate.service.SqlGenerationService.SqlGenerationResult;

class SqlGenerationServiceTests {

    private final SqlGenerationService service = new SqlGenerationService();

    @Test
    void generateSqlWithSummaryExplainsSkippedDuplicateUserQuery() throws Exception {
        InputStream usersCsv = csv("""
                CIF,USERNAME,IDENTITY_NUMBER,MOBILE,EMAIL
                1001,john,A123,7771111,john@example.com
                1001,jane,A124,7772222,jane@example.com
                """);
        InputStream beneficiariesCsv = csv("CIF,TYPE,ACCOUNT_NUMBER\n");

        SqlGenerationResult result = service.generateSqlWithSummary(usersCsv, beneficiariesCsv, null, 1);

        assertThat(result.failureCount()).isEqualTo(1);
        assertThat(result.sql())
                .contains("INSERT INTO \"pending_user\"")
                .contains("-- Skipped users CSV row 2 due to duplicate cif");
        assertThat(result.failureSummary())
                .contains("Failed scenarios: 1")
                .contains("Source: users CSV")
                .contains("Row: 2")
                .contains("Query: pending_user insert")
                .contains("Reason: query skipped without being created because the row has duplicate cif");
        assertThat(result.migrationDataCsv())
                .contains("insert_index,source_file,source_row,target_table,column_name,final_sql_value")
                .contains("1,users CSV,1,pending_user,cif,'1001'")
                .contains("1,users CSV,1,pending_user,username,'JOHN'")
                .doesNotContain("2,users CSV,2,pending_user,cif,'1001'");
    }

    private InputStream csv(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }
}
