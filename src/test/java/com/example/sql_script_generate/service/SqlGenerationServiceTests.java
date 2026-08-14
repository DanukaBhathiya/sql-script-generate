package com.example.sql_script_generate.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.example.sql_script_generate.service.SqlGenerationService.MigrationFileSummary;
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
                .contains("1,users CSV,1,pending_user,migrated_username,'JOHN'")
                .doesNotContain("2,users CSV,2,pending_user,cif,'1001'");
        assertThat(result.fileSummaries())
                .contains(new MigrationFileSummary("users CSV", 2, 1, 1))
                .contains(new MigrationFileSummary("beneficiaries CSV", 0, 0, 0))
                .contains(new MigrationFileSummary("templates CSV", 0, 0, 0));
    }

    @Test
    void generateSqlWithSummaryMapsAdditionalUserMigrationParameters() throws Exception {
        InputStream usersCsv = csv("""
                CIF,USERNAME,BANK_EMAIL,DIGESTED_PASSWORD,MOBILE,FDA ACCOUNT CREATED ON,FDA ACCOUNT STATUS,REMARKS / LOCK REASON,NUMBER OF OTP ATTEMPTS,NUMBER OF LOGIN ATTEMPTS
                1001,john,john@example.com,hash,7771111,2020-01-02,LOCKED,Fraud review,3,4
                """);
        InputStream beneficiariesCsv = csv("CIF,TYPE,ACCOUNT_NUMBER\n");

        SqlGenerationResult result = service.generateSqlWithSummary(usersCsv, beneficiariesCsv, null, 1);

        assertThat(result.sql())
                .contains("\"fda_account_remarks\", \"number_of_otp_attempts\", \"number_of_login_attempts\", "
                        + "\"fda_account_created_date_time\", \"fda_account_status\"")
                .contains("VALUES (1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP")
                .contains("'LOCKED'")
                .contains("'Fraud review', 3, 4, '2020-01-02', 'LOCKED'");
        assertThat(result.migrationDataCsv())
                .contains("1,users CSV,1,pending_user,fda_account_created_date_time,'2020-01-02'")
                .contains("1,users CSV,1,pending_user,fda_account_status,'LOCKED'")
                .contains("1,users CSV,1,pending_user,fda_account_remarks,'Fraud review'")
                .contains("1,users CSV,1,pending_user,number_of_otp_attempts,3")
                .contains("1,users CSV,1,pending_user,number_of_login_attempts,4");
    }

    private InputStream csv(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }
}
