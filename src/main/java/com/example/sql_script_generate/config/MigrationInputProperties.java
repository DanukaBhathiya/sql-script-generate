package com.example.sql_script_generate.config;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "migration.input")
public class MigrationInputProperties {

    private static final Pattern BATCH_USERS_FILE = Pattern.compile("^users_(.+)\\.csv$", Pattern.CASE_INSENSITIVE);

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

    public List<MigrationBatch> discoverBatches() throws IOException {
        Path inputDirectory = directory.toAbsolutePath().normalize();
        if (!Files.isDirectory(inputDirectory)) {
            return List.of(singleConfiguredBatch());
        }

        try (var files = Files.list(inputDirectory)) {
            List<MigrationBatch> batches = files
                    .filter(Files::isRegularFile)
                    .map(path -> BATCH_USERS_FILE.matcher(path.getFileName().toString()))
                    .filter(Matcher::matches)
                    .map(matcher -> matcher.group(1))
                    .distinct()
                    .sorted(MigrationInputProperties::compareBatchIds)
                    .map(this::batchForSuffix)
                    .toList();
            return batches.isEmpty() ? List.of(singleConfiguredBatch()) : batches;
        }
    }

    private MigrationBatch singleConfiguredBatch() {
        return new MigrationBatch("default", usersPath(), beneficiariesPath(), templatesPath(), false);
    }

    private MigrationBatch batchForSuffix(String suffix) {
        return new MigrationBatch(
                "batch-" + suffix,
                resolveFile("users_" + suffix + ".csv"),
                resolveFile("beneficiaries_" + suffix + ".csv"),
                resolveFile("templates_" + suffix + ".csv"),
                true);
    }

    private static int compareBatchIds(String left, String right) {
        Integer leftNumber = parseInteger(left);
        Integer rightNumber = parseInteger(right);
        if (leftNumber != null && rightNumber != null) {
            return leftNumber.compareTo(rightNumber);
        }
        return Comparator.<String>naturalOrder().compare(left, right);
    }

    private static Integer parseInteger(String value) {
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Path resolveFile(String fileName) {
        Path inputDirectory = directory.toAbsolutePath().normalize();
        Path file = inputDirectory.resolve(fileName).normalize();
        if (!file.startsWith(inputDirectory)) {
            throw new IllegalStateException("Configured migration input file must be inside the input directory");
        }
        return file;
    }

    public record MigrationBatch(String id, Path usersPath, Path beneficiariesPath, Path templatesPath,
            boolean discovered) {
    }
}
