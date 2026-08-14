package com.example.sql_script_generate.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;

@Service
public class SqlGenerationService {

    private static final DateTimeFormatter SQL_DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final List<String> PENDING_USER_COLUMNS = List.of(
            "id", "created_date_time", "updated_date_time", "bank_email", "cif", "city", "country",
            "digested_password", "email", "email_matched", "email_mismatch_count", "first_name", "full_name",
            "id_expire", "id_type", "identity_number", "last_name", "middle_name", "mobile",
            "password_reference", "phase", "preferred_language", "status", "street1", "street2", "street3",
            "username", "migrated_username", "date_of_birth", "registered_account_number", "user_group_id",
            "migrate_user", "fda_account_remarks", "number_of_otp_attempts", "number_of_login_attempts",
            "fda_account_created_date_time", "fda_account_status"
    );
    private static final List<String> BENEFICIARY_COLUMNS = List.of(
            "cif", "created_date_time", "updated_date_time", "account_number", "bank_code", "bank_name",
            "nickname", "predefined_limit", "recipient_name", "transfer_limit", "type", "type_description",
            "recipient_country", "recipient_country_code", "bank_bic", "is_intra_group", "is_combank"
    );
    private static final List<String> TEMPLATE_COLUMNS = List.of(
            "created_date_time", "updated_date_time", "amount", "from_account", "note_to_recipient",
            "personal_note", "recipient_bank", "recipient_name", "template_name", "to_account", "cif",
            "currency_code", "bank_code", "charges", "purpose", "recipient_country", "transfer_type",
            "recipient_country_code", "charge_option", "intermediary_bank_swift_code", "recipient_address",
            "swift_code", "is_combank"
    );

    public String generateSql(InputStream usersCsvStream, InputStream beneficiariesCsvStream, int userIdStart) throws IOException {
        return generateSql(usersCsvStream, beneficiariesCsvStream, null, userIdStart);
    }

    public String generateSql(InputStream usersCsvStream, InputStream beneficiariesCsvStream, InputStream templatesCsvStream, int userIdStart) throws IOException {
        return generateSqlWithSummary(usersCsvStream, beneficiariesCsvStream, templatesCsvStream, userIdStart).sql();
    }

