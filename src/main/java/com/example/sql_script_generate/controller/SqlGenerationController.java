package com.example.sql_script_generate.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

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
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/sql")
public class SqlGenerationController {

    private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final SqlGenerationService sqlGenerationService;

    public SqlGenerationController(SqlGenerationService sqlGenerationService) {
        this.sqlGenerationService = sqlGenerationService;
    }

    @PostMapping(value = "/generate", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> generateSql(
            @RequestParam("usersCsv") MultipartFile usersCsv,
            @RequestParam("beneCsv") MultipartFile beneCsv,
            @RequestParam(name = "templateCsv", required = false) MultipartFile templateCsv,
            @RequestParam(name = "userIdStart", defaultValue = "1") int userIdStart,
            @RequestParam(name = "saveToDisk", defaultValue = "true") boolean saveToDisk,
            @RequestParam(name = "outputDir", defaultValue = "generated") String outputDir
    ) throws IOException {

        if (usersCsv.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "usersCsv file is required");
        }

        if (beneCsv.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "beneCsv file is required");
        }

        if (userIdStart < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userIdStart must be greater than 0");
        }

        Path outputDirectory = null;
        if (saveToDisk) {
            outputDirectory = resolveOutputDirectory(outputDir);
        }

        SqlGenerationResult result;
        try {
            result = sqlGenerationService.generateSqlWithSummary(
                    usersCsv.getInputStream(),
                    beneCsv.getInputStream(),
                    (templateCsv == null || templateCsv.isEmpty()) ? null : templateCsv.getInputStream(),
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

        String usersName = usersCsv.getOriginalFilename();
        String beneName = beneCsv.getOriginalFilename();
        headers.add("X-Users-File", StringUtils.hasText(usersName) ? usersName : "users.csv");
        headers.add("X-Beneficiary-File", StringUtils.hasText(beneName) ? beneName : "bene.csv");

        if (templateCsv != null && !templateCsv.isEmpty()) {
            String templateName = templateCsv.getOriginalFilename();
            headers.add("X-Template-File", StringUtils.hasText(templateName) ? templateName : "templates.csv");
        }

        return ResponseEntity.ok()
                .headers(headers)
                .body(result.sql().getBytes(StandardCharsets.UTF_8));
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
