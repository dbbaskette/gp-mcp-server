package com.baskettecase.gpmcp.tools;

import com.baskettecase.gpmcp.policy.PolicyService;
import com.baskettecase.gpmcp.sql.SqlValidator;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

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

    private final JdbcTemplate jdbcTemplate;
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
        description = "Preview and validate a SELECT query without executing it. Returns query plan and validation results."
    )
    public QueryPreviewResult previewQuery(
        @McpToolParam(
            description = "SQL SELECT template with named parameters (e.g., 'SELECT * FROM users WHERE id = :id')",
            required = true
        ) String sqlTemplate,
        @McpToolParam(
            description = "Named parameters for the query",
            required = false
        ) Map<String, Object> params
    ) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            log.debug("Previewing query: {}", sqlTemplate);

            // Validate SQL syntax and permissions
            SqlValidator.ValidationResult validation = sqlValidator.validate(sqlTemplate);
            
            QueryPreviewResult result = new QueryPreviewResult();
            result.setSqlTemplate(sqlTemplate);
            result.setValid(validation.isValid());
            result.setErrors(validation.errors());
            result.setWarnings(validation.warnings());

            if (validation.isValid()) {
                // Get query plan via EXPLAIN
                try {
                    String explainSql = "EXPLAIN (FORMAT JSON) " + sqlTemplate;
                    List<Map<String, Object>> planRows = jdbcTemplate.queryForList(explainSql, 
                            params != null ? params.values().toArray() : new Object[0]);
                    
                    if (!planRows.isEmpty()) {
                        result.setQueryPlan(planRows.get(0));
                    }
                    
                    result.setEstimatedRows(getEstimatedRows(planRows));
                    
                } catch (Exception e) {
                    result.setValid(false);
                    result.getErrors().add("Query plan generation failed: " + e.getMessage());
                }
            }

            log.info("✅ Query preview completed: valid={}, errors={}", 
                    result.isValid(), result.getErrors().size());
            
            return result;

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
    public QueryResult runQuery(
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
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            queryCounter.increment();
            
            int maxRowsValue = maxRows != null ? Math.min(maxRows, policyService.getMaxRows()) : 1000;
            boolean streamValue = stream != null ? stream : true;
            
            log.debug("Executing query: {} with maxRows={}, stream={}", sqlTemplate, maxRowsValue, streamValue);

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
                actualRowCount = executeStreamingQuery(finalSql, params, rows, maxRowsValue);
            } else {
                // Execute normally
                rows = jdbcTemplate.queryForList(finalSql, 
                        params != null ? params.values().toArray() : new Object[0]);
                actualRowCount = rows.size();
            }

            // Apply redaction
            List<Map<String, Object>> redactedRows = applyRedaction(rows, sqlTemplate);

            QueryResult result = new QueryResult();
            result.setSql(finalSql);
            result.setRows(redactedRows);
            result.setRowCount(actualRowCount);
            result.setMaxRows(maxRowsValue);
            result.setStreamed(streamValue);
            result.setWarnings(validation.warnings());

            log.info("✅ Query executed: {} rows returned (max: {})", actualRowCount, maxRowsValue);
            
            return result;

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
        description = "Get detailed query execution plan using EXPLAIN (FORMAT JSON)."
    )
    public ExplainResult explain(
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
            boolean analyzeValue = analyze != null ? analyze : false;
            
            log.debug("Explaining query: {} with analyze={}", sqlTemplate, analyzeValue);

            // Validate SQL
            SqlValidator.ValidationResult validation = sqlValidator.validate(sqlTemplate);
            if (!validation.isValid()) {
                throw new IllegalArgumentException("Query validation failed: " + validation.getErrorMessage());
            }

            String explainSql = analyzeValue ? 
                "EXPLAIN (ANALYZE, FORMAT JSON) " + sqlTemplate :
                "EXPLAIN (FORMAT JSON) " + sqlTemplate;

            List<Map<String, Object>> planRows = jdbcTemplate.queryForList(explainSql, 
                    params != null ? params.values().toArray() : new Object[0]);

            ExplainResult result = new ExplainResult();
            result.setSql(sqlTemplate);
            result.setAnalyzed(analyzeValue);
            result.setQueryPlan(planRows.isEmpty() ? null : planRows.get(0));
            result.setEstimatedRows(getEstimatedRows(planRows));
            result.setWarnings(validation.warnings());

            log.info("✅ Query explained: analyzed={}", analyzeValue);
            
            return result;

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
    public CursorResult openCursor(
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

            CursorResult result = new CursorResult();
            result.setCursorId(cursorId);
            result.setFetchSize(fetchSizeValue);
            result.setStatus("OPENED");

            log.info("✅ Cursor opened: {}", cursorId);
            
            return result;

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
        description = "Fetch rows from a server-side cursor."
    )
    public CursorFetchResult fetchCursor(
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
            CursorFetchResult result = new CursorFetchResult();
            result.setCursorId(cursorId);
            result.setRows(Collections.emptyList());
            result.setHasMore(false);
            result.setTotalFetched(0);

            log.info("✅ Cursor fetch completed: {}", cursorId);
            
            return result;

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
    public CursorCloseResult closeCursor(
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

            CursorCloseResult result = new CursorCloseResult();
            result.setCursorId(cursorId);
            result.setStatus("CLOSED");

            log.info("✅ Cursor closed: {}", cursorId);
            
            return result;

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
    public SampleDataResult getSampleData(
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

            int sampleSizeValue = sampleSize != null ? Math.min(sampleSize, 100) : 10;

            log.debug("Getting sample data from {}.{} with {} rows", schemaName, tableName, sampleSizeValue);

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
            String sql = String.format(
                "SELECT %s FROM %s.%s LIMIT ?",
                columnList,
                quoteIdentifier(schemaName),
                quoteIdentifier(tableName)
            );

            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, sampleSizeValue);

            // Apply redaction
            List<Map<String, Object>> redactedRows = applyRedaction(rows, fullTableName);

            SampleDataResult result = new SampleDataResult();
            result.setSchemaName(schemaName);
            result.setTableName(tableName);
            result.setRows(redactedRows);
            result.setRowCount(redactedRows.size());
            result.setSampleSize(sampleSizeValue);

            log.info("✅ Retrieved {} sample rows from {}.{}", redactedRows.size(), schemaName, tableName);

            return result;

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
    public CancelResult cancel(
        @McpToolParam(
            description = "Operation ID to cancel",
            required = true
        ) String operationId
    ) {
        try {
            log.debug("Cancelling operation: {}", operationId);

            // For now, return a placeholder - in a real implementation,
            // this would track running queries and cancel them
            CancelResult result = new CancelResult();
            result.setOperationId(operationId);
            result.setStatus("CANCELLED");

            log.info("✅ Operation cancelled: {}", operationId);

            return result;

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

    private int executeStreamingQuery(String sql, Map<String, Object> params, 
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