    public SqlGenerationResult generateSqlWithSummary(InputStream usersCsvStream, InputStream beneficiariesCsvStream, InputStream templatesCsvStream, int userIdStart) throws IOException {
        List<CSVRecord> users = readCsv(usersCsvStream);
        List<CSVRecord> beneficiaries = readCsv(beneficiariesCsvStream);
        List<CSVRecord> templates = templatesCsvStream == null ? new ArrayList<>() : readCsv(templatesCsvStream);
        List<FailureScenario> failureScenarios = new ArrayList<>();
        int skippedUsers = 0;

        int nextUserId = userIdStart;
        int nextInsertIndex = 1;

        StringBuilder sql = new StringBuilder();
        sql.append("-- Auto-generated SQL inserts").append(System.lineSeparator());
        sql.append(System.lineSeparator());
        StringBuilder migrationDataCsv = new StringBuilder();
        migrationDataCsv.append("insert_index,source_file,source_row,target_table,column_name,final_sql_value")
                .append(System.lineSeparator());

        String pendingUserPrefix = "INSERT INTO \"pending_user\" (\"id\", \"created_date_time\", \"updated_date_time\", "
                + "\"bank_email\", \"cif\", \"city\", \"country\", \"digested_password\", \"email\", \"email_matched\", "
                + "\"email_mismatch_count\", \"first_name\", \"full_name\", \"id_expire\", \"id_type\", \"identity_number\", "
                + "\"last_name\", \"middle_name\", \"mobile\", \"password_reference\", \"phase\", \"preferred_language\", "
                + "\"status\", \"street1\", \"street2\", \"street3\", \"username\", \"migrated_username\", "
                + "\"date_of_birth\", \"registered_account_number\", \"user_group_id\", \"migrate_user\", \"fda_account_remarks\", "
                + "\"number_of_otp_attempts\", \"number_of_login_attempts\", \"fda_account_created_date_time\", "
                + "\"fda_account_status\") VALUES ";

        Set<String> seenCif = new HashSet<>();
        Set<String> seenUsername = new HashSet<>();
        Set<String> seenIdentityNumber = new HashSet<>();
        Set<String> seenMobile = new HashSet<>();
        Set<String> seenEmail = new HashSet<>();

        for (CSVRecord row : users) {
            String bankEmail = normalize(getRaw(row, "BANK_EMAIL"));
            String email = normalize(getRaw(row, "EMAIL"));
            if (email == null) {
                email = bankEmail;
            }

            String cif = normalize(getRaw(row, "CIF"));
            String identityNumber = normalize(getRaw(row, "IDENTITY_NUMBER"));
            String mobile = normalize(getRaw(row, "MOBILE"));
            String username = normalize(getRaw(row, "USERNAME"));
            String digestedPassword = normalize(getRaw(row, "DIGESTED_PASSWORD", "DIGESTED PASSWORD"));
            if (username != null) {
                username = username.toUpperCase(Locale.ROOT);
            }
            String idExpire = parseIdExpire(normalize(getRaw(row, "ID_EXPIRE")));
            String dateOfBirth = parseDateOfBirth(normalize(getRaw(row, "DATE_OF_BIRTH")));
            String idType = mapIdType(normalize(getRaw(row, "ID_TYPE")));
            String userGroupName = normalize(getRaw(row, "USER_GROUP", "USER GROUP", "USERGROUP", "USER_GROUP_NAME", "USER GROUP NAME"));
            String fdaAccountCreatedOn = normalize(getRaw(row,
                    "FDAACCOUNT_CREATED_ON", "FDAACCOUNT CREATED ON", "FDA_ACCOUNT_CREATED_ON",
                    "FDA ACCOUNT CREATED ON", "FDAAccount Created on"));
            String fdaAccountStatus = normalize(getRaw(row,
                    "FDA_ACCOUNT_STATUS", "FDA ACCOUNT STATUS", "FDAACCOUNT_STATUS",
                    "FDAACCOUNT STATUS", "ACCOUNT_STATUS", "STATUS"));
            String remarks = normalize(getRaw(row,
                    "REMARKS", "LOCK_REASON", "LOCK REASON", "REMARKS_LOCK_REASON",
                    "REMARKS / LOCK REASON", "REMARKS/LOCK_REASON"));
            String numberOfOtpAttempts = normalize(getRaw(row,
                    "NUMBER_OF_OTP_ATTEMPTS", "NUMBER OF OTP ATTEMPTS", "OTP_ATTEMPTS",
                    "OTP ATTEMPTS"));
            String numberOfLoginAttempts = normalize(getRaw(row,
                    "NUMBER_OF_LOGIN_ATTEMPTS", "NUMBER OF LOGIN ATTEMPTS", "LOGIN_ATTEMPTS",
                    "LOGIN ATTEMPTS"));

            List<String> duplicateFields = new ArrayList<>();
            registerUniqueField(seenCif, normalizeUniqueKey(cif, false), "cif", duplicateFields);
            registerUniqueField(seenUsername, normalizeUniqueKey(username, true), "username", duplicateFields);
            registerUniqueField(seenIdentityNumber, normalizeUniqueKey(identityNumber, true), "identity_number", duplicateFields);
            registerUniqueField(seenMobile, normalizeUniqueKey(mobile, false), "mobile", duplicateFields);
            registerUniqueField(seenEmail, normalizeUniqueKey(email, true), "email", duplicateFields);

            if (!duplicateFields.isEmpty()) {
                String reason = "duplicate " + String.join(", ", duplicateFields);
                sql.append("-- Skipped users CSV row ")
                        .append(row.getRecordNumber())
                        .append(" due to ")
                        .append(reason)
                        .append(System.lineSeparator());
                failureScenarios.add(new FailureScenario(
                        "users CSV",
                        row.getRecordNumber(),
                        "pending_user insert",
                        "query skipped without being created because the row has " + reason
                ));
                skippedUsers++;
                continue;
            }

            List<String> values = new ArrayList<>();
            values.add(String.valueOf(nextUserId));
            values.add("CURRENT_TIMESTAMP");
            values.add("CURRENT_TIMESTAMP");
            values.add(toSqlString(bankEmail));
            values.add(toSqlString(cif));
            values.add("NULL");
            values.add(toSqlString("MALDIVES"));
            values.add(toSqlString(digestedPassword));
            values.add(toSqlString(email));
            values.add("NULL");
            values.add("0");
            values.add("NULL");
            values.add(toSqlString(normalize(getRaw(row, "FULL_NAME"))));
            values.add(toSqlString(idExpire));
            values.add(toSqlString(idType));
            values.add(toSqlString(identityNumber));
            values.add("NULL");
            values.add("NULL");
            values.add(toSqlString(mobile));
            values.add("NULL");
            values.add(toSqlString("MIGRATE"));
            values.add("NULL");
            values.add(toSqlString("IN_PROGRESS"));
            values.add(toSqlString(normalize(getRaw(row, "STREET1"))));
            values.add(toSqlString(normalize(getRaw(row, "STREET2"))));
            values.add(toSqlString(normalize(getRaw(row, "STREET3"))));
            values.add(toSqlString(username));
            values.add(toSqlString(username));
            values.add(toSqlString(dateOfBirth));
            values.add(toSqlString(normalize(getRaw(row, "REGISTERED_ACCOUNT_NUMBER"))));
            values.add(toSqlUserGroupId(userGroupName));
            values.add("TRUE");
            values.add(toSqlString(remarks));
            values.add(toSqlIntegerOrNull(numberOfOtpAttempts));
            values.add(toSqlIntegerOrNull(numberOfLoginAttempts));
            values.add(toSqlTimestampOrCurrent(fdaAccountCreatedOn));
            values.add(toSqlString(fdaAccountStatus == null ? "IN_PROGRESS" : fdaAccountStatus));

            sql.append(pendingUserPrefix)
                    .append("(")
                    .append(String.join(", ", values))
                    .append(") ON CONFLICT DO NOTHING;")
                    .append(System.lineSeparator());
            appendMigrationDataCsvRows(migrationDataCsv, nextInsertIndex, "users CSV", row.getRecordNumber(),
                    "pending_user", PENDING_USER_COLUMNS, values);
            nextInsertIndex++;
            nextUserId++;
        }

        sql.append(System.lineSeparator());

        String beneficiaryPrefix = "INSERT INTO \"migrate_beneficiary\" (\"cif\", \"created_date_time\", \"updated_date_time\", "
                + "\"account_number\", \"bank_code\", \"bank_name\", \"nickname\", \"predefined_limit\", \"recipient_name\", "
                + "\"transfer_limit\", \"type\", \"type_description\", \"recipient_country\", \"recipient_country_code\", "
                + "\"bank_bic\", \"is_intra_group\", \"is_combank\") VALUES ";

        for (CSVRecord row : beneficiaries) {
            String typeRaw = normalize(getRaw(row, "TYPE"));
            String upperType = typeRaw == null ? "" : typeRaw.toUpperCase();

            boolean isCombank = "WITHIN_COMBANK".equals(upperType);
            String mappedType = typeRaw;
            if ("WITHIN_COMBANK".equals(upperType) || "OTHER_BANK".equals(upperType) || mappedType == null) {
                mappedType = "LOCAL_TRANSFER";
            }

            String recipientName = normalize(getRaw(row, "RECIPIENT_NAME"));
            if (recipientName == null) {
                recipientName = normalize(getRaw(row, "NICKNAME"));
            }

            String bankCode = normalize(getRaw(row, "BANK_CODE"));
            String bankName = isCombank ? "Commercial Bank of Maldives" : null;

            String predefinedRaw = normalize(getRaw(row, "PREDEFINED_LIMIT"));
            String predefinedLimit = "FALSE";
            if (predefinedRaw != null) {
                String lower = predefinedRaw.toLowerCase();
                if (!lower.matches("^(0+(\\.0+)?|\\.0+|false|f|no)$")) {
                    predefinedLimit = "TRUE";
                }
            }

            List<String> values = new ArrayList<>();
            values.add(toSqlString(normalize(getRaw(row, "CIF"))));
            values.add("CURRENT_TIMESTAMP");
            values.add("CURRENT_TIMESTAMP");
            values.add(toSqlString(normalize(getRaw(row, "ACCOUNT_NUMBER"))));
            values.add(toSqlString(bankCode));
            values.add(toSqlString(bankName));
            values.add(toSqlString(normalize(getRaw(row, "NICKNAME"))));
            values.add(toSqlString(predefinedLimit));
            values.add(toSqlString(recipientName));
            values.add(toSqlNumber(predefinedRaw, "0.00"));
            values.add(toSqlString(mappedType));
            values.add(toSqlString("Fund Transfer"));
            values.add("NULL");
            values.add("NULL");
            values.add(toSqlString(bankCode));
            values.add(toSqlBool(isCombank));
            values.add(toSqlBool(isCombank));

            sql.append(beneficiaryPrefix)
                    .append("(")
                    .append(String.join(", ", values))
                    .append(");")
                    .append(System.lineSeparator());
            appendMigrationDataCsvRows(migrationDataCsv, nextInsertIndex, "beneficiaries CSV", row.getRecordNumber(),
                    "migrate_beneficiary", BENEFICIARY_COLUMNS, values);
            nextInsertIndex++;
        }

        if (!templates.isEmpty()) {
            sql.append(System.lineSeparator());

            String templatePrefix = "INSERT INTO \"migrate_template\" (\"created_date_time\", \"updated_date_time\", \"amount\", "
                    + "\"from_account\", \"note_to_recipient\", \"personal_note\", \"recipient_bank\", \"recipient_name\", "
                    + "\"template_name\", \"to_account\", \"cif\", \"currency_code\", \"bank_code\", \"charges\", \"purpose\", "
                    + "\"recipient_country\", \"transfer_type\", \"recipient_country_code\", \"charge_option\", "
                    + "\"intermediary_bank_swift_code\", \"recipient_address\", \"swift_code\", \"is_combank\") VALUES ";

            for (CSVRecord row : templates) {
                String migratedTimestamp = normalize(getRaw(row, "MIGRATED_TIMESTAMP"));
                String transferType = mapTemplateTransferType(normalize(getRaw(row, "TEMPLATE_TYPE")));
                String templateBankCode = normalize(getRaw(row, "BANK_CODE"));
                boolean isTemplateCombank = "66".equals(templateBankCode);
                String templateAmount = normalize(getRaw(row, "AMOUNT"));
                String templateCurrencyCode = normalize(getRaw(row, "CURRENCY_CODE"));
                if (templateCurrencyCode == null) {
                    templateCurrencyCode = "MVR";
                }
                String intermediarySwift = normalize(getRaw(row, "INTERMEDIARY_BANK_SWIFT_CODE"));
                if (intermediarySwift == null) {
                    intermediarySwift = "";
                }

                List<String> values = new ArrayList<>();
                values.add("CURRENT_TIMESTAMP");
                values.add("CURRENT_TIMESTAMP");
                values.add(toSqlNumber(templateAmount, "1.0"));
                values.add(toSqlString(normalize(getRaw(row, "FROM_ACCOUNT"))));
                values.add(toSqlString(normalize(getRaw(row, "NOTE_TO_RECIPIENT"))));
                values.add(toSqlString(normalize(getRaw(row, "PERSONAL_NOTE"))));
                values.add(toSqlString(normalize(getRaw(row, "RECIPIENT_BANK"))));
                values.add(toSqlString(normalize(getRaw(row, "RECIPIENT_NAME"))));
                values.add(toSqlString(normalize(getRaw(row, "TEMPLATE_NAME"))));
                values.add(toSqlString(normalize(getRaw(row, "TO_ACCOUNT"))));
                values.add(toSqlString(normalize(getRaw(row, "CIF"))));
                values.add(toSqlString(templateCurrencyCode));
                values.add(toSqlString(templateBankCode));
                values.add("NULL");
                values.add(toSqlString(normalize(getRaw(row, "PURPOSE"))));
                values.add(toSqlString(normalize(getRaw(row, "RECIPIENT_COUNTRY"))));
                values.add(toSqlString(transferType));
                values.add(toSqlString(normalize(getRaw(row, "RECIPIENT_COUNTRY_CODE"))));
                values.add("NULL");
                values.add(toSqlString(intermediarySwift));
                values.add(toSqlString(normalize(getRaw(row, "RECIPIENT_ADDRESS"))));
                values.add(toSqlString(normalize(getRaw(row, "SWIFT_CODE"))));
                values.add(isTemplateCombank ? "TRUE" : "FALSE");

                sql.append(templatePrefix)
                        .append("(")
                        .append(String.join(", ", values))
                        .append(");")
                        .append(System.lineSeparator());
                appendMigrationDataCsvRows(migrationDataCsv, nextInsertIndex, "templates CSV", row.getRecordNumber(),
                        "migrate_template", TEMPLATE_COLUMNS, values);
                nextInsertIndex++;
            }
        }

        int generatedInsertCount = nextInsertIndex - 1;
        int generatedUsers = users.size() - skippedUsers;
        int generatedBeneficiaries = beneficiaries.size();
        int generatedTemplates = templates.size();
        List<MigrationFileSummary> fileSummaries = List.of(
                new MigrationFileSummary("users CSV", users.size(), generatedUsers, skippedUsers),
                new MigrationFileSummary("beneficiaries CSV", beneficiaries.size(), generatedBeneficiaries, 0),
                new MigrationFileSummary("templates CSV", templates.size(), generatedTemplates, 0)
        );

        return new SqlGenerationResult(sql.toString(), buildFailureSummary(failureScenarios),
                migrationDataCsv.toString(), failureScenarios.size(), generatedInsertCount, fileSummaries);
    }

