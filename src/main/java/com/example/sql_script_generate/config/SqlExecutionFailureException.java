package com.example.sql_script_generate.config;

public class SqlExecutionFailureException extends RuntimeException {

    public SqlExecutionFailureException(String message, Throwable cause) {
        super(message, cause);
    }
}
