package com.example.sql_script_generate.config;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "migration.input")
public class MigrationInputProperties {

    private Path directory = Paths.get("csv-files");
    private String usersFile = "users.csv";
    private String beneficiariesFile = "beneficiaries.csv";
    private String templatesFile = "templates.csv";

    public Path getDirectory() {
        return directory;
    }

    public void setDirectory(Path directory) {
        this.directory = directory;
    }

    public String getUsersFile() {
        return usersFile;
    }

    public void setUsersFile(String usersFile) {
        this.usersFile = usersFile;
    }

    public String getBeneficiariesFile() {
        return beneficiariesFile;
    }

    public void setBeneficiariesFile(String beneficiariesFile) {
        this.beneficiariesFile = beneficiariesFile;
    }

    public String getTemplatesFile() {
        return templatesFile;
    }

    public void setTemplatesFile(String templatesFile) {
        this.templatesFile = templatesFile;
    }

    public Path usersPath() {
        return resolveFile(usersFile);
    }

    public Path beneficiariesPath() {
        return resolveFile(beneficiariesFile);
    }

    public Path templatesPath() {
        return resolveFile(templatesFile);
    }

    private Path resolveFile(String fileName) {
        Path inputDirectory = directory.toAbsolutePath().normalize();
        Path file = inputDirectory.resolve(fileName).normalize();
        if (!file.startsWith(inputDirectory)) {
            throw new IllegalStateException("Configured migration input file must be inside the input directory");
        }
        return file;
    }
}
