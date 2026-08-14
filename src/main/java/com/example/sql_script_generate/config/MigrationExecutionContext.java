package com.example.sql_script_generate.config;

import java.nio.file.Path;
import java.util.Optional;

import org.springframework.stereotype.Component;

@Component
public class MigrationExecutionContext {

    private final ThreadLocal<String> migrationDataCsv = new ThreadLocal<>();
    private final ThreadLocal<String> batchId = new ThreadLocal<>();
    private final ThreadLocal<MigrationSourceFiles> sourceFiles = new ThreadLocal<>();

    public void setMigrationDataCsv(String value) {
        migrationDataCsv.set(value);
    }

    public String requireMigrationDataCsv() {
        String value = migrationDataCsv.get();
        if (value == null) {
            throw new IllegalStateException("Migration execution metadata is unavailable");
        }
        return value;
    }

    public String currentMigrationDataCsv() {
        return migrationDataCsv.get();
    }

    public void setBatch(String value, MigrationSourceFiles files) {
        batchId.set(value);
        sourceFiles.set(files);
    }

    public Optional<String> currentBatchId() {
        return Optional.ofNullable(batchId.get());
    }

    public Optional<MigrationSourceFiles> currentSourceFiles() {
        return Optional.ofNullable(sourceFiles.get());
    }

    public void clear() {
        migrationDataCsv.remove();
        batchId.remove();
        sourceFiles.remove();
    }

    public record MigrationSourceFiles(Path usersPath, Path beneficiariesPath, Path templatesPath) {
    }
}