    private void appendMigrationDataCsvRows(StringBuilder csv, int insertIndex, String source, long sourceRow,
            String targetTable, List<String> columns, List<String> values) {
        for (int i = 0; i < columns.size(); i++) {
            appendCsvValue(csv, String.valueOf(insertIndex));
            csv.append(",");
            appendCsvValue(csv, source);
            csv.append(",");
            appendCsvValue(csv, String.valueOf(sourceRow));
            csv.append(",");
            appendCsvValue(csv, targetTable);
            csv.append(",");
            appendCsvValue(csv, columns.get(i));
            csv.append(",");
            appendCsvValue(csv, values.get(i));
            csv.append(System.lineSeparator());
        }
    }

    private void appendCsvValue(StringBuilder csv, String value) {
        if (value == null) {
            return;
        }
        boolean needsQuoting = value.contains(",") || value.contains("\"") || value.contains("\r") || value.contains("\n");
        if (!needsQuoting) {
            csv.append(value);
            return;
        }
        csv.append("\"").append(value.replace("\"", "\"\"")).append("\"");
    }

    private String buildFailureSummary(List<FailureScenario> failureScenarios) {
        StringBuilder summary = new StringBuilder();
        summary.append("SQL generation fail summary").append(System.lineSeparator());
        summary.append("Failed scenarios: ").append(failureScenarios.size()).append(System.lineSeparator());
        summary.append(System.lineSeparator());

        if (failureScenarios.isEmpty()) {
            summary.append("No failed scenarios found. No SQL queries were skipped.").append(System.lineSeparator());
            return summary.toString();
        }

        for (int i = 0; i < failureScenarios.size(); i++) {
            FailureScenario failure = failureScenarios.get(i);
            summary.append(i + 1).append(". Source: ").append(failure.source()).append(System.lineSeparator());
            summary.append("   Row: ").append(failure.rowNumber()).append(System.lineSeparator());
            summary.append("   Query: ").append(failure.queryName()).append(System.lineSeparator());
            summary.append("   Status: skipped").append(System.lineSeparator());
            summary.append("   Reason: ").append(failure.reason()).append(System.lineSeparator());
            summary.append(System.lineSeparator());
        }

        return summary.toString();
    }

