package com.example.sql_script_generate.config;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class ConfiguredOutputDirectoryFilter extends OncePerRequestFilter {

    private final MigrationOutputProperties outputProperties;

    public ConfiguredOutputDirectoryFilter(MigrationOutputProperties outputProperties) {
        this.outputProperties = outputProperties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!"POST".equalsIgnoreCase(request.getMethod()) || !"/api/sql/generate".equals(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }

        String configuredOutputDirectory = outputProperties.directoryPath().toString();
        HttpServletRequest wrappedRequest = new HttpServletRequestWrapper(request) {
            @Override
            public String getParameter(String name) {
                if ("outputDir".equals(name)) {
                    return configuredOutputDirectory;
                }
                return super.getParameter(name);
            }

            @Override
            public String[] getParameterValues(String name) {
                if ("outputDir".equals(name)) {
                    return new String[] { configuredOutputDirectory };
                }
                return super.getParameterValues(name);
            }
        };

        filterChain.doFilter(wrappedRequest, response);
    }
}
