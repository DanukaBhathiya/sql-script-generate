package com.example.sql_script_generate.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "migration.database")
public class MigrationDatabaseProperties {

    private String schema = "public";
    private String pendingUserTable = "pending_user";
    private String pendingUserSequence = "pending_user_id_seq";
    private boolean autoCreatePendingUserSequence = true;
    private boolean autoCreateMigrationUniqueIndexes = true;

    public String getSchema() {
        return schema;
    }

    public void setSchema(String schema) {
        this.schema = schema;
    }

    public String getPendingUserTable() {
        return pendingUserTable;
    }

    public void setPendingUserTable(String pendingUserTable) {
        this.pendingUserTable = pendingUserTable;
    }

    public String getPendingUserSequence() {
        return pendingUserSequence;
    }

    public void setPendingUserSequence(String pendingUserSequence) {
        this.pendingUserSequence = pendingUserSequence;
    }

    public boolean isAutoCreatePendingUserSequence() {
        return autoCreatePendingUserSequence;
    }

    public void setAutoCreatePendingUserSequence(boolean autoCreatePendingUserSequence) {
        this.autoCreatePendingUserSequence = autoCreatePendingUserSequence;
    }

    public boolean isAutoCreateMigrationUniqueIndexes() {
        return autoCreateMigrationUniqueIndexes;
    }

    public void setAutoCreateMigrationUniqueIndexes(boolean autoCreateMigrationUniqueIndexes) {
        this.autoCreateMigrationUniqueIndexes = autoCreateMigrationUniqueIndexes;
    }
}