    private List<CSVRecord> readCsv(InputStream stream) throws IOException {
        try (
                BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
                CSVParser parser = CSVFormat.DEFAULT.builder()
                        .setHeader()
                        .setSkipHeaderRecord(true)
                        .setTrim(true)
                        .build()
                        .parse(reader)
        ) {
            return parser.getRecords();
        }
    }

    private String getRaw(CSVRecord row, String header) {
        if (!row.isMapped(header)) {
            String normalizedHeader = normalizeHeader(header);
            for (String mappedHeader : row.toMap().keySet()) {
                if (normalizeHeader(mappedHeader).equals(normalizedHeader)) {
                    return row.get(mappedHeader);
                }
            }
            return null;
        }
        return row.get(header);
    }

    private String getRaw(CSVRecord row, String... headers) {
        for (String header : headers) {
            String value = getRaw(row, header);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.trim();
        if (cleaned.startsWith("\"") && cleaned.endsWith("\"") && cleaned.length() >= 2) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
        }
        if (cleaned.isBlank()) {
            return null;
        }
        return cleaned;
    }

    private String toSqlString(String value) {
        if (value == null || value.isBlank()) {
            return "NULL";
        }
        return "'" + value.replace("'", "''") + "'";
    }

    private String toSqlNumber(String value, String defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        String normalized = value.trim();
        if (normalized.startsWith(".")) {
            normalized = "0" + normalized;
        }
        if (normalized.matches("^-?\\d+(\\.\\d+)?$")) {
            return normalized;
        }
        return defaultValue;
    }

