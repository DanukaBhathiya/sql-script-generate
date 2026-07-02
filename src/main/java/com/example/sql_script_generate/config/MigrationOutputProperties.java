package com.example.sql_script_generate.config;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "migration.output")
public class MigrationOutputProperties {

    private Path directory = Paths.get("migration-output");

    public Path getDirectory() {
        return directory;
    }

    public void setDirectory(Path directory) {
        this.directory = directory;
    }

    public Path directoryPath() {
        return directory.toAbsolutePath().normalize();
    }
}
