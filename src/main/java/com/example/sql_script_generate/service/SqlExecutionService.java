package com.example.sql_script_generate.service;

import java.nio.charset.StandardCharsets;

import javax.sql.DataSource;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SqlExecutionService {

    private final ObjectProvider<DataSource> dataSourceProvider;

    public SqlExecutionService(ObjectProvider<DataSource> dataSourceProvider) {
        this.dataSourceProvider = dataSourceProvider;
    }

    @Transactional
    public SqlExecutionResult executeGeneratedSql(String sql, int expectedInsertCount) {
        DataSource dataSource = dataSourceProvider.getIfAvailable();
        if (dataSource == null) {
            throw new IllegalStateException("No datasource configured. Start the service with the dev or qa profile to execute SQL.");
        }

        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
        populator.setContinueOnError(false);
        populator.addScript(new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8), "generated migration SQL"));
        populator.execute(dataSource);

        return new SqlExecutionResult(expectedInsertCount);
    }

    public record SqlExecutionResult(int expectedInsertCount) {
    }
}
