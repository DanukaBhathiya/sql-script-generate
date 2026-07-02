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

import com.example.sql_script_generate.config.MigrationInputProperties;
import com.example.sql_script_generate.service.SqlExecutionService;
import com.example.sql_script_generate.service.SqlExecutionService.SqlExecutionResult;
import com.example.sql_script_generate.service.SqlGenerationService;
import com.example.sql_script_generate.service.SqlGenerationService.SqlGenerationResult;
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

    private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final SqlGenerationService sqlGenerationService;
    private final SqlExecutionService sqlExecutionService;
    private final MigrationInputProperties inputProperties;

    public SqlGenerationController(SqlGenerationService sqlGenerationService, SqlExecutionService sqlExecutionService,
            MigrationInputProperties inputProperties) {
        this.sqlGenerationService = sqlGenerationService;
        this.sqlExecutionService = sqlExecutionService;
        this.inputProperties = inputProperties;
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

        Path usersCsv = inputProperties.usersPath();
        Path beneficiariesCsv = inputProperties.beneficiariesPath();
        Path templatesCsv = inputProperties.templatesPath();
        requireInputFile(usersCsv, "users CSV");
        requireInputFile(beneficiariesCsv, "beneficiaries CSV");
        boolean hasTemplatesCsv = Files.isRegularFile(templatesCsv);

        SqlGenerationResult result;
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

        String baseFileName = "migration_inserts_" + LocalDateTime.now().format(FILE_TIMESTAMP);
        String fileName = baseFileName + ".sql";
        String failSummaryFileName = baseFileName + "_fail_summary.log";
        String migrationDataFileName = baseFileName + "_data.csv";
        Path savedPath = null;
        Path failSummaryPath = null;
        Path migrationDataPath = null;

        if (saveToDisk) {
            Files.createDirectories(outputDirectory);
            savedPath = outputDirectory.resolve(fileName);
            failSummaryPath = outputDirectory.resolve(failSummaryFileName);
            migrationDataPath = outputDirectory.resolve(migrationDataFileName);
            Files.writeString(savedPath, result.sql(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.writeString(failSummaryPath, result.failureSummary(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.writeString(migrationDataPath, result.migrationDataCsv(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        }

        SqlExecutionResult executionResult = null;
        if (executeToDb) {
            try {
                executionResult = sqlExecutionService.executeGeneratedSql(result.sql(), result.insertCount());
            } catch (IllegalStateException ex) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
            } catch (RuntimeException ex) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Database execution failed: " + ex.getMessage());
            }
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        headers.setContentDisposition(ContentDisposition.attachment().filename(fileName).build());

        if (savedPath != null) {
            headers.add("X-Saved-File", savedPath.toAbsolutePath().toString());
        }
        if (failSummaryPath != null) {
            headers.add("X-Fail-Summary-File", failSummaryPath.toAbsolutePath().toString());
        }
        if (migrationDataPath != null) {
            headers.add("X-Migration-Data-File", migrationDataPath.toAbsolutePath().toString());
        }
        if (outputDirectory != null) {
            headers.add("X-Output-Directory", outputDirectory.toString());
        }
        headers.add("X-Fail-Count", String.valueOf(result.failureCount()));
        headers.add("X-Insert-Count", String.valueOf(result.insertCount()));
        headers.add("X-Db-Execution", executeToDb ? "EXECUTED" : "SKIPPED");
        if (executionResult != null) {
            headers.add("X-Db-Expected-Insert-Count", String.valueOf(executionResult.expectedInsertCount()));
        }

        headers.add("X-Users-File", usersCsv.getFileName().toString());
        headers.add("X-Beneficiary-File", beneficiariesCsv.getFileName().toString());
        if (hasTemplatesCsv) {
            headers.add("X-Template-File", templatesCsv.getFileName().toString());
        }

        return ResponseEntity.ok()
                .headers(headers)
                .body(result.sql().getBytes(StandardCharsets.UTF_8));
    }

    private void requireInputFile(Path path, String description) {
        if (!Files.isRegularFile(path)) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Configured " + description + " file was not found: " + path);
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
}
