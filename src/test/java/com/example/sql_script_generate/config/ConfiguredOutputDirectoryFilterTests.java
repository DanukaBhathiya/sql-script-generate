package com.example.sql_script_generate.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ConfiguredOutputDirectoryFilterTests {

    @Test
    void configuredDirectoryOverridesRequestValue() throws Exception {
        MigrationOutputProperties properties = new MigrationOutputProperties();
        properties.setDirectory(Paths.get("configured-output"));
        ConfiguredOutputDirectoryFilter filter = new ConfiguredOutputDirectoryFilter(properties);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/sql/generate");
        request.addParameter("outputDir", "request-output");
        AtomicReference<String> resolvedOutputDirectory = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(),
                (wrappedRequest, response) -> resolvedOutputDirectory.set(wrappedRequest.getParameter("outputDir")));

        assertThat(resolvedOutputDirectory.get()).isEqualTo(properties.directoryPath().toString());
    }
}
