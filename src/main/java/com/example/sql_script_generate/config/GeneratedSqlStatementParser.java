package com.example.sql_script_generate.config;

import java.util.ArrayList;
import java.util.List;

final class GeneratedSqlStatementParser {

    private GeneratedSqlStatementParser() {
    }

    static List<String> insertStatements(String sql) {
        List<String> statements = new ArrayList<>();
        int statementStart = 0;
        boolean insideString = false;

        for (int i = 0; i < sql.length(); i++) {
            char current = sql.charAt(i);
            if (current == '\'' && insideString && i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                i++;
                continue;
            }
            if (current == '\'') {
                insideString = !insideString;
            } else if (current == ';' && !insideString) {
                addInsert(statements, sql.substring(statementStart, i));
                statementStart = i + 1;
            }
        }
        addInsert(statements, sql.substring(statementStart));
        return statements;
    }

    private static void addInsert(List<String> statements, String candidate) {
        String statement = candidate.strip();
        if (statement.contains("INSERT INTO \"")) {
            statements.add(statement);
        }
    }
}
