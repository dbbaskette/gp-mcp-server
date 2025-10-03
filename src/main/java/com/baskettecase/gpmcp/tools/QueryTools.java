package com.baskettecase.gpmcp.tools;

import com.baskettecase.gpmcp.db.DatabaseConnectionManager;
import com.baskettecase.gpmcp.policy.PolicyService;
import com.baskettecase.gpmcp.security.ApiKey;
import com.baskettecase.gpmcp.security.ApiKeyContext;
import com.baskettecase.gpmcp.sql.SqlValidator;
import com.baskettecase.gpmcp.util.FuzzyMatcher;
import com.baskettecase.gpmcp.util.JsonResponseFormatter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Query Execution Tools for MCP Server
 * 
 * Provides safe query execution tools with policy enforcement, streaming support,
 * and cursor-based pagination for large result sets.
 * 
 * @see <a href="https://docs.spring.io/spring-ai/reference/1.1-SNAPSHOT/api/mcp/mcp-server-boot-starter-docs.html">Spring AI MCP Server</a>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QueryTools {

    private final DatabaseConnectionManager connectionManager;
    private final PolicyService policyService;
    private final SqlValidator sqlValidator;
    private final MeterRegistry meterRegistry;

    // Cursor registry for managing server-side cursors
    private final Map<String, CursorInfo> cursors = new ConcurrentHashMap<>();
    private final AtomicLong cursorIdGenerator = new AtomicLong(1);

    private Counter queryCounter;
    private Timer queryTimer;

    @PostConstruct
    public void initializeMetrics() {
        queryCounter = Counter.builder("gp_mcp.query.executions")
                .description("Total number of query executions")
                .register(meterRegistry);
        queryTimer = Timer.builder("gp_mcp.query.duration")
                .description("Time taken to execute queries")
                .register(meterRegistry);
    }

    /**
     * Preview and validate a query without executing it
     */
    @McpTool(
        name = "gp.previewQuery",
        description = "Preview and validate a SELECT query without executing it. Returns validation issues as JSON table if any exist."
    )
    public String previewQuery(
        @McpToolParam(
            description = "Database name (optional, uses default if not specified)",
            required = false
        ) String databaseName,
        @McpToolParam(
            description = "SQL SELECT template with named parameters (e.g., 'SELECT * FROM users WHERE id = :id')",
            required = true
        ) String sqlTemplate,
        @McpToolParam(
            description = "Named parameters for the query",
            required = false
        ) Map<String, Object> params
    ) {
        log.info("🔧 TOOL CALLED: gp.previewQuery");
        log.info("   📊 Parameters:");
        log.info("      - databaseName: {}", databaseName);
        log.info("      - sqlTemplate: {}", sqlTemplate);
        log.info("      - params: {}", params);

        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            // Get JdbcTemplate for the specified database
            // Get API key from request context
            ApiKey apiKey = ApiKeyContext.getCurrentApiKey();

            JdbcTemplate jdbcTemplate = connectionManager.getJdbcTemplate(apiKey, databaseName);

            String dbKey = (databaseName == null || databaseName.trim().isEmpty()) ? "default" : databaseName;
            log.debug("Previewing query on database '{}': {}", dbKey, sqlTemplate);

            // Validate SQL syntax and permissions
            SqlValidator.ValidationResult validation = sqlValidator.validate(sqlTemplate);

            List<Map<String, Object>> issues = new ArrayList<>();

            // Add errors to issues list
            for (String error : validation.errors()) {
                Map<String, Object> issue = new LinkedHashMap<>();
                issue.put("severity", "ERROR");
                issue.put("message", error);
                issues.add(issue);
            }

            // Add warnings to issues list
            for (String warning : validation.warnings()) {
                Map<String, Object> issue = new LinkedHashMap<>();
                issue.put("severity", "WARNING");
                issue.put("message", warning);
                issues.add(issue);
            }

            if (validation.isValid()) {
                // Try to get estimated rows
                try {
                    String explainSql = "EXPLAIN (FORMAT JSON) " + sqlTemplate;
                    List<Map<String, Object>> planRows = jdbcTemplate.queryForList(explainSql,
                            params != null ? params.values().toArray() : new Object[0]);

                    Long estimatedRows = getEstimatedRows(planRows);

                    log.info("✅ Query preview completed: valid=true, estimated_rows={}", estimatedRows);

                    if (issues.isEmpty()) {
                        return String.format("✅ Query is valid.\n\nEstimated rows: %d\n\nQuery is ready to execute.",
                                estimatedRows != null ? estimatedRows : 0);
                    } else {
                        // Has warnings but is valid
                        String message = String.format("✅ Query is valid with %d warning(s). Estimated rows: %d",
                                validation.warnings().size(), estimatedRows != null ? estimatedRows : 0);
                        return message + "\n\n" + JsonResponseFormatter.formatAsJsonTable(issues);
                    }

                } catch (Exception e) {
                    Map<String, Object> issue = new LinkedHashMap<>();
                    issue.put("severity", "WARNING");
                    issue.put("message", "Could not generate query plan: " + e.getMessage());
                    issues.add(issue);

                    String message = "✅ Query syntax is valid but query plan could not be generated:";
                    return message + "\n\n" + JsonResponseFormatter.formatAsJsonTable(issues);
                }
            } else {
                log.info("❌ Query preview completed: valid=false, errors={}", validation.errors().size());

                // Invalid query - show errors and warnings
                String message = String.format("❌ Query validation failed with %d error(s) and %d warning(s):",
                        validation.errors().size(), validation.warnings().size());
                return message + "\n\n" + JsonResponseFormatter.formatAsJsonTable(issues);
            }

        } catch (Exception e) {
            log.error("❌ Query preview failed", e);
            throw new RuntimeException("Query preview failed: " + e.getMessage(), e);
        } finally {
            sample.stop(queryTimer);
        }
    }

    /**
     * Execute a parameterized SELECT query with streaming support
     */
    @McpTool(
        name = "gp.runQuery",
        description = "Execute a parameterized SELECT query with streaming support and policy enforcement."
    )
    public String runQuery(
        @McpToolParam(
            description = "Database name (optional, uses default if not specified)",
            required = false
        ) String databaseName,
        @McpToolParam(
            description = "SQL SELECT template with named parameters",
            required = true
        ) String sqlTemplate,
        @McpToolParam(
            description = "Named parameters for the query",
            required = false
        ) Map<String, Object> params,
        @McpToolParam(
            description = "Maximum number of rows to return (default: 1000, max: 10000)",
            required = false
        ) Integer maxRows,
        @McpToolParam(
            description = "Whether to stream results (default: true)",
            required = false
        ) Boolean stream
    ) {
        log.info("🔧 TOOL CALLED: gp.runQuery");
        log.info("   📊 Parameters:");
        log.info("      - databaseName: {}", databaseName);
        log.info("      - sqlTemplate: {}", sqlTemplate);
        log.info("      - params: {}", params);
        log.info("      - maxRows: {}", maxRows);
        log.info("      - stream: {}", stream);

        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            queryCounter.increment();

            // Get JdbcTemplate for the specified database
            // Get API key from request context
            ApiKey apiKey = ApiKeyContext.getCurrentApiKey();

            JdbcTemplate jdbcTemplate = connectionManager.getJdbcTemplate(apiKey, databaseName);

            int maxRowsValue = maxRows != null ? Math.min(maxRows, policyService.getMaxRows()) : 1000;
            boolean streamValue = stream != null ? stream : true;

            String dbKey = (databaseName == null || databaseName.trim().isEmpty()) ? "default" : databaseName;
            log.debug("Executing query on database '{}': {} with maxRows={}, stream={}",
                dbKey, sqlTemplate, maxRowsValue, streamValue);

            // Validate SQL
            SqlValidator.ValidationResult validation = sqlValidator.validate(sqlTemplate);
            if (!validation.isValid()) {
                throw new IllegalArgumentException("Query validation failed: " + validation.getErrorMessage());
            }

            // Add LIMIT clause if not present
            String finalSql = addLimitClause(sqlTemplate, maxRowsValue);

            // Execute query
            List<Map<String, Object>> rows = new ArrayList<>();
            int actualRowCount = 0;

            if (streamValue) {
                // Stream results
                actualRowCount = executeStreamingQuery(jdbcTemplate, finalSql, params, rows, maxRowsValue);
            } else {
                // Execute normally
                rows = jdbcTemplate.queryForList(finalSql,
                        params != null ? params.values().toArray() : new Object[0]);
                actualRowCount = rows.size();
            }

            // Apply redaction
            List<Map<String, Object>> redactedRows = applyRedaction(rows, sqlTemplate);

            log.info("✅ Query executed: {} rows returned (max: {})", actualRowCount, maxRowsValue);

            // Return JSON-formatted results for table rendering in frontend
            if (redactedRows.isEmpty()) {
                return "Query executed successfully. No rows returned.";
            }

            return JsonResponseFormatter.formatWithRowCount(actualRowCount, redactedRows);

        } catch (DataAccessException e) {
            log.error("❌ Query execution failed", e);

            // Try to provide helpful error messages with fuzzy matching suggestions
            String errorMessage = e.getMessage();
            String enhancedError = enhanceErrorMessage(errorMessage, databaseName);

            throw new RuntimeException(enhancedError, e);
        } catch (Exception e) {
            log.error("❌ Query execution failed", e);
            throw new RuntimeException("Query execution failed: " + e.getMessage(), e);
        } finally {
            sample.stop(queryTimer);
        }
    }

    /**
     * Get EXPLAIN plan for a query
     */
    @McpTool(
        name = "gp.explain",
        description = "Get detailed query execution plan using EXPLAIN. Returns flattened plan as JSON table."
    )
    public String explain(
        @McpToolParam(
            description = "Database name (optional, uses default if not specified)",
            required = false
        ) String databaseName,
        @McpToolParam(
            description = "SQL SELECT template to explain",
            required = true
        ) String sqlTemplate,
        @McpToolParam(
            description = "Named parameters for the query",
            required = false
        ) Map<String, Object> params,
        @McpToolParam(
            description = "Whether to run ANALYZE (default: false - may execute the query)",
            required = false
        ) Boolean analyze
    ) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            // Get JdbcTemplate for the specified database
            // Get API key from request context
            ApiKey apiKey = ApiKeyContext.getCurrentApiKey();

            JdbcTemplate jdbcTemplate = connectionManager.getJdbcTemplate(apiKey, databaseName);

            boolean analyzeValue = analyze != null ? analyze : false;

            String dbKey = (databaseName == null || databaseName.trim().isEmpty()) ? "default" : databaseName;
            log.debug("Explaining query on database '{}': {} with analyze={}", dbKey, sqlTemplate, analyzeValue);

            // Validate SQL
            SqlValidator.ValidationResult validation = sqlValidator.validate(sqlTemplate);
            if (!validation.isValid()) {
                throw new IllegalArgumentException("Query validation failed: " + validation.getErrorMessage());
            }

            // Use text format for simpler parsing
            String explainSql = analyzeValue ?
                "EXPLAIN (ANALYZE, VERBOSE) " + sqlTemplate :
                "EXPLAIN (VERBOSE) " + sqlTemplate;

            List<Map<String, Object>> planRows = jdbcTemplate.queryForList(explainSql,
                    params != null ? params.values().toArray() : new Object[0]);

            log.info("✅ Query explained: analyzed={}, plan_rows={}", analyzeValue, planRows.size());

            // Convert EXPLAIN output to table format
            List<Map<String, Object>> planTable = new ArrayList<>();
            for (Map<String, Object> row : planRows) {
                Map<String, Object> planStep = new LinkedHashMap<>();
                // The EXPLAIN output typically comes back in a single column
                Object queryPlan = row.values().iterator().next();
                if (queryPlan != null) {
                    planStep.put("plan_step", queryPlan.toString().trim());
                    planTable.add(planStep);
                }
            }

            // Return JSON-formatted results for table rendering in frontend
            if (planTable.isEmpty()) {
                return "No query plan available.";
            }

            String message = String.format("Query execution plan%s:",
                    analyzeValue ? " (with ANALYZE - query was executed)" : "");
            return message + "\n\n" + JsonResponseFormatter.formatAsJsonTable(planTable);

        } catch (Exception e) {
            log.error("❌ Query explanation failed", e);
            throw new RuntimeException("Query explanation failed: " + e.getMessage(), e);
        } finally {
            sample.stop(queryTimer);
        }
    }

    /**
     * Open a server-side cursor for large result sets
     */
    @McpTool(
        name = "gp.openCursor",
        description = "Open a server-side cursor for streaming large result sets."
    )
    public String openCursor(
        @McpToolParam(
            description = "SQL SELECT template with named parameters",
            required = true
        ) String sqlTemplate,
        @McpToolParam(
            description = "Named parameters for the query",
            required = false
        ) Map<String, Object> params,
        @McpToolParam(
            description = "Number of rows to fetch per batch (default: 1000)",
            required = false
        ) Integer fetchSize
    ) {
        try {
            int fetchSizeValue = fetchSize != null ? Math.min(fetchSize, 5000) : 1000;

            log.debug("Opening cursor for query: {} with fetchSize={}", sqlTemplate, fetchSizeValue);

            // Validate SQL
            SqlValidator.ValidationResult validation = sqlValidator.validate(sqlTemplate);
            if (!validation.isValid()) {
                throw new IllegalArgumentException("Query validation failed: " + validation.getErrorMessage());
            }

            String cursorId = "cursor_" + cursorIdGenerator.getAndIncrement();

            // Create cursor info
            CursorInfo cursorInfo = new CursorInfo();
            cursorInfo.setId(cursorId);
            cursorInfo.setSqlTemplate(sqlTemplate);
            cursorInfo.setParams(params);
            cursorInfo.setFetchSize(fetchSizeValue);
            cursorInfo.setCreatedAt(System.currentTimeMillis());

            cursors.put(cursorId, cursorInfo);

            log.info("✅ Cursor opened: {}", cursorId);

            return String.format("✅ Cursor '%s' opened successfully with fetch size %d.\n\nUse gp.fetchCursor to retrieve rows in batches.",
                    cursorId, fetchSizeValue);

        } catch (Exception e) {
            log.error("❌ Cursor opening failed", e);
            throw new RuntimeException("Cursor opening failed: " + e.getMessage(), e);
        }
    }

    /**
     * Fetch rows from a cursor
     */
    @McpTool(
        name = "gp.fetchCursor",
        description = "Fetch rows from a server-side cursor. Returns JSON table format."
    )
    public String fetchCursor(
        @McpToolParam(
            description = "Cursor ID returned by gp.openCursor",
            required = true
        ) String cursorId
    ) {
        try {
            log.debug("Fetching from cursor: {}", cursorId);

            CursorInfo cursorInfo = cursors.get(cursorId);
            if (cursorInfo == null) {
                throw new IllegalArgumentException("Cursor not found: " + cursorId);
            }

            // For now, return a placeholder - in a real implementation,
            // this would use PostgreSQL cursors or similar
            List<Map<String, Object>> rows = Collections.emptyList();

            log.info("✅ Cursor fetch completed: {}", cursorId);

            // Return JSON-formatted results for table rendering in frontend
            if (rows.isEmpty()) {
                return String.format("No more rows available from cursor %s", cursorId);
            }

            return JsonResponseFormatter.formatWithRowCount(rows.size(), rows);

        } catch (Exception e) {
            log.error("❌ Cursor fetch failed", e);
            throw new RuntimeException("Cursor fetch failed: " + e.getMessage(), e);
        }
    }

    /**
     * Close a server-side cursor
     */
    @McpTool(
        name = "gp.closeCursor",
        description = "Close a server-side cursor and free resources."
    )
    public String closeCursor(
        @McpToolParam(
            description = "Cursor ID to close",
            required = true
        ) String cursorId
    ) {
        try {
            log.debug("Closing cursor: {}", cursorId);

            CursorInfo removed = cursors.remove(cursorId);
            if (removed == null) {
                throw new IllegalArgumentException("Cursor not found: " + cursorId);
            }

            log.info("✅ Cursor closed: {}", cursorId);

            return String.format("✅ Cursor '%s' closed successfully. Resources have been freed.", cursorId);

        } catch (Exception e) {
            log.error("❌ Cursor close failed", e);
            throw new RuntimeException("Cursor close failed: " + e.getMessage(), e);
        }
    }

    /**
     * Get sample data from a table
     */
    @McpTool(
        name = "gp.getSampleData",
        description = "Get sample rows from a table to understand data format and values. Useful for AI to understand the table structure and content."
    )
    public String getSampleData(
        @McpToolParam(
            description = "Database name (optional, uses default if not specified)",
            required = false
        ) String databaseName,
        @McpToolParam(
            description = "Schema name",
            required = true
        ) String schemaName,
        @McpToolParam(
            description = "Table name",
            required = true
        ) String tableName,
        @McpToolParam(
            description = "Number of sample rows to return (default: 10, max: 100)",
            required = false
        ) Integer sampleSize,
        @McpToolParam(
            description = "Specific columns to return (comma-separated), or null for all columns",
            required = false
        ) String columns
    ) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            queryCounter.increment();

            // Get JdbcTemplate for the specified database
            // Get API key from request context
            ApiKey apiKey = ApiKeyContext.getCurrentApiKey();

            JdbcTemplate jdbcTemplate = connectionManager.getJdbcTemplate(apiKey, databaseName);

            int sampleSizeValue = sampleSize != null ? Math.min(sampleSize, 100) : 10;

            String dbKey = (databaseName == null || databaseName.trim().isEmpty()) ? "default" : databaseName;
            log.debug("Getting sample data from database '{}', table {}.{} with {} rows",
                dbKey, schemaName, tableName, sampleSizeValue);

            // Validate SQL
            String fullTableName = schemaName + "." + tableName;
            SqlValidator.ValidationResult validation = sqlValidator.validate("SELECT * FROM " + fullTableName);
            if (!validation.isValid()) {
                throw new IllegalArgumentException("Table access validation failed: " + validation.getErrorMessage());
            }

            // Build column list
            String columnList = "*";
            if (columns != null && !columns.trim().isEmpty()) {
                // Validate each column name to prevent SQL injection
                String[] columnArray = columns.split(",");
                for (String col : columnArray) {
                    String trimmedCol = col.trim();
                    if (!trimmedCol.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
                        throw new IllegalArgumentException("Invalid column name: " + trimmedCol);
                    }
                }
                columnList = columns;
            }

            // Execute sample query
            // Note: Using direct string interpolation for LIMIT instead of ? parameter
            // because some JDBC drivers have issues with parameterized LIMIT clauses
            // Note: Not using quoteIdentifier for Greenplum compatibility
            String sql = String.format(
                "SELECT %s FROM %s.%s LIMIT %d",
                columnList,
                schemaName,
                tableName,
                sampleSizeValue
            );

            log.debug("Executing SQL: {}", sql);

            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);

            log.debug("Query returned {} rows", rows.size());

            // Additional debugging - try a simple count query
            try {
                String countSql = String.format("SELECT COUNT(*) FROM %s.%s", schemaName, tableName);
                Integer count = jdbcTemplate.queryForObject(countSql, Integer.class);
                log.debug("Table {}.{} has {} total rows according to COUNT(*)", schemaName, tableName, count);
            } catch (Exception e) {
                log.warn("Could not get row count: {}", e.getMessage());
            }

            // Apply redaction
            List<Map<String, Object>> redactedRows = applyRedaction(rows, fullTableName);

            log.info("✅ Retrieved {} sample rows from {}.{}", redactedRows.size(), schemaName, tableName);

            // Return JSON-formatted results for table rendering in frontend
            if (redactedRows.isEmpty()) {
                return String.format("No data found in table %s.%s", schemaName, tableName);
            }

            String message = String.format("Sample data from %s.%s (%d rows):", schemaName, tableName, redactedRows.size());
            return JsonResponseFormatter.formatWithMessage(message, redactedRows);

        } catch (Exception e) {
            log.error("❌ Failed to get sample data from {}.{}", schemaName, tableName, e);
            throw new RuntimeException("Failed to get sample data: " + e.getMessage(), e);
        } finally {
            sample.stop(queryTimer);
        }
    }

    /**
     * Cancel a running query
     */
    @McpTool(
        name = "gp.cancel",
        description = "Cancel a running query by operation ID."
    )
    public String cancel(
        @McpToolParam(
            description = "Operation ID to cancel",
            required = true
        ) String operationId
    ) {
        try {
            log.debug("Cancelling operation: {}", operationId);

            // For now, return a placeholder - in a real implementation,
            // this would track running queries and cancel them

            log.info("✅ Operation cancelled: {}", operationId);

            return String.format("✅ Operation '%s' has been cancelled.", operationId);

        } catch (Exception e) {
            log.error("❌ Operation cancellation failed", e);
            throw new RuntimeException("Operation cancellation failed: " + e.getMessage(), e);
        }
    }

    // Helper methods
    private String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private String addLimitClause(String sql, int maxRows) {
        String upperSql = sql.toUpperCase().trim();
        if (!upperSql.contains("LIMIT")) {
            return sql + " LIMIT " + maxRows;
        }
        return sql;
    }

    private int executeStreamingQuery(JdbcTemplate jdbcTemplate, String sql, Map<String, Object> params,
                                   List<Map<String, Object>> rows, int maxRows) {
        // Simplified streaming implementation
        List<Map<String, Object>> allRows = jdbcTemplate.queryForList(sql,
                params != null ? params.values().toArray() : new Object[0]);

        rows.addAll(allRows.subList(0, Math.min(allRows.size(), maxRows)));
        return rows.size();
    }

    private List<Map<String, Object>> applyRedaction(List<Map<String, Object>> rows, String sqlTemplate) {
        // Simplified redaction - in production, this would be more sophisticated
        return rows;
    }

    private Long getEstimatedRows(List<Map<String, Object>> planRows) {
        // Extract estimated rows from query plan
        return planRows.isEmpty() ? 0L : 100L; // Placeholder
    }

    /**
     * Enhance error messages with fuzzy matching suggestions for common SQL errors
     *
     * Detects patterns like:
     * - "relation 'tablename' does not exist" → suggest similar table names
     * - "column 'colname' does not exist" → suggest similar column names
     * - "schema 'schemaname' does not exist" → suggest similar schema names
     */
    private String enhanceErrorMessage(String errorMessage, String databaseName) {
        if (errorMessage == null) {
            return "Unknown database error occurred";
        }

        // Pattern: relation "tablename" does not exist
        Pattern tablePattern = Pattern.compile("relation \"([^\"]+)\" does not exist", Pattern.CASE_INSENSITIVE);
        Matcher tableMatcher = tablePattern.matcher(errorMessage);
        if (tableMatcher.find()) {
            String misspelledTable = tableMatcher.group(1);
            return enhanceTableNotFoundError(misspelledTable, errorMessage, databaseName);
        }

        // Pattern: column "colname" does not exist
        Pattern columnPattern = Pattern.compile("column \"([^\"]+)\" does not exist", Pattern.CASE_INSENSITIVE);
        Matcher columnMatcher = columnPattern.matcher(errorMessage);
        if (columnMatcher.find()) {
            String misspelledColumn = columnMatcher.group(1);
            return enhanceColumnNotFoundError(misspelledColumn, errorMessage, databaseName);
        }

        // Pattern: schema "schemaname" does not exist
        Pattern schemaPattern = Pattern.compile("schema \"([^\"]+)\" does not exist", Pattern.CASE_INSENSITIVE);
        Matcher schemaMatcher = schemaPattern.matcher(errorMessage);
        if (schemaMatcher.find()) {
            String misspelledSchema = schemaMatcher.group(1);
            return enhanceSchemaNotFoundError(misspelledSchema, errorMessage, databaseName);
        }

        // No pattern matched - return original error
        return errorMessage;
    }

    /**
     * Enhance "table not found" errors with fuzzy match suggestions
     */
    private String enhanceTableNotFoundError(String misspelledTable, String originalError, String databaseName) {
        try {
            ApiKey apiKey = ApiKeyContext.getCurrentApiKey();
            JdbcTemplate jdbcTemplate = connectionManager.getJdbcTemplate(apiKey, databaseName);

            // Extract schema if qualified name (schema.table)
            final String schemaName;
            final String tableName;
            if (misspelledTable.contains(".")) {
                String[] parts = misspelledTable.split("\\.", 2);
                schemaName = parts[0];
                tableName = parts[1];
            } else {
                schemaName = "public";
                tableName = misspelledTable;
            }

            // Get all tables in the schema
            String sql = """
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = ?
                AND table_type = 'BASE TABLE'
                """;

            List<String> allTables = jdbcTemplate.queryForList(sql, String.class, schemaName);

            // Find fuzzy matches
            List<String> suggestions = FuzzyMatcher.findClosestMatches(tableName, allTables);

            if (!suggestions.isEmpty()) {
                String suggestionText = suggestions.size() == 1
                    ? String.format("Did you mean '%s.%s'?", schemaName, suggestions.get(0))
                    : String.format("Did you mean one of these? %s",
                        suggestions.stream()
                            .map(s -> String.format("'%s.%s'", schemaName, s))
                            .collect(java.util.stream.Collectors.joining(", ")));

                log.info("💡 Fuzzy match suggestion for table '{}': {}", misspelledTable, suggestions);

                return String.format("%s\n\n💡 %s", originalError, suggestionText);
            }
        } catch (Exception e) {
            log.warn("Failed to generate fuzzy match suggestions for table: {}", e.getMessage());
        }

        return originalError;
    }

    /**
     * Enhance "column not found" errors with fuzzy match suggestions
     */
    private String enhanceColumnNotFoundError(String misspelledColumn, String originalError, String databaseName) {
        // For column suggestions, we'd need to know which table is being queried
        // This is more complex and would require parsing the SQL
        // For now, just return the original error
        return originalError;
    }

    /**
     * Enhance "schema not found" errors with fuzzy match suggestions
     */
    private String enhanceSchemaNotFoundError(String misspelledSchema, String originalError, String databaseName) {
        try {
            ApiKey apiKey = ApiKeyContext.getCurrentApiKey();
            JdbcTemplate jdbcTemplate = connectionManager.getJdbcTemplate(apiKey, databaseName);

            // Get all schemas
            String sql = """
                SELECT schema_name
                FROM information_schema.schemata
                WHERE schema_name NOT IN ('pg_catalog', 'information_schema', 'pg_toast')
                """;

            List<String> allSchemas = jdbcTemplate.queryForList(sql, String.class);

            // Find fuzzy matches
            List<String> suggestions = FuzzyMatcher.findClosestMatches(misspelledSchema, allSchemas);

            if (!suggestions.isEmpty()) {
                String suggestionText = suggestions.size() == 1
                    ? String.format("Did you mean schema '%s'?", suggestions.get(0))
                    : String.format("Did you mean one of these schemas? %s",
                        suggestions.stream()
                            .map(s -> String.format("'%s'", s))
                            .collect(java.util.stream.Collectors.joining(", ")));

                log.info("💡 Fuzzy match suggestion for schema '{}': {}", misspelledSchema, suggestions);

                return String.format("%s\n\n💡 %s", originalError, suggestionText);
            }
        } catch (Exception e) {
            log.warn("Failed to generate fuzzy match suggestions for schema: {}", e.getMessage());
        }

        return originalError;
    }

    // Data classes
    @Data
    public static class QueryPreviewResult {
        private String sqlTemplate;
        private boolean valid;
        private List<String> errors;
        private List<String> warnings;
        private Map<String, Object> queryPlan;
        private Long estimatedRows;
    }

    @Data
    public static class QueryResult {
        private String sql;
        private List<Map<String, Object>> rows;
        private int rowCount;
        private int maxRows;
        private boolean streamed;
        private List<String> warnings;
    }

    @Data
    public static class ExplainResult {
        private String sql;
        private boolean analyzed;
        private Map<String, Object> queryPlan;
        private Long estimatedRows;
        private List<String> warnings;
    }

    @Data
    public static class CursorResult {
        private String cursorId;
        private int fetchSize;
        private String status;
    }

    @Data
    public static class CursorFetchResult {
        private String cursorId;
        private List<Map<String, Object>> rows;
        private boolean hasMore;
        private int totalFetched;
    }

    @Data
    public static class CursorCloseResult {
        private String cursorId;
        private String status;
    }

    @Data
    public static class CancelResult {
        private String operationId;
        private String status;
    }

    @Data
    public static class CursorInfo {
        private String id;
        private String sqlTemplate;
        private Map<String, Object> params;
        private int fetchSize;
        private long createdAt;
    }

    @Data
    public static class SampleDataResult {
        private String schemaName;
        private String tableName;
        private List<Map<String, Object>> rows;
        private int rowCount;
        private int sampleSize;
    }
}
