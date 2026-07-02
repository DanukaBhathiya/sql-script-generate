package com.example.sql_script_generate.config;

import java.util.Map;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class PendingUserSequenceManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(PendingUserSequenceManager.class);
    private static final String IDENTIFIER_PATTERN = "[A-Za-z_][A-Za-z0-9_]*";

    private final ObjectProvider<DataSource> dataSourceProvider;
    private final MigrationDatabaseProperties properties;

    public PendingUserSequenceManager(ObjectProvider<DataSource> dataSourceProvider,
            MigrationDatabaseProperties properties) {
        this.dataSourceProvider = dataSourceProvider;
        this.properties = properties;
    }

    public void ensureReady() {
        DataSource dataSource = dataSourceProvider.getIfAvailable();
        if (dataSource == null) {
            throw new IllegalStateException("No datasource configured for pending_user sequence validation");
        }

        String schema = validatedIdentifier(properties.getSchema(), "schema");
        String table = validatedIdentifier(properties.getPendingUserTable(), "pending user table");
        String configuredSequence = validatedIdentifier(properties.getPendingUserSequence(), "pending user sequence");
        String tableRegclass = schema + "." + table;
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        String sequenceRegclass = jdbcTemplate.queryForObject(
                "SELECT pg_get_serial_sequence(?, 'id')", String.class, tableRegclass);
        if (!StringUtils.hasText(sequenceRegclass)) {
            sequenceRegclass = createAndAttachSequence(jdbcTemplate, schema, table, configuredSequence);
        }

        synchronizeSequence(jdbcTemplate, schema, table, sequenceRegclass);
        LOGGER.info("pending_user ID sequence is ready: table={}.{}, sequence={}",
                schema, table, sequenceRegclass);
    }

    private String createAndAttachSequence(JdbcTemplate jdbcTemplate, String schema, String table,
            String sequence) {
        String columnDefault = jdbcTemplate.queryForObject(
                "SELECT column_default FROM information_schema.columns "
                        + "WHERE table_schema = ? AND table_name = ? AND column_name = 'id'",
                String.class, schema, table);
        if (StringUtils.hasText(columnDefault)) {
            throw new IllegalStateException(
                    "pending_user.id has a default that is not an owned PostgreSQL sequence");
        }
        if (!properties.isAutoCreatePendingUserSequence()) {
            throw new IllegalStateException(
                    "pending_user.id has no sequence default and automatic sequence creation is disabled");
        }

        String qualifiedTable = quoteIdentifier(schema) + "." + quoteIdentifier(table);
        String qualifiedSequence = quoteIdentifier(schema) + "." + quoteIdentifier(sequence);
        String sequenceRegclass = schema + "." + sequence;
        jdbcTemplate.execute("CREATE SEQUENCE IF NOT EXISTS " + qualifiedSequence);
        jdbcTemplate.execute("ALTER SEQUENCE " + qualifiedSequence + " OWNED BY " + qualifiedTable + ".\"id\"");
        jdbcTemplate.execute("ALTER TABLE " + qualifiedTable
                + " ALTER COLUMN \"id\" SET DEFAULT nextval('" + sequenceRegclass + "'::regclass)");
        LOGGER.warn("Created and attached pending_user ID sequence: table={}.{}, sequence={}",
                schema, table, sequenceRegclass);
        return sequenceRegclass;
    }

    private void synchronizeSequence(JdbcTemplate jdbcTemplate, String schema, String table,
            String sequenceRegclass) {
        Map<String, Object> sequenceIdentity = jdbcTemplate.queryForMap(
                "SELECT n.nspname AS sequence_schema, c.relname AS sequence_name "
                        + "FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                        + "WHERE c.oid = CAST(? AS regclass)",
                sequenceRegclass);
        String sequenceSchema = (String) sequenceIdentity.get("sequence_schema");
        String sequenceName = (String) sequenceIdentity.get("sequence_name");
        String qualifiedSequence = quoteIdentifier(sequenceSchema) + "." + quoteIdentifier(sequenceName);
        String qualifiedTable = quoteIdentifier(schema) + "." + quoteIdentifier(table);

        Long maximumId = jdbcTemplate.queryForObject(
                "SELECT MAX(\"id\") FROM " + qualifiedTable, Long.class);
        if (maximumId == null) {
            return;
        }

        Map<String, Object> sequenceState = jdbcTemplate.queryForMap(
                "SELECT last_value, is_called FROM " + qualifiedSequence);
        long lastValue = ((Number) sequenceState.get("last_value")).longValue();
        boolean isCalled = (Boolean) sequenceState.get("is_called");
        if (lastValue < maximumId || (lastValue == maximumId && !isCalled)) {
            jdbcTemplate.queryForObject(
                    "SELECT setval(CAST(? AS regclass), ?, true)", Long.class,
                    sequenceRegclass, maximumId);
            LOGGER.info("Advanced pending_user ID sequence to current table maximum");
        }
    }

    private String validatedIdentifier(String value, String description) {
        if (value == null || !value.matches(IDENTIFIER_PATTERN)) {
            throw new IllegalStateException("Invalid migration database " + description + " identifier");
        }
        return value;
    }

    private String quoteIdentifier(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
