package com.example.sql_script_generate.config;

import java.util.Arrays;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class MigrationStartupLogger implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(MigrationStartupLogger.class);

    private final Environment environment;
    private final MigrationInputProperties inputProperties;
    private final MigrationOutputProperties outputProperties;
    private final ObjectProvider<DataSource> dataSourceProvider;

    public MigrationStartupLogger(Environment environment, MigrationInputProperties inputProperties,
            MigrationOutputProperties outputProperties, ObjectProvider<DataSource> dataSourceProvider) {
        this.environment = environment;
        this.inputProperties = inputProperties;
        this.outputProperties = outputProperties;
        this.dataSourceProvider = dataSourceProvider;
    }

    @Override
    public void run(ApplicationArguments args) {
        LOGGER.info("Migration service configuration: profiles={}, inputDirectory={}, outputDirectory={}, "
                        + "usersFile={}, beneficiariesFile={}, templatesFile={}, datasourceConfigured={}",
                Arrays.toString(environment.getActiveProfiles()), inputProperties.getDirectory().toAbsolutePath().normalize(),
                outputProperties.directoryPath(), inputProperties.getUsersFile(), inputProperties.getBeneficiariesFile(),
                inputProperties.getTemplatesFile(), dataSourceProvider.getIfAvailable() != null);
    }
}
