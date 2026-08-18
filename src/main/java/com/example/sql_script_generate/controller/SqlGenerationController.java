package com.example.sql_script_generate.controller;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import com.example.sql_script_generate.config.MigrationExecutionContext;
import com.example.sql_script_generate.config.MigrationExecutionContext.MigrationSourceFiles;
import com.example.sql_script_generate.config.MigrationInputProperties;
import com.example.sql_script_generate.config.MigrationInputProperties.MigrationBatch;
import com.example.sql_script_generate.service.SqlExecutionService;
import com.example.sql_script_generate.service.SqlExecutionService.SqlExecutionResult;
import com.example.sql_script_generate.service.SqlGenerationService;
import com.example.sql_script_generate.service.SqlGenerationService.SqlGenerationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/sql")
public class SqlGenerationController {

    private static final Logger LOGGER = LoggerFactory.getLogger(SqlGenerationController.class);
    private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final SqlGenerationService sqlGenerationService;
    private final SqlExecutionService sqlExecutionService;
    private final MigrationInputProperties inputProperties;
    private final MigrationExecutionContext executionContext;

    public SqlGenerationController(SqlGenerationService sqlGenerationService, SqlExecutionService sqlExecutionService,
            MigrationInputProperties inputProperties, MigrationExecutionContext executionContext) {
        this.sqlGenerationService = sqlGenerationService;
        this.sqlExecutionService = sqlExecutionService;
        this.inputProperties = inputProperties;
        this.executionContext = executionContext;
    }

    @PostMapping("/generate")
    public ResponseEntity<byte[]> generateSql(
            @RequestParam(name = "userIdStart", defaultValue = "1") int userIdStart,
            @RequestParam(name = "saveToDisk", defaultValue = "true") boolean saveToDisk,
            @RequestParam(name = "outputDir", defaultValue = "generated") String outputDir,
            @RequestParam(name = "executeToDb", defaultValue = "false") boolean executeToDb
    ) throws IOException {

        if (userIdStart < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userIdStart must be greater than 0");
        }

        Path outputDirectory = null;
        if (saveToDisk) {
            outputDirectory = resolveOutputDirectory(outputDir);
        }

        List<MigrationBatch> batches;
        try {
            batches = inputProperties.discoverBatches();
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage());
        }

        List<Path> savedPaths = new ArrayList<>();
        List<Path> failSummaryPaths = new ArrayList<>();
        List<Path> migrationDataPaths = new ArrayList<>();
        List<Path> userSuccessPaths = new ArrayList<>();
        List<Path> userFailurePaths = new ArrayList<>();
        List<String> batchFileDescriptions = new ArrayList<>();
        StringBuilder responseSql = new StringBuilder();
        int totalFailureCount = 0;
        int totalInsertCount = 0;
        int totalExpectedDbInsertCount = 0;

