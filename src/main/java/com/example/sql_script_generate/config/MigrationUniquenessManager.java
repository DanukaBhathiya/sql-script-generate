package com.example.sql_script_generate.config;

import java.util.List;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class MigrationUniquenessManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(MigrationUniquenessManager.class);

    private final ObjectProvider<DataSource> dataSourceProvider;
    private final MigrationDatabaseProperties properties;

    public MigrationUniquenessManager(ObjectProvider<DataSource> dataSourceProvider,
            MigrationDatabaseProperties properties) {
        this.dataSourceProvider = dataSourceProvider;
        this.properties = properties;
    }

    public void ensureReady() {
        DataSource dataSource = dataSourceProvider.getIfAvailable();
        if (dataSource == null) {
            throw new IllegalStateException("No datasource configured for migration uniqueness validation");
        }
        if (!properties.isAutoCreateMigrationUniqueIndexes()) {
            LOGGER.info("Automatic migration unique-index creation is disabled");
            return;
        }

        String schema = validatedIdentifier(properties.getSchema(), "schema");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        List<UniqueIndexDefinition> indexes = List.of(
                new UniqueIndexDefinition(
                        "uq_migrate_beneficiary_cif_nickname",
                        "migrate_beneficiary",
                        "cif, LOWER(nickname)",
                        "cif IS NOT NULL AND nickname IS NOT NULL",
                        "cif, LOWER(nickname)"),
                new UniqueIndexDefinition(
                        "uq_migrate_beneficiary_cif_account_type",
                        "migrate_beneficiary",
                        "cif, account_number, type",
                        "cif IS NOT NULL AND account_number IS NOT NULL AND type IS NOT NULL",
                        "cif, account_number, type"),
                new UniqueIndexDefinition(
                        "uq_migrate_template_cif_name",
                        "migrate_template",
                        "cif, LOWER(template_name)",
                        "cif IS NOT NULL AND template_name IS NOT NULL",
                        "cif, LOWER(template_name)")
        );

        for (UniqueIndexDefinition index : indexes) {
            ensureIndex(jdbcTemplate, schema, index);
        }
    }

    private void ensureIndex(JdbcTemplate jdbcTemplate, String schema, UniqueIndexDefinition index) {
        String indexRegclass = schema + "." + index.name();
        Boolean exists = jdbcTemplate.queryForObject(
                "SELECT to_regclass(?) IS NOT NULL", Boolean.class, indexRegclass);
        if (Boolean.TRUE.equals(exists)) {
            return;
        }

        String qualifiedTable = quoteIdentifier(schema) + "." + quoteIdentifier(index.table());
        Integer duplicateGroups = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM (SELECT " + index.groupByExpression()
                        + " FROM " + qualifiedTable
                        + " WHERE " + index.nonNullPredicate()
                        + " GROUP BY " + index.groupByExpression()
                        + " HAVING COUNT(*) > 1) duplicate_groups",
                Integer.class);
        if (duplicateGroups != null && duplicateGroups > 0) {
            throw new IllegalStateException("Cannot create migration unique index " + index.name()
                    + ": existing duplicate groups=" + duplicateGroups);
        }

        jdbcTemplate.execute("CREATE UNIQUE INDEX " + quoteIdentifier(index.name())
                + " ON " + qualifiedTable + " (" + index.indexExpression() + ")");
        LOGGER.info("Created migration unique index: index={}, table={}.{}",
                index.name(), schema, index.table());
    }

    private String validatedIdentifier(String value, String description) {
        if (value == null || !value.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalStateException("Invalid migration database " + description + " identifier");
        }
        return value;
    }

    private String quoteIdentifier(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private record UniqueIndexDefinition(String name, String table, String indexExpression,
            String nonNullPredicate, String groupByExpression) {
    }
}
