package com.example.sql_script_generate.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SuccessfulMigrationCsvWriterTests {

    @TempDir
    Path tempDirectory;

    @Test
    void writesOnlyRowsWhoseStatementsInsertedSuccessfully() throws Exception {
        Path users = write("users.csv", "CIF,USERNAME\n1001,first\n1002,second\n");
        Path beneficiaries = write("beneficiaries.csv", "CIF,NICKNAME\n1001,Home\n");
        Path templates = write("templates.csv", "CIF,TEMPLATE_NAME\n1002,Rent\n");
        String migrationData = "insert_index,source_file,source_row,target_table,column_name,final_sql_value\n"
                + "1,users CSV,2,pending_user,cif,'1002'\n"
                + "2,beneficiaries CSV,1,migrate_beneficiary,cif,'1001'\n"
                + "3,templates CSV,1,migrate_template,cif,'1002'\n";

        var result = new SuccessfulMigrationCsvWriter().write(
                tempDirectory, "migration_success_test", users, beneficiaries, templates,
                migrationData, List.of(1, 3));

        assertThat(result.usersCount()).isEqualTo(1);
        assertThat(result.beneficiariesCount()).isZero();
        assertThat(result.templatesCount()).isEqualTo(1);
        assertThat(Files.readString(result.usersPath()))
                .contains("CIF,USERNAME")
                .contains("1002,second")
                .doesNotContain("1001,first");
        assertThat(Files.readString(result.beneficiariesPath()))
                .contains("CIF,NICKNAME")
                .doesNotContain("1001,Home");
        assertThat(Files.readString(result.templatesPath())).contains("1002,Rent");
    }

    @Test
    void parserKeepsMultilineAndSemicolonValuesInOneInsert() {
        String sql = "INSERT INTO \"pending_user\" (\"full_name\") VALUES ('first;\nsecond');\n"
                + "INSERT INTO \"migrate_template\" (\"template_name\") VALUES ('Rent') ON CONFLICT DO NOTHING;";

        List<String> statements = GeneratedSqlStatementParser.insertStatements(sql);

        assertThat(statements).hasSize(2);
        assertThat(statements.get(0)).contains("first;\nsecond");
        assertThat(statements.get(1)).contains("ON CONFLICT DO NOTHING");
    }

    private Path write(String fileName, String content) throws Exception {
        return Files.writeString(tempDirectory.resolve(fileName), content);
    }
}
