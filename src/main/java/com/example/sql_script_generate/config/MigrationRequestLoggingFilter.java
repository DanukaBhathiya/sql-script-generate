package com.example.sql_script_generate.config;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MigrationRequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(MigrationRequestLoggingFilter.class);
    private final MigrationExecutionContext executionContext;

    public MigrationRequestLoggingFilter(MigrationExecutionContext executionContext) {
        this.executionContext = executionContext;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!"POST".equalsIgnoreCase(request.getMethod()) || !"/api/sql/generate".equals(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }

        String requestId = UUID.randomUUID().toString();
        long startedAt = System.nanoTime();
        MDC.put("migrationRequestId", requestId);
        LOGGER.info("Migration request started: saveToDisk={}, executeToDb={}",
                parameterOrDefault(request, "saveToDisk", "true"),
                parameterOrDefault(request, "executeToDb", "false"));

        try {
            filterChain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException ex) {
            LOGGER.error("Migration request failed: exceptionType={}", ex.getClass().getSimpleName());
            throw ex;
        } finally {
            long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
            LOGGER.info("Migration request completed: status={}, insertCount={}, skippedCount={}, dbExecution={}, durationMs={}",
                    response.getStatus(), response.getHeader("X-Insert-Count"), response.getHeader("X-Fail-Count"),
                    response.getHeader("X-Db-Execution"), durationMs);
            executionContext.clear();
            MDC.remove("migrationRequestId");
        }
    }

    private String parameterOrDefault(HttpServletRequest request, String name, String defaultValue) {
        String value = request.getParameter(name);
        return value == null ? defaultValue : value;
    }
}
