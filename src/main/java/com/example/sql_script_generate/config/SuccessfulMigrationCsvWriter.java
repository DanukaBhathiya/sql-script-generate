package com.example.sql_script_generate.config;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

@Component
public class SuccessfulMigrationCsvWriter {

    public SuccessCsvResult write(Path outputDirectory, String baseFileName, Path usersCsv, Path beneficiariesCsv,
            Path templatesCsv, String migrationDataCsv, List<Integer> successfulInsertIndexes) throws IOException {
        Files.createDirectories(outputDirectory);
        Map<Integer, SourceRow> sourceRows = readSourceRows(migrationDataCsv);
        Map<String, Set<Long>> successfulRows = new LinkedHashMap<>();
        successfulRows.put("users CSV", new LinkedHashSet<>());
        successfulRows.put("beneficiaries CSV", new LinkedHashSet<>());
        successfulRows.put("templates CSV", new LinkedHashSet<>());

        for (Integer insertIndex : successfulInsertIndexes) {
            SourceRow sourceRow = sourceRows.get(insertIndex);
            if (sourceRow == null) {
                throw new IllegalStateException("Missing migration data mapping for insert index " + insertIndex);
            }
            successfulRows.get(sourceRow.source()).add(sourceRow.rowNumber());
        }

        Path usersOutput = outputDirectory.resolve(baseFileName + "_users.csv");
        Path beneficiariesOutput = outputDirectory.resolve(baseFileName + "_beneficiaries.csv");
        Path templatesOutput = outputDirectory.resolve(baseFileName + "_templates.csv");
        int usersCount = writeSelectedRows(usersCsv, usersOutput, successfulRows.get("users CSV"));
        int beneficiariesCount = writeSelectedRows(
                beneficiariesCsv, beneficiariesOutput, successfulRows.get("beneficiaries CSV"));
        int templatesCount = writeSelectedRows(templatesCsv, templatesOutput, successfulRows.get("templates CSV"));

        return new SuccessCsvResult(usersOutput, beneficiariesOutput, templatesOutput,
                usersCount, beneficiariesCount, templatesCount);
    }

    private Map<Integer, SourceRow> readSourceRows(String migrationDataCsv) throws IOException {
        Map<Integer, SourceRow> sourceRows = new LinkedHashMap<>();
        try (Reader reader = new StringReader(migrationDataCsv);
                CSVParser parser = CSVFormat.DEFAULT.builder()
                        .setHeader().setSkipHeaderRecord(true).build().parse(reader)) {
            for (CSVRecord record : parser) {
                int insertIndex = Integer.parseInt(record.get("insert_index"));
                sourceRows.putIfAbsent(insertIndex, new SourceRow(
                        record.get("source_file"), Long.parseLong(record.get("source_row"))));
            }
        }
        return sourceRows;
    }

    private int writeSelectedRows(Path source, Path output, Set<Long> selectedRows) throws IOException {
        if (source == null || !Files.isRegularFile(source)) {
            Files.writeString(output, "", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return 0;
        }

        int written = 0;
        try (Reader reader = Files.newBufferedReader(source, StandardCharsets.UTF_8);
                CSVParser parser = CSVFormat.DEFAULT.builder()
                        .setHeader().setSkipHeaderRecord(true).build().parse(reader);
                Writer writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT.builder()
                        .setHeader(parser.getHeaderNames().toArray(String[]::new)).build())) {
            List<String> headers = parser.getHeaderNames();
            for (CSVRecord record : parser) {
                if (!selectedRows.contains(record.getRecordNumber())) {
                    continue;
                }
                List<String> values = new ArrayList<>(headers.size());
                for (String header : headers) {
                    values.add(record.get(header));
                }
                printer.printRecord(values);
                written++;
            }
        }
        return written;
    }

    private record SourceRow(String source, long rowNumber) {
    }

    public record SuccessCsvResult(Path usersPath, Path beneficiariesPath, Path templatesPath,
            int usersCount, int beneficiariesCount, int templatesCount) {
        public int totalCount() {
            return usersCount + beneficiariesCount + templatesCount;
        }
    }
}
