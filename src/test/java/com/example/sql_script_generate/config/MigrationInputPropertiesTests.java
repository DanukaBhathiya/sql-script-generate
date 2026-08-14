package com.example.sql_script_generate.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MigrationInputPropertiesTests {

    @TempDir
    Path tempDirectory;

    @Test
    void discoversNumberedBatchFilesInNumericOrder() throws Exception {
        write("users_2.csv");
        write("beneficiaries_2.csv");
        write("templates_2.csv");
        write("users_1.csv");
        write("beneficiaries_1.csv");
        write("templates_1.csv");

        MigrationInputProperties properties = new MigrationInputProperties();
        properties.setDirectory(tempDirectory);

        var batches = properties.discoverBatches();

        assertThat(batches).extracting(MigrationInputProperties.MigrationBatch::id)
                .containsExactly("batch-1", "batch-2");
        assertThat(batches.get(0).usersPath().getFileName()).isEqualTo(Path.of("users_1.csv"));
        assertThat(batches.get(1).beneficiariesPath().getFileName()).isEqualTo(Path.of("beneficiaries_2.csv"));
    }

    @Test
    void fallsBackToConfiguredSingleFileSetWhenNoBatchFilesExist() throws Exception {
        MigrationInputProperties properties = new MigrationInputProperties();
        properties.setDirectory(tempDirectory);

        var batches = properties.discoverBatches();

        assertThat(batches).hasSize(1);
        assertThat(batches.get(0).id()).isEqualTo("default");
        assertThat(batches.get(0).usersPath().getFileName()).isEqualTo(Path.of("users.csv"));
        assertThat(batches.get(0).beneficiariesPath().getFileName()).isEqualTo(Path.of("beneficiaries.csv"));
        assertThat(batches.get(0).templatesPath().getFileName()).isEqualTo(Path.of("templates.csv"));
    }

    private void write(String fileName) throws Exception {
        Files.writeString(tempDirectory.resolve(fileName), "header\n");
    }
}
