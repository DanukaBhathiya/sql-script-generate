package com.example.sql_script_generate.config;

import org.springframework.stereotype.Component;

@Component
public class MigrationExecutionContext {

    private final ThreadLocal<String> migrationDataCsv = new ThreadLocal<>();

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

    public void clear() {
        migrationDataCsv.remove();
    }
}
