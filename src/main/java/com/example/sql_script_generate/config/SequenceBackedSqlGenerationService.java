package com.example.sql_script_generate.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.example.sql_script_generate.config.MigrationCsvPreprocessor.PreparedCsv;
import com.example.sql_script_generate.config.MigrationCsvPreprocessor.SkippedRow;
import com.example.sql_script_generate.service.SqlGenerationService;
import com.example.sql_script_generate.service.SqlGenerationService.MigrationFileSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

@Service
@Primary
public class SequenceBackedSqlGenerationService extends SqlGenerationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(SequenceBackedSqlGenerationService.class);
    private static final Pattern SOURCE_ROW = Pattern.compile(
            "(?m)^(\\d+,(users|beneficiaries|templates) CSV,)(\\d+)(,)");

    private final MigrationCsvPreprocessor csvPreprocessor = new MigrationCsvPreprocessor();
    private final MigrationExecutionContext executionContext;

    public SequenceBackedSqlGenerationService(MigrationExecutionContext executionContext) {
        this.executionContext = executionContext;
    }

    @Override
    public SqlGenerationResult generateSqlWithSummary(InputStream usersCsvStream, InputStream beneficiariesCsvStream,
            InputStream templatesCsvStream, int ignoredUserIdStart) throws IOException {
        LOGGER.info("SQL generation started");
        PreparedCsv users = csvPreprocessor.prepareUsers(usersCsvStream);
        PreparedCsv beneficiaries = csvPreprocessor.prepareBeneficiaries(beneficiariesCsvStream);
        PreparedCsv templates = csvPreprocessor.prepareTemplates(templatesCsvStream);
        SqlGenerationResult result = super.generateSqlWithSummary(
                users.stream(), beneficiaries.stream(), templates.stream(), 1);

        String sql = result.sql().replaceAll(
                "(?m)(^INSERT INTO \"pending_user\" .*? VALUES \\()\\d+(, )",
                "$1DEFAULT$2");
        sql = addConflictHandlingToMigrationInserts(sql);
        String migrationDataCsv = remapSourceRows(result.migrationDataCsv(),
                users.sourceRows(), beneficiaries.sourceRows(), templates.sourceRows()).replaceAll(
                "(?m)^(\\d+,users CSV,\\d+,pending_user,id,)\\d+(\\r?)$",
                "$1DEFAULT$2");

        List<SkippedRow> preprocessorFailures = new java.util.ArrayList<>(users.skippedRows());
        preprocessorFailures.addAll(beneficiaries.skippedRows());
        preprocessorFailures.addAll(templates.skippedRows());
        String failureSummary = mergeFailureSummary(result.failureSummary(), result.failureCount(), preprocessorFailures);

        SqlGenerationResult sequenceBackedResult = new SqlGenerationResult(sql, failureSummary, migrationDataCsv,
                result.failureCount() + preprocessorFailures.size(), result.insertCount(),
                mergeFileSummaries(result.fileSummaries(), users, beneficiaries, templates));
        executionContext.setMigrationDataCsv(migrationDataCsv);
        if (sequenceBackedResult.failureCount() > 0) {
            LOGGER.warn("SQL generation skipped rows: skippedCount={}", sequenceBackedResult.failureCount());
        }
        LOGGER.info("SQL generation completed: insertCount={}, skippedCount={}",
                sequenceBackedResult.insertCount(), sequenceBackedResult.failureCount());
        return sequenceBackedResult;
    }

    private String addConflictHandlingToMigrationInserts(String sql) {
        StringBuilder output = new StringBuilder(sql.length());
        int statementStart = 0;
        boolean insideString = false;

        for (int i = 0; i < sql.length(); i++) {
            char current = sql.charAt(i);
            if (current == '\'' && insideString && i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                i++;
                continue;
            }
            if (current == '\'') {
                insideString = !insideString;
            } else if (current == ';' && !insideString) {
                String statement = sql.substring(statementStart, i);
                output.append(statement);
                if (isMigrationInsert(statement) && !statement.stripTrailing().endsWith("ON CONFLICT DO NOTHING")) {
                    output.append(" ON CONFLICT DO NOTHING");
                }
                output.append(';');
                statementStart = i + 1;
            }
        }
        output.append(sql, statementStart, sql.length());
        return output.toString();
    }

    private boolean isMigrationInsert(String statement) {
        return statement.contains("INSERT INTO \"migrate_beneficiary\"")
                || statement.contains("INSERT INTO \"migrate_template\"");
    }

    private String remapSourceRows(String csv, Map<Long, Long> userRows, Map<Long, Long> beneficiaryRows,
            Map<Long, Long> templateRows) {
        Matcher matcher = SOURCE_ROW.matcher(csv);
        StringBuffer remapped = new StringBuffer();
        while (matcher.find()) {
            long generatedRow = Long.parseLong(matcher.group(3));
            Map<Long, Long> rows = switch (matcher.group(2)) {
                case "users" -> userRows;
                case "beneficiaries" -> beneficiaryRows;
                default -> templateRows;
            };
            long sourceRow = rows.getOrDefault(generatedRow, generatedRow);
            matcher.appendReplacement(remapped,
                    Matcher.quoteReplacement(matcher.group(1) + sourceRow + matcher.group(4)));
        }
        matcher.appendTail(remapped);
        return remapped.toString();
    }

    private String mergeFailureSummary(String summary, int existingCount, List<SkippedRow> addedFailures) {
        if (addedFailures.isEmpty()) {
            return summary;
        }
        int total = existingCount + addedFailures.size();
        String merged = summary.replaceFirst("Failed scenarios: \\d+", "Failed scenarios: " + total);
        if (existingCount == 0) {
            merged = merged.replaceFirst("(?m)^No failed scenarios found\\. No SQL queries were skipped\\.\\R?", "");
        }
        StringBuilder output = new StringBuilder(merged);
        for (int i = 0; i < addedFailures.size(); i++) {
            SkippedRow failure = addedFailures.get(i);
            output.append(existingCount + i + 1).append(". Source: ").append(failure.source()).append(System.lineSeparator());
            output.append("   Row: ").append(failure.rowNumber()).append(System.lineSeparator());
            output.append("   Query: ").append(failure.query()).append(System.lineSeparator());
            output.append("   Status: skipped").append(System.lineSeparator());
            output.append("   Reason: query skipped without being created because the row has ")
                    .append(failure.reason()).append(System.lineSeparator()).append(System.lineSeparator());
        }
        return output.toString();
    }

    private List<MigrationFileSummary> mergeFileSummaries(List<MigrationFileSummary> generatedSummaries,
            PreparedCsv users, PreparedCsv beneficiaries, PreparedCsv templates) {
        return List.of(
                mergeFileSummary("users CSV", generatedSummaries, users),
                mergeFileSummary("beneficiaries CSV", generatedSummaries, beneficiaries),
                mergeFileSummary("templates CSV", generatedSummaries, templates)
        );
    }

    private MigrationFileSummary mergeFileSummary(String sourceFile, List<MigrationFileSummary> generatedSummaries,
            PreparedCsv preparedCsv) {
        MigrationFileSummary generated = generatedSummaries.stream()
                .filter(summary -> sourceFile.equals(summary.sourceFile()))
                .findFirst()
                .orElse(new MigrationFileSummary(sourceFile, 0, 0, 0));
        return new MigrationFileSummary(
                sourceFile,
                Math.toIntExact(preparedCsv.totalRows()),
                generated.generatedInsertCount(),
                preparedCsv.skippedRows().size() + generated.skippedCount());
    }
}