    private String toSqlIntegerOrNull(String value) {
        if (value == null || value.isBlank()) {
            return "NULL";
        }
        String normalized = value.trim();
        if (normalized.matches("^-?\\d+$")) {
            return normalized;
        }
        return "NULL";
    }

    private String toSqlTimestampOrCurrent(String value) {
        if (value == null || value.isBlank()) {
            return "CURRENT_TIMESTAMP";
        }
        return toSqlString(value);
    }

    private String toSqlBool(boolean value) {
        return value ? "TRUE" : "FALSE";
    }

    private String toSqlUserGroupId(String userGroupName) {
        if (userGroupName == null || userGroupName.isBlank()) {
            return "NULL";
        }
        String normalized = userGroupName.trim();
        if (normalized.matches("^\\d+$")) {
            return normalized;
        }
        String nameLiteral = toSqlString(normalized);
        return "(SELECT \"id\" FROM \"user_group\" WHERE UPPER(\"name\") = UPPER(" + nameLiteral + ") LIMIT 1)";
    }

    private String mapIdType(String raw) {
        if (raw == null) {
            return null;
        }
        if ("NIC".equalsIgnoreCase(raw.trim())) {
            return "NID";
        }
        return raw;
    }

    private String mapTemplateTransferType(String raw) {
        if (raw == null) {
            return null;
        }
        String upper = raw.trim().toUpperCase(Locale.ROOT);
        if ("INTRABANK".equals(upper) || "DOMESTIC_PAYMENT".equals(upper)) {
            return "LOCAL_TRANSFER";
        }
        return raw;
    }

