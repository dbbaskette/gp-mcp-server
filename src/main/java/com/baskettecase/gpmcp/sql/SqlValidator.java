package com.baskettecase.gpmcp.sql;

import com.baskettecase.gpmcp.policy.PolicyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.SelectBody;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.Join;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * SQL Validator for Security Enforcement
 * 
 * Validates SQL queries against security policies using JSQLParser.
 * Ensures only SELECT statements are allowed and validates table/column access.
 * 
 * @see <a href="https://github.com/JSQLParser/JSqlParser">JSQLParser Documentation</a>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SqlValidator {

    private final PolicyService policyService;

    // Pattern to detect multi-statement queries (semicolon outside string literals)
    private static final Pattern MULTI_STATEMENT_PATTERN = Pattern.compile(
        ";(?=(?:[^']*'[^']*')*[^']*$)", Pattern.CASE_INSENSITIVE
    );

    /**
     * Validate SQL query against security policies
     */
    public ValidationResult validate(String sqlTemplate) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        try {
            // Check for multi-statement queries
            if (MULTI_STATEMENT_PATTERN.matcher(sqlTemplate).find()) {
                errors.add("Multi-statement queries are not allowed");
                return new ValidationResult(false, errors, warnings);
            }

            // Parse SQL
            Statement statement = CCJSqlParserUtil.parse(sqlTemplate);
            
            // Ensure it's a SELECT statement
            if (!(statement instanceof Select)) {
                errors.add("Only SELECT statements are allowed");
                return new ValidationResult(false, errors, warnings);
            }

            Select select = (Select) statement;
            SelectBody selectBody = select.getSelectBody();

            if (selectBody instanceof PlainSelect) {
                PlainSelect plainSelect = (PlainSelect) selectBody;
                
                // Validate tables
                validateTables(plainSelect, errors, warnings);
                
                // Validate columns (basic check)
                validateColumns(plainSelect, errors, warnings);
                
                // Check for dangerous functions
                validateFunctions(sqlTemplate, errors, warnings);
                
            } else {
                errors.add("Complex SELECT statements (UNION, etc.) are not supported");
            }

        } catch (JSQLParserException e) {
            errors.add("Invalid SQL syntax: " + e.getMessage());
        } catch (Exception e) {
            errors.add("SQL validation error: " + e.getMessage());
        }

        boolean isValid = errors.isEmpty();
        if (isValid) {
            log.debug("✅ SQL validation passed: {}", sqlTemplate);
        } else {
            log.warn("❌ SQL validation failed: {} - Errors: {}", sqlTemplate, errors);
        }

        return new ValidationResult(isValid, errors, warnings);
    }

    /**
     * Validate table access permissions
     */
    private void validateTables(PlainSelect select, List<String> errors, List<String> warnings) {
        Table fromTable = (Table) select.getFromItem();
        if (fromTable != null) {
            validateTableAccess(fromTable, errors, warnings);
        }

        // Validate JOIN tables
        List<Join> joins = select.getJoins();
        if (joins != null) {
            for (Join join : joins) {
                if (join.getRightItem() instanceof Table) {
                    validateTableAccess((Table) join.getRightItem(), errors, warnings);
                }
            }
        }
    }

    /**
     * Validate access to a specific table
     */
    private void validateTableAccess(Table table, List<String> errors, List<String> warnings) {
        String schemaName = table.getSchemaName();
        String tableName = table.getName();

        // Default to 'public' schema if not specified
        if (schemaName == null) {
            schemaName = "public";
        }

        // Check schema permission
        if (!policyService.isSchemaAllowed(schemaName)) {
            errors.add("Access denied to schema: " + schemaName);
            return;
        }

        // Check table permission
        if (!policyService.isTableAllowed(schemaName, tableName)) {
            errors.add("Access denied to table: " + schemaName + "." + tableName);
        }
    }

    /**
     * Validate column access permissions
     */
    private void validateColumns(PlainSelect select, List<String> errors, List<String> warnings) {
        // For now, we'll do basic validation
        // In a more sophisticated implementation, we'd parse the SELECT list
        // and validate each column reference
        
        // Check for SELECT * - warn but don't block
        String sql = select.toString().toLowerCase();
        if (sql.contains("select *")) {
            warnings.add("SELECT * queries may expose sensitive columns");
        }
    }

    /**
     * Check for potentially dangerous functions
     */
    private void validateFunctions(String sql, List<String> errors, List<String> warnings) {
        String lowerSql = sql.toLowerCase();
        
        // Block dangerous functions
        String[] dangerousFunctions = {
            "pg_read_file", "pg_ls_dir", "lo_import", "lo_export",
            "copy", "create", "drop", "alter", "insert", "update", "delete",
            "grant", "revoke", "exec", "execute"
        };
        
        for (String func : dangerousFunctions) {
            if (lowerSql.contains(func)) {
                errors.add("Dangerous function/statement detected: " + func);
            }
        }
    }

    /**
     * Validation result
     */
    public record ValidationResult(
        boolean isValid,
        List<String> errors,
        List<String> warnings
    ) {
        public String getErrorMessage() {
            return String.join("; ", errors);
        }
        
        public String getWarningMessage() {
            return String.join("; ", warnings);
        }
    }
}
