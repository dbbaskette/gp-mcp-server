package com.baskettecase.gpmcp.tools;

import com.baskettecase.gpmcp.policy.PolicyService;
import com.baskettecase.gpmcp.util.JsonResponseFormatter;
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
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/**
 * Schema Discovery Tools for MCP Server
 * 
 * Provides tools for discovering database schemas, tables, and columns.
 * Enforces policy-based access control and pagination for large result sets.
 * 
 * @see <a href="https://docs.spring.io/spring-ai/reference/1.1-SNAPSHOT/api/mcp/mcp-annotations-server.html">Spring AI MCP Annotations</a>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SchemaTools {

    private final JdbcTemplate jdbcTemplate;
    private final PolicyService policyService;
    private final MeterRegistry meterRegistry;

    private Counter schemaQueryCounter;
    private Timer schemaQueryTimer;

    @PostConstruct
    public void initializeMetrics() {
        schemaQueryCounter = Counter.builder("gp_mcp.schema.queries")
                .description("Total number of schema queries processed")
                .register(meterRegistry);
        schemaQueryTimer = Timer.builder("gp_mcp.schema.query.duration")
                .description("Time taken to process schema queries")
                .register(meterRegistry);
    }

    /**
     * List available schemas with optional pagination
     */
    @McpTool(
        name = "gp.listSchemas",
        description = "List available database schemas with table counts. Returns JSON table format."
    )
    public String listSchemas(
        @McpToolParam(
            description = "Maximum number of schemas to return (default: 50)",
            required = false
        ) Integer limit,
        @McpToolParam(
            description = "Number of schemas to skip (default: 0)",
            required = false
        ) Integer offset
    ) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            schemaQueryCounter.increment();

            int limitValue = limit != null ? Math.min(limit, 100) : 50;
            int offsetValue = offset != null ? Math.max(offset, 0) : 0;

            log.debug("Listing schemas: limit={}, offset={}", limitValue, offsetValue);

            // Get allowed schemas from policy
            Set<String> allowedSchemas = policyService.getAllowedSchemas();

            if (allowedSchemas.isEmpty()) {
                log.warn("No allowed schemas configured");
                return "No schemas are accessible according to policy configuration.";
            }

            // Build IN clause for allowed schemas
            String schemaPlaceholders = String.join(",", allowedSchemas.stream()
                .map(s -> "?")
                .toList());

            // Query database for schema information
            String sql = String.format("""
                SELECT schema_name,
                       COUNT(table_name) as table_count
                FROM information_schema.schemata s
                LEFT JOIN information_schema.tables t ON s.schema_name = t.table_schema
                WHERE schema_name IN (%s)
                GROUP BY schema_name
                ORDER BY schema_name
                LIMIT ? OFFSET ?
                """, schemaPlaceholders);

            // Build parameters array
            List<Object> params = new ArrayList<>(allowedSchemas);
            params.add(limitValue);
            params.add(offsetValue);

            List<Map<String, Object>> schemaRows = jdbcTemplate.queryForList(sql, params.toArray());

            List<Map<String, Object>> schemas = new ArrayList<>();
            for (Map<String, Object> row : schemaRows) {
                Map<String, Object> schemaData = new LinkedHashMap<>();
                schemaData.put("schema", row.get("schema_name"));
                schemaData.put("table_count", row.get("table_count"));
                schemas.add(schemaData);
            }

            log.info("✅ Listed {} schemas (limit={}, offset={})", schemas.size(), limitValue, offsetValue);

            // Return JSON-formatted results for table rendering in frontend
            if (schemas.isEmpty()) {
                return "No schemas found.";
            }

            String message = String.format("Found %d database schemas:", schemas.size());
            return JsonResponseFormatter.formatWithMessage(message, schemas);

        } catch (Exception e) {
            log.error("❌ Failed to list schemas", e);
            throw new RuntimeException("Failed to list schemas: " + e.getMessage(), e);
        } finally {
            sample.stop(schemaQueryTimer);
        }
    }

    /**
     * Get tables for a specific schema
     */
    private List<TableInfo> getTablesForSchema(String schemaName, boolean includeColumns) {
        try {
            String sql = """
                SELECT table_name, table_type, 
                       COUNT(column_name) as column_count
                FROM information_schema.tables t
                LEFT JOIN information_schema.columns c ON t.table_name = c.table_name 
                    AND t.table_schema = c.table_schema
                WHERE t.table_schema = ? 
                    AND (t.table_name LIKE 'public.%' OR t.table_name NOT LIKE '%.%')
                GROUP BY table_name, table_type
                ORDER BY table_name
                """;
            
            List<Map<String, Object>> tableRows = jdbcTemplate.queryForList(sql, schemaName);
            List<TableInfo> tables = new ArrayList<>();
            
            for (Map<String, Object> row : tableRows) {
                String tableName = (String) row.get("table_name");
                String tableType = (String) row.get("table_type");
                Long columnCount = ((Number) row.get("column_count")).longValue();
                
                // Check if table is allowed
                if (!policyService.isTableAllowed(schemaName, tableName)) {
                    continue;
                }
                
                TableInfo tableInfo = new TableInfo();
                tableInfo.setName(tableName);
                tableInfo.setType(tableType);
                tableInfo.setColumnCount(columnCount);
                
                if (includeColumns) {
                    tableInfo.setColumns(getColumnsForTable(schemaName, tableName));
                }
                
                tables.add(tableInfo);
            }
            
            return tables;
            
        } catch (Exception e) {
            log.warn("Failed to get tables for schema {}: {}", schemaName, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Get columns for a specific table
     */
    private List<ColumnInfo> getColumnsForTable(String schemaName, String tableName) {
        try {
            String sql = """
                SELECT column_name, data_type, is_nullable, column_default,
                       character_maximum_length, numeric_precision, numeric_scale
                FROM information_schema.columns
                WHERE table_schema = ? AND table_name = ?
                ORDER BY ordinal_position
                """;
            
            List<Map<String, Object>> columnRows = jdbcTemplate.queryForList(sql, schemaName, tableName);
            List<ColumnInfo> columns = new ArrayList<>();
            
            for (Map<String, Object> row : columnRows) {
                String columnName = (String) row.get("column_name");
                
                // Check if column is allowed
                if (!policyService.isColumnAllowed(schemaName, tableName, columnName)) {
                    continue;
                }
                
                ColumnInfo columnInfo = new ColumnInfo();
                columnInfo.setName(columnName);
                columnInfo.setDataType((String) row.get("data_type"));
                columnInfo.setNullable("YES".equals(row.get("is_nullable")));
                columnInfo.setDefaultValue((String) row.get("column_default"));
                
                // Set length/precision for appropriate types
                Object maxLength = row.get("character_maximum_length");
                if (maxLength != null) {
                    columnInfo.setMaxLength(((Number) maxLength).intValue());
                }
                
                Object precision = row.get("numeric_precision");
                if (precision != null) {
                    columnInfo.setPrecision(((Number) precision).intValue());
                }
                
                Object scale = row.get("numeric_scale");
                if (scale != null) {
                    columnInfo.setScale(((Number) scale).intValue());
                }
                
                columns.add(columnInfo);
            }
            
            return columns;
            
        } catch (Exception e) {
            log.warn("Failed to get columns for table {}.{}: {}", schemaName, tableName, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * List tables in a specific schema
     */
    @McpTool(
        name = "gp.listTables",
        description = "List all tables in a specific schema with metadata including row counts, size, and type."
    )
    public String listTables(
        @McpToolParam(
            description = "Schema name to list tables from",
            required = true
        ) String schemaName,
        @McpToolParam(
            description = "Maximum number of tables to return (default: 100)",
            required = false
        ) Integer limit,
        @McpToolParam(
            description = "Number of tables to skip (default: 0)",
            required = false
        ) Integer offset,
        @McpToolParam(
            description = "Include row count estimates (may be slow for large tables)",
            required = false
        ) Boolean includeRowCounts
    ) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            schemaQueryCounter.increment();

            int limitValue = limit != null ? Math.min(limit, 500) : 100;
            int offsetValue = offset != null ? Math.max(offset, 0) : 0;
            boolean includeRowCountsValue = includeRowCounts != null ? includeRowCounts : false;

            log.debug("Listing tables in schema: {}, limit={}, offset={}", schemaName, limitValue, offsetValue);

            // Validate schema access
            if (!policyService.isSchemaAllowed(schemaName)) {
                throw new SecurityException("Access denied to schema: " + schemaName);
            }

            String sql = """
                SELECT
                    t.table_name,
                    t.table_type,
                    pg_size_pretty(pg_total_relation_size(quote_ident(t.table_schema)||'.'||quote_ident(t.table_name))) as total_size,
                    obj_description((quote_ident(t.table_schema)||'.'||quote_ident(t.table_name))::regclass) as table_comment
                FROM information_schema.tables t
                WHERE t.table_schema = ?
                    AND t.table_type IN ('BASE TABLE', 'VIEW')
                ORDER BY t.table_name
                LIMIT ? OFFSET ?
                """;

            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, schemaName, limitValue, offsetValue);
            List<Map<String, Object>> tables = new ArrayList<>();

            for (Map<String, Object> row : rows) {
                String tableName = (String) row.get("table_name");

                // Check policy
                if (!policyService.isTableAllowed(schemaName, tableName)) {
                    continue;
                }

                Map<String, Object> tableData = new LinkedHashMap<>();
                tableData.put("schema", schemaName);
                tableData.put("table", tableName);
                tableData.put("type", row.get("table_type"));
                tableData.put("size", row.get("total_size"));

                if (row.get("table_comment") != null) {
                    tableData.put("comment", row.get("table_comment"));
                }

                if (includeRowCountsValue) {
                    try {
                        Long rowCount = jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM " + quote(schemaName) + "." + quote(tableName),
                            Long.class
                        );
                        tableData.put("row_count", rowCount);
                    } catch (Exception e) {
                        log.warn("Failed to get row count for {}.{}: {}", schemaName, tableName, e.getMessage());
                    }
                }

                tables.add(tableData);
            }

            log.info("✅ Listed {} tables in schema {} (limit={}, offset={})", tables.size(), schemaName, limitValue, offsetValue);

            // Return JSON-formatted results for table rendering in frontend
            if (tables.isEmpty()) {
                return String.format("No tables found in schema '%s'.", schemaName);
            }

            String message = String.format("Found %d tables in schema '%s':", tables.size(), schemaName);
            return JsonResponseFormatter.formatWithMessage(message, tables);

        } catch (Exception e) {
            log.error("❌ Failed to list tables in schema {}", schemaName, e);
            throw new RuntimeException("Failed to list tables: " + e.getMessage(), e);
        } finally {
            sample.stop(schemaQueryTimer);
        }
    }

    /**
     * Get detailed schema for a specific table
     */
    @McpTool(
        name = "gp.getTableSchema",
        description = "Get detailed schema information for a specific table including columns, types, constraints, and indexes."
    )
    public String getTableSchema(
        @McpToolParam(
            description = "Schema name",
            required = true
        ) String schemaName,
        @McpToolParam(
            description = "Table name",
            required = true
        ) String tableName
    ) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            schemaQueryCounter.increment();

            log.debug("Getting table schema for {}.{}", schemaName, tableName);

            // Validate access
            if (!policyService.isTableAllowed(schemaName, tableName)) {
                throw new SecurityException("Access denied to table: " + schemaName + "." + tableName);
            }

            // Get columns with detailed info
            String columnSql = """
                SELECT
                    c.column_name,
                    c.data_type,
                    c.character_maximum_length,
                    c.numeric_precision,
                    c.numeric_scale,
                    c.is_nullable,
                    c.column_default,
                    c.ordinal_position,
                    col_description((quote_ident(c.table_schema)||'.'||quote_ident(c.table_name))::regclass, c.ordinal_position) as column_comment
                FROM information_schema.columns c
                WHERE c.table_schema = ? AND c.table_name = ?
                ORDER BY c.ordinal_position
                """;

            List<Map<String, Object>> columnRows = jdbcTemplate.queryForList(columnSql, schemaName, tableName);
            List<Map<String, Object>> columns = new ArrayList<>();

            for (Map<String, Object> row : columnRows) {
                String columnName = (String) row.get("column_name");

                // Check policy
                if (!policyService.isColumnAllowed(schemaName, tableName, columnName)) {
                    continue;
                }

                Map<String, Object> columnData = new LinkedHashMap<>();
                columnData.put("column", columnName);

                // Build type string with length/precision
                String dataType = (String) row.get("data_type");
                Object maxLength = row.get("character_maximum_length");
                Object precision = row.get("numeric_precision");
                Object scale = row.get("numeric_scale");

                if (maxLength != null) {
                    dataType += "(" + maxLength + ")";
                } else if (precision != null && scale != null) {
                    dataType += "(" + precision + "," + scale + ")";
                } else if (precision != null) {
                    dataType += "(" + precision + ")";
                }

                columnData.put("type", dataType);
                columnData.put("nullable", "YES".equals(row.get("is_nullable")) ? "YES" : "NO");

                if (row.get("column_default") != null) {
                    columnData.put("default", row.get("column_default"));
                }

                if (row.get("column_comment") != null) {
                    columnData.put("comment", row.get("column_comment"));
                }

                columns.add(columnData);
            }

            log.info("✅ Retrieved schema for {}.{} with {} columns", schemaName, tableName, columns.size());

            // Return JSON-formatted results for table rendering in frontend
            if (columns.isEmpty()) {
                return String.format("No columns found in table %s.%s", schemaName, tableName);
            }

            String message = String.format("Schema for table %s.%s (%d columns):", schemaName, tableName, columns.size());
            return JsonResponseFormatter.formatWithMessage(message, columns);

        } catch (Exception e) {
            log.error("❌ Failed to get table schema for {}.{}", schemaName, tableName, e);
            throw new RuntimeException("Failed to get table schema: " + e.getMessage(), e);
        } finally {
            sample.stop(schemaQueryTimer);
        }
    }

    /**
     * Get Greenplum-specific distribution information for a table
     */
    @McpTool(
        name = "gp.getTableDistribution",
        description = "Get Greenplum-specific distribution and partitioning information for a table. Returns JSON table format."
    )
    public String getTableDistribution(
        @McpToolParam(
            description = "Schema name",
            required = true
        ) String schemaName,
        @McpToolParam(
            description = "Table name",
            required = true
        ) String tableName
    ) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            schemaQueryCounter.increment();

            log.debug("Getting distribution info for {}.{}", schemaName, tableName);

            // Validate access
            if (!policyService.isTableAllowed(schemaName, tableName)) {
                throw new SecurityException("Access denied to table: " + schemaName + "." + tableName);
            }

            List<Map<String, Object>> distributionInfo = new ArrayList<>();
            Map<String, Object> distData = new LinkedHashMap<>();
            distData.put("schema", schemaName);
            distData.put("table", tableName);

            // Get distribution policy
            try {
                String distSql = """
                    SELECT
                        CASE
                            WHEN d.policytype = 'p' THEN 'PARTITIONED'
                            WHEN d.policytype = 'r' THEN 'REPLICATED'
                            ELSE 'RANDOM'
                        END as distribution_type,
                        string_agg(a.attname, ', ' ORDER BY array_position(d.distkey, a.attnum)) as distribution_columns
                    FROM gp_distribution_policy d
                    LEFT JOIN pg_attribute a ON a.attrelid = d.localoid AND a.attnum = ANY(d.distkey)
                    WHERE d.localoid = (quote_ident(?) || '.' || quote_ident(?))::regclass
                    GROUP BY d.policytype
                    """;
                Map<String, Object> distInfo = jdbcTemplate.queryForMap(distSql, schemaName, tableName);
                distData.put("distribution_type", distInfo.get("distribution_type"));
                if (distInfo.get("distribution_columns") != null) {
                    distData.put("distribution_columns", distInfo.get("distribution_columns"));
                }
            } catch (Exception e) {
                log.debug("Could not get Greenplum distribution info (may not be a Greenplum database): {}", e.getMessage());
                distData.put("distribution_type", "UNKNOWN");
                distData.put("note", "Not a Greenplum database or distribution info unavailable");
            }

            distributionInfo.add(distData);

            log.info("✅ Retrieved distribution info for {}.{}", schemaName, tableName);

            // Return JSON-formatted results for table rendering in frontend
            String message = String.format("Distribution information for %s.%s:", schemaName, tableName);
            return JsonResponseFormatter.formatWithMessage(message, distributionInfo);

        } catch (Exception e) {
            log.error("❌ Failed to get distribution info for {}.{}", schemaName, tableName, e);
            throw new RuntimeException("Failed to get distribution info: " + e.getMessage(), e);
        } finally {
            sample.stop(schemaQueryTimer);
        }
    }

    // Helper method to quote identifiers
    private String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    // Data classes for results
    @Data
    public static class SchemaListResult {
        private List<SchemaInfo> schemas;
        private int count;
        private int offset;
        private int limit;
        
        public SchemaListResult(List<SchemaInfo> schemas, int count, int offset, int limit) {
            this.schemas = schemas;
            this.count = count;
            this.offset = offset;
            this.limit = limit;
        }
    }

    @Data
    public static class SchemaInfo {
        private String name;
        private Long tableCount;
        private List<TableInfo> tables;
    }

    @Data
    public static class TableInfo {
        private String name;
        private String type;
        private Long columnCount;
        private List<ColumnInfo> columns;
    }

    @Data
    public static class ColumnInfo {
        private String name;
        private String dataType;
        private boolean nullable;
        private String defaultValue;
        private Integer maxLength;
        private Integer precision;
        private Integer scale;
    }

    @Data
    public static class TableListResult {
        private String schemaName;
        private List<TableMetadata> tables;
        private int count;
        private int offset;
        private int limit;

        public TableListResult(String schemaName, List<TableMetadata> tables, int count, int offset, int limit) {
            this.schemaName = schemaName;
            this.tables = tables;
            this.count = count;
            this.offset = offset;
            this.limit = limit;
        }
    }

    @Data
    public static class TableMetadata {
        private String schemaName;
        private String tableName;
        private String tableType;
        private String totalSize;
        private String comment;
        private Long rowCount;
    }

    @Data
    public static class TableSchemaResult {
        private String schemaName;
        private String tableName;
        private List<ColumnDetail> columns;
        private String primaryKey;
        private List<IndexInfo> indexes;
    }

    @Data
    public static class ColumnDetail {
        private String name;
        private String dataType;
        private boolean nullable;
        private String defaultValue;
        private Integer maxLength;
        private Integer precision;
        private Integer scale;
        private int ordinalPosition;
        private String comment;
    }

    @Data
    public static class IndexInfo {
        private String name;
        private String definition;
    }

    @Data
    public static class TableDistributionResult {
        private String schemaName;
        private String tableName;
        private String distributionType;
        private String distributionColumns;
    }
}
