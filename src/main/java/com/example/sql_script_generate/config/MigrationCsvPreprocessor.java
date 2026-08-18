package com.example.sql_script_generate.config;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;

final class MigrationCsvPreprocessor {

    PreparedCsv prepareUsers(InputStream input) throws IOException {
        return prepare(input, "users CSV", "pending_user insert", row -> {
            List<String> missing = missingFields(row, "BANK_EMAIL", "DIGESTED_PASSWORD", "MOBILE");
            return missing.isEmpty()
                    ? RowDecision.keep(Map.of())
                    : RowDecision.skip("missing required field(s): " + String.join(", ", missing));
        });
    }

    PreparedCsv prepareBeneficiaries(InputStream input) throws IOException {
        return prepare(input, "beneficiaries CSV", "migrate_beneficiary insert", row -> {
            List<String> missing = missingFields(row, "CIF", "ACCOUNT_NUMBER", "NICKNAME", "TYPE");
            if (!missing.isEmpty()) {
                return RowDecision.skip("missing required field(s): " + String.join(", ", missing));
            }
            Map<String, String> replacements = new LinkedHashMap<>();
            if ("INTERNATIONAL".equals(normalized(row, "TYPE").toUpperCase(Locale.ROOT))) {
                replacements.put("TYPE", "INTERNATIONAL_TRANSFER");
            }
            return RowDecision.keep(replacements);
        });
    }

    PreparedCsv prepareTemplates(InputStream input) throws IOException {
        if (input == null) {
            return PreparedCsv.empty();
        }
        return prepare(input, "templates CSV", "migrate_template insert", row -> {
            List<String> missing = missingFields(row, "CIF", "TEMPLATE_NAME", "RECIPIENT_BANK");
            return missing.isEmpty()
                    ? RowDecision.keep(Map.of())
                    : RowDecision.skip("missing required field(s): " + String.join(", ", missing));
        });
    }

    private PreparedCsv prepare(InputStream input, String source, String query, RowValidator validator)
            throws IOException {
        try (InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8);
                CSVParser parser = CSVFormat.DEFAULT.builder()
                        .setHeader().setSkipHeaderRecord(true).setTrim(true).build().parse(reader);
                StringWriter writer = new StringWriter()) {
            List<String> headers = parser.getHeaderNames();
            Map<Long, Long> sourceRows = new LinkedHashMap<>();
            List<SkippedRow> skippedRows = new ArrayList<>();
            long totalRows = 0;

            try (CSVPrinter printer = new CSVPrinter(writer,
                    CSVFormat.DEFAULT.builder().setHeader(headers.toArray(String[]::new)).build())) {
                long outputRow = 0;
                for (CSVRecord row : parser) {
                    totalRows++;
                    RowDecision decision = validator.validate(row);
                    if (decision.skipReason() != null) {
                        skippedRows.add(new SkippedRow(source, row.getRecordNumber(), query, decision.skipReason(),
                                normalized(row, "CIF")));
                        continue;
                    }
                    List<String> values = new ArrayList<>(headers.size());
                    for (String header : headers) {
                        values.add(decision.replacements().getOrDefault(header, row.get(header)));
                    }
                    printer.printRecord(values);
                    sourceRows.put(++outputRow, row.getRecordNumber());
                }
            }
            return new PreparedCsv(
                    new ByteArrayInputStream(writer.toString().getBytes(StandardCharsets.UTF_8)),
                    sourceRows,
                    skippedRows,
                    totalRows);
        }
    }

    private List<String> missingFields(CSVRecord row, String... fields) {
        List<String> missing = new ArrayList<>();
        for (String field : fields) {
            if (!row.isMapped(field) || normalized(row, field) == null) {
                missing.add(field);
            }
        }
        return missing;
    }

    private String normalized(CSVRecord row, String field) {
        String value = row.get(field);
        return value == null || value.isBlank() ? null : value.trim();
    }

    record PreparedCsv(InputStream stream, Map<Long, Long> sourceRows, List<SkippedRow> skippedRows, long totalRows) {
        static PreparedCsv empty() {
            return new PreparedCsv(null, Map.of(), List.of(), 0);
        }
    }

    record SkippedRow(String source, long rowNumber, String query, String reason, String cif) {
    }

    private record RowDecision(String skipReason, Map<String, String> replacements) {
        static RowDecision keep(Map<String, String> replacements) {
            return new RowDecision(null, replacements);
        }

        static RowDecision skip(String reason) {
            return new RowDecision(reason, Map.of());
        }
    }

    @FunctionalInterface
    private interface RowValidator {
        RowDecision validate(CSVRecord row);
    }
}