    private String normalizeUniqueKey(String value, boolean caseInsensitive) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return caseInsensitive ? trimmed.toLowerCase(Locale.ROOT) : trimmed;
    }

    private String normalizeHeader(String value) {
        return value == null ? "" : value.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
    }

    private void registerUniqueField(Set<String> seenValues, String normalizedKey, String fieldName, List<String> duplicateFields) {
        if (normalizedKey == null) {
            return;
        }
        if (!seenValues.add(normalizedKey)) {
            duplicateFields.add(fieldName);
        }
    }

    private String parseIdExpire(String raw) {
        if (raw == null) {
            return null;
        }
        String digits = raw.replaceAll("\\D", "");
        if (digits.isBlank() || digits.length() > 6) {
            return null;
        }
        String padded = String.format("%6s", digits).replace(' ', '0');
        try {
            LocalDate parsed = LocalDate.parse(padded, DateTimeFormatter.ofPattern("ddMMyy"));
            return parsed.format(SQL_DATE_FORMAT);
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    private String parseDateOfBirth(String raw) {
        if (raw == null) {
            return null;
        }

        String digits = raw.replaceAll("\\D", "");
        if (digits.isBlank()) {
            return null;
        }

        if (digits.length() == 7) {
            try {
                int year = Integer.parseInt(digits.substring(0, 4));
                int dayOfYear = Integer.parseInt(digits.substring(4));
                if (dayOfYear >= 1 && dayOfYear <= 366) {
                    LocalDate localDate = LocalDate.ofYearDay(year, dayOfYear);
                    return localDate.format(SQL_DATE_FORMAT);
                }
            } catch (DateTimeException | NumberFormatException ignored) {
                return null;
            }
        }

        DateTimeFormatter[] formatters = new DateTimeFormatter[] {
                DateTimeFormatter.ofPattern("yyyyMMdd"),
                DateTimeFormatter.ofPattern("ddMMyyyy")
        };

        for (DateTimeFormatter formatter : formatters) {
            try {
                return LocalDate.parse(digits, formatter).format(SQL_DATE_FORMAT);
            } catch (DateTimeParseException ignored) {
                // Continue with fallback format.
            }
        }

        return null;
    }

    public record SqlGenerationResult(String sql, String failureSummary, String migrationDataCsv, int failureCount,
            int insertCount, List<MigrationFileSummary> fileSummaries) {
        public SqlGenerationResult(String sql, String failureSummary, String migrationDataCsv, int failureCount,
                int insertCount) {
            this(sql, failureSummary, migrationDataCsv, failureCount, insertCount, List.of());
        }
    }

    public record MigrationFileSummary(String sourceFile, int rowsRead, int generatedInsertCount, int skippedCount) {
    }

    private record FailureScenario(String source, long rowNumber, String queryName, String reason) {
    }
}
