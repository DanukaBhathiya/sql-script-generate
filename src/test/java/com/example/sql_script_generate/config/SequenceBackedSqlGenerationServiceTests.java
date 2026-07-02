package com.example.sql_script_generate.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class SequenceBackedSqlGenerationServiceTests {

    private final SequenceBackedSqlGenerationService service = new SequenceBackedSqlGenerationService();

    @Test
    void pendingUserIdUsesDatabaseDefaultAndIgnoresRequestedStart() throws Exception {
        InputStream usersCsv = csv("CIF,USERNAME\n1001,john\n");
        InputStream beneficiariesCsv = csv("CIF,TYPE,ACCOUNT_NUMBER\n");

        var result = service.generateSqlWithSummary(usersCsv, beneficiariesCsv, null, 99999);

        assertThat(result.sql())
                .contains("VALUES (DEFAULT, CURRENT_TIMESTAMP")
                .doesNotContain("VALUES (99999,");
        assertThat(result.migrationDataCsv()).contains("1,users CSV,1,pending_user,id,DEFAULT");
    }

    private InputStream csv(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }
}