        for (MigrationBatch batch : batches) {
            Path usersCsv = batch.usersPath();
            Path beneficiariesCsv = batch.beneficiariesPath();
            Path templatesCsv = batch.templatesPath();
            requireInputFile(usersCsv, batch.id(), "users CSV");
            requireInputFile(beneficiariesCsv, batch.id(), "beneficiaries CSV");
            boolean hasTemplatesCsv = Files.isRegularFile(templatesCsv);
            logSourceFileReadStarted(batch.id(), "users CSV", usersCsv, true);
            logSourceFileReadStarted(batch.id(), "beneficiaries CSV", beneficiariesCsv, true);
            if (hasTemplatesCsv) {
                logSourceFileReadStarted(batch.id(), "templates CSV", templatesCsv, false);
            } else {
                LOGGER.info("Migration source file read skipped: batchId={}, sourceFile=templates CSV, path={}, reason=optional file not found",
                        batch.id(), templatesCsv.toAbsolutePath().normalize());
            }

            try {
            SqlGenerationResult result;
            executionContext.setBatch(batch.id(), new MigrationSourceFiles(usersCsv, beneficiariesCsv, templatesCsv));
            try (InputStream usersInput = Files.newInputStream(usersCsv);
                    InputStream beneficiariesInput = Files.newInputStream(beneficiariesCsv);
                    InputStream templatesInput = hasTemplatesCsv ? Files.newInputStream(templatesCsv) : null) {
                result = sqlGenerationService.generateSqlWithSummary(
                        usersInput,
                        beneficiariesInput,
                        templatesInput,
                        userIdStart
                );
            } catch (IllegalArgumentException ex) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
            }
            result.fileSummaries().forEach(summary -> LOGGER.info(
                    "Migration source file read completed: batchId={}, sourceFile={}, rowsRead={}, generatedInsertCount={}, skippedCount={}",
                    batch.id(), summary.sourceFile(), summary.rowsRead(), summary.generatedInsertCount(), summary.skippedCount()));

            String batchSafeName = sanitizeFilePart(batch.id());
            String baseFileName = "default".equals(batch.id())
                    ? "migration_inserts_" + LocalDateTime.now().format(FILE_TIMESTAMP)
                    : "migration_inserts_" + batchSafeName + "_" + LocalDateTime.now().format(FILE_TIMESTAMP);
            String fileName = baseFileName + ".sql";
            String failSummaryFileName = baseFileName + "_fail_summary.log";
            String migrationDataFileName = baseFileName + "_data.csv";
            String userSuccessFileName = baseFileName + "_users_success.csv";
            String userFailureFileName = baseFileName + "_users_failed.csv";

            if (saveToDisk) {
                Files.createDirectories(outputDirectory);
                Path savedPath = outputDirectory.resolve(fileName);
                Path failSummaryPath = outputDirectory.resolve(failSummaryFileName);
                Path migrationDataPath = outputDirectory.resolve(migrationDataFileName);
                Path userSuccessPath = outputDirectory.resolve(userSuccessFileName);
                Path userFailurePath = outputDirectory.resolve(userFailureFileName);
                Files.writeString(savedPath, result.sql(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                Files.writeString(failSummaryPath, result.failureSummary(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                Files.writeString(migrationDataPath, result.migrationDataCsv(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                Files.writeString(userSuccessPath, result.userSuccessCsv(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                Files.writeString(userFailurePath, result.userFailureCsv(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                savedPaths.add(savedPath);
                failSummaryPaths.add(failSummaryPath);
                migrationDataPaths.add(migrationDataPath);
                userSuccessPaths.add(userSuccessPath);
                userFailurePaths.add(userFailurePath);
                LOGGER.info("Migration output file written: batchId={}, fileType=sql, path={}, bytes={}",
                        batch.id(), savedPath.toAbsolutePath().normalize(), Files.size(savedPath));
                LOGGER.info("Migration output file written: batchId={}, fileType=fail-summary, path={}, bytes={}",
                        batch.id(), failSummaryPath.toAbsolutePath().normalize(), Files.size(failSummaryPath));
                LOGGER.info("Migration output file written: batchId={}, fileType=migration-data, path={}, bytes={}",
                        batch.id(), migrationDataPath.toAbsolutePath().normalize(), Files.size(migrationDataPath));
                LOGGER.info("Migration output file written: batchId={}, fileType=user-success, path={}, bytes={}",
                        batch.id(), userSuccessPath.toAbsolutePath().normalize(), Files.size(userSuccessPath));
                LOGGER.info("Migration output file written: batchId={}, fileType=user-failure, path={}, bytes={}",
                        batch.id(), userFailurePath.toAbsolutePath().normalize(), Files.size(userFailurePath));
            }

            if (executeToDb) {
                try {
                    SqlExecutionResult executionResult = sqlExecutionService.executeGeneratedSql(result.sql(), result.insertCount());
                    totalExpectedDbInsertCount += executionResult.expectedInsertCount();
                } catch (IllegalStateException ex) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
                } catch (RuntimeException ex) {
                    throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Database execution failed: " + ex.getMessage());
                }
            }

            appendBatchSql(responseSql, batch, result.sql());
            batchFileDescriptions.add(batch.id() + ":"
                    + usersCsv.getFileName() + ","
                    + beneficiariesCsv.getFileName() + ","
                    + (hasTemplatesCsv ? templatesCsv.getFileName() : "templates CSV not found"));
            totalFailureCount += result.failureCount();
            totalInsertCount += result.insertCount();
            } finally {
                executionContext.clear();
            }
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        String responseFileName = batches.size() == 1 && "default".equals(batches.get(0).id())
                ? savedPaths.stream().findFirst().map(path -> path.getFileName().toString())
                        .orElse("migration_inserts_" + LocalDateTime.now().format(FILE_TIMESTAMP) + ".sql")
                : "migration_inserts_batches_" + LocalDateTime.now().format(FILE_TIMESTAMP) + ".sql";
        headers.setContentDisposition(ContentDisposition.attachment().filename(responseFileName).build());

        if (!savedPaths.isEmpty()) {
            headers.add("X-Saved-File", savedPaths.get(0).toAbsolutePath().toString());
            headers.add("X-Saved-Files", joinPaths(savedPaths));
        }
        if (!failSummaryPaths.isEmpty()) {
            headers.add("X-Fail-Summary-File", failSummaryPaths.get(0).toAbsolutePath().toString());
            headers.add("X-Fail-Summary-Files", joinPaths(failSummaryPaths));
        }
        if (!migrationDataPaths.isEmpty()) {
            headers.add("X-Migration-Data-File", migrationDataPaths.get(0).toAbsolutePath().toString());
            headers.add("X-Migration-Data-Files", joinPaths(migrationDataPaths));
        }
        if (!userSuccessPaths.isEmpty()) {
            headers.add("X-User-Success-File", userSuccessPaths.get(0).toAbsolutePath().toString());
            headers.add("X-User-Success-Files", joinPaths(userSuccessPaths));
        }
        if (!userFailurePaths.isEmpty()) {
            headers.add("X-User-Failure-File", userFailurePaths.get(0).toAbsolutePath().toString());
            headers.add("X-User-Failure-Files", joinPaths(userFailurePaths));
        }
        if (outputDirectory != null) {
            headers.add("X-Output-Directory", outputDirectory.toString());
        }
        headers.add("X-Batch-Count", String.valueOf(batches.size()));
        headers.add("X-Migration-Batches", String.join(";", batchFileDescriptions));
        headers.add("X-Fail-Count", String.valueOf(totalFailureCount));
        headers.add("X-Insert-Count", String.valueOf(totalInsertCount));
        headers.add("X-Db-Execution", executeToDb ? "EXECUTED" : "SKIPPED");
        if (executeToDb) {
            headers.add("X-Db-Expected-Insert-Count", String.valueOf(totalExpectedDbInsertCount));
        }

        if (batches.size() == 1) {
            MigrationBatch batch = batches.get(0);
            headers.add("X-Users-File", batch.usersPath().getFileName().toString());
            headers.add("X-Beneficiary-File", batch.beneficiariesPath().getFileName().toString());
            if (Files.isRegularFile(batch.templatesPath())) {
                headers.add("X-Template-File", batch.templatesPath().getFileName().toString());
            }
        }

        return ResponseEntity.ok()
                .headers(headers)
                .body(responseSql.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void requireInputFile(Path path, String batchId, String description) {
        if (!Files.isRegularFile(path)) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Configured " + description + " file was not found for " + batchId + ": " + path);
        }
    }

    private Path resolveOutputDirectory(String outputDir) {
        if (!StringUtils.hasText(outputDir)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "outputDir is required when saveToDisk is true");
        }
        try {
            return Paths.get(outputDir).toAbsolutePath().normalize();
        } catch (InvalidPathException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "outputDir is not a valid path");
        }
    }

    private void logSourceFileReadStarted(String batchId, String sourceFile, Path path, boolean required) throws IOException {
        LOGGER.info("Migration source file read started: batchId={}, sourceFile={}, path={}, required={}, sizeBytes={}",
                batchId, sourceFile, path.toAbsolutePath().normalize(), required, Files.size(path));
    }

    private void appendBatchSql(StringBuilder responseSql, MigrationBatch batch, String sql) {
        if (!responseSql.isEmpty()) {
            responseSql.append(System.lineSeparator()).append(System.lineSeparator());
        }
        if (!"default".equals(batch.id())) {
            responseSql.append("-- Migration batch: ").append(batch.id()).append(System.lineSeparator());
            responseSql.append("-- Users file: ").append(batch.usersPath().getFileName()).append(System.lineSeparator());
            responseSql.append("-- Beneficiaries file: ").append(batch.beneficiariesPath().getFileName()).append(System.lineSeparator());
            responseSql.append("-- Templates file: ").append(batch.templatesPath().getFileName()).append(System.lineSeparator());
        }
        responseSql.append(sql);
    }

    private String joinPaths(List<Path> paths) {
        return paths.stream()
                .map(path -> path.toAbsolutePath().toString())
                .reduce((left, right) -> left + ";" + right)
                .orElse("");
    }

    private String sanitizeFilePart(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
