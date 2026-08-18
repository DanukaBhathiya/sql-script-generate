package com.example.sql_script_generate.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.example.sql_script_generate.service.SqlGenerationService.MigrationFileSummary;

class SequenceBackedSqlGenerationServiceTests {

    private final SequenceBackedSqlGenerationService service =
            new SequenceBackedSqlGenerationService(new MigrationExecutionContext());

    @Test
    void pendingUserIdUsesDatabaseDefaultAndIgnoresRequestedStart() throws Exception {
        InputStream usersCsv = csv("CIF,USERNAME,BANK_EMAIL,DIGESTED_PASSWORD,MOBILE,REGISTERED_ACCOUNT_NUMBER\n"
                + "1001,john,john@example.test,hash,7000001,8000000001\n");
        InputStream beneficiariesCsv = csv("CIF,TYPE,ACCOUNT_NUMBER\n");

        var result = service.generateSqlWithSummary(usersCsv, beneficiariesCsv, null, 99999);

        assertThat(result.sql())
                .contains("VALUES (DEFAULT, CURRENT_TIMESTAMP")
                .doesNotContain("VALUES (99999,");
        assertThat(result.userSuccessCsv())
                .contains("cif,username,registered_account_number")
                .contains("1001,john,8000000001");
        assertThat(result.migrationDataCsv()).contains("1,users CSV,1,pending_user,id,DEFAULT");
    }

    @Test
    void beneficiaryAndTemplateInsertsIgnoreBusinessKeyConflicts() throws Exception {
        InputStream usersCsv = csv("CIF,USERNAME\n");
        InputStream beneficiariesCsv = csv("CIF,TYPE,ACCOUNT_NUMBER,NICKNAME\n"
                + "1001,OTHER_BANK,1234,Home\n");
        InputStream templatesCsv = csv("CIF,TEMPLATE_TYPE,TEMPLATE_NAME,RECIPIENT_BANK\n"
                + "1001,DOMESTIC_PAYMENT,Rent,Bank\n");

        var result = service.generateSqlWithSummary(usersCsv, beneficiariesCsv, templatesCsv, 1);

        assertThat(result.sql())
                .containsPattern("INSERT INTO \\\"migrate_beneficiary\\\" .* ON CONFLICT DO NOTHING;")
                .containsPattern("INSERT INTO \\\"migrate_template\\\" .* ON CONFLICT DO NOTHING;");
    }

    @Test
    void bulkRowsAreNormalizedAndMissingRequiredTemplateFieldsAreSkipped() throws Exception {
        InputStream usersCsv = csv("CIF,USERNAME,BANK_EMAIL,DIGESTED_PASSWORD,MOBILE\n");
        InputStream beneficiariesCsv = csv("CIF,TYPE,ACCOUNT_NUMBER,NICKNAME\n"
                + "1001,INTERNATIONAL,1234,Overseas\n");
        InputStream templatesCsv = csv("CIF,TEMPLATE_TYPE,TEMPLATE_NAME,RECIPIENT_BANK\n"
                + "1001,TRANSFER_OWN,Own account,\n"
                + "1001,DOMESTIC_PAYMENT,Rent,Local Bank\n");

        var result = service.generateSqlWithSummary(usersCsv, beneficiariesCsv, templatesCsv, 1);

        assertThat(result.sql())
                .contains("'INTERNATIONAL_TRANSFER'")
                .doesNotContain("'INTERNATIONAL'")
                .doesNotContain("'Own account'")
                .contains("'Rent'");
        assertThat(result.userSuccessCsv())
                .contains("cif,username,registered_account_number");
        assertThat(result.failureCount()).isEqualTo(1);
        assertThat(result.insertCount()).isEqualTo(2);
        assertThat(result.failureSummary())
                .contains("Source: templates CSV")
                .contains("Row: 1")
                .contains("missing required field(s): RECIPIENT_BANK");
        assertThat(result.migrationDataCsv()).contains("templates CSV,2,migrate_template");
        assertThat(result.fileSummaries())
                .contains(new MigrationFileSummary("beneficiaries CSV", 1, 1, 0))
                .contains(new MigrationFileSummary("templates CSV", 2, 1, 1));
    }

    @Test
    void usersMissingRequiredMigrationFieldsAreSkippedWithOriginalRowNumbers() throws Exception {
        InputStream usersCsv = csv("CIF,USERNAME,BANK_EMAIL,DIGESTED_PASSWORD,MOBILE,REGISTERED_ACCOUNT_NUMBER\n"
                + "1001,invalid,,,7000001,8000000001\n"
                + "1002,valid,valid@example.test,hash,7000002,8000000002\n");
        InputStream beneficiariesCsv = csv("CIF,TYPE,ACCOUNT_NUMBER,NICKNAME\n");

        var result = service.generateSqlWithSummary(usersCsv, beneficiariesCsv, null, 1);

        assertThat(result.sql())
                .doesNotContain("'INVALID'")
                .contains("'valid'");
        assertThat(result.failureCount()).isEqualTo(1);
        assertThat(result.insertCount()).isEqualTo(1);
        assertThat(result.failureSummary())
                .contains("Source: users CSV")
                .contains("Row: 1")
                .contains("missing required field(s): BANK_EMAIL, DIGESTED_PASSWORD");
        assertThat(result.userFailureCsv())
                .contains("cif,reason")
                .contains("1001,\"missing required field(s): BANK_EMAIL, DIGESTED_PASSWORD\"");
        assertThat(result.migrationDataCsv()).contains("users CSV,2,pending_user");
        assertThat(result.fileSummaries())
                .contains(new MigrationFileSummary("users CSV", 2, 1, 1));
    }

    @Test
    void multilineTemplateValuesStillReceiveConflictHandling() throws Exception {
        InputStream usersCsv = csv("CIF,USERNAME,BANK_EMAIL,DIGESTED_PASSWORD,MOBILE\n");
        InputStream beneficiariesCsv = csv("CIF,TYPE,ACCOUNT_NUMBER,NICKNAME\n");
        InputStream templatesCsv = csv("CIF,TEMPLATE_TYPE,TEMPLATE_NAME,RECIPIENT_BANK,NOTE_TO_RECIPIENT\n"
                + "1001,DOMESTIC_PAYMENT,Rent,Local Bank,\"first line\nsecond line\"\n");

        var result = service.generateSqlWithSummary(usersCsv, beneficiariesCsv, templatesCsv, 1);

        assertThat(result.sql())
                .contains("'first line\nsecond line'")
                .containsPattern("(?s)INSERT INTO \\\"migrate_template\\\".*first line\\Rsecond line.*"
                        + "ON CONFLICT DO NOTHING;");
    }

    private InputStream csv(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }
}
