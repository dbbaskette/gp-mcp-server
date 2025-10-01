package com.baskettecase.gpmcp.tools;

import com.baskettecase.gpmcp.policy.PolicyService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.Tool;
import org.springframework.ai.tool.ToolParameter;
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
    @Tool(
        name = "gp.listSchemas",
        description = "List available database schemas with tables and columns. Supports pagination for large schemas."
    )
    public SchemaListResult listSchemas(
        @ToolParameter(
            name = "limit",
            description = "Maximum number of schemas to return (default: 50)",
            required = false
        ) Integer limit,
        @ToolParameter(
            name = "offset",
            description = "Number of schemas to skip (default: 0)",
            required = false
        ) Integer offset,
        @ToolParameter(
            name = "includeTables",
            description = "Whether to include table information (default: true)",
            required = false
        ) Boolean includeTables,
        @ToolParameter(
            name = "includeColumns",
            description = "Whether to include column information (default: false)",
            required = false
        ) Boolean includeColumns
    ) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            schemaQueryCounter.increment();
            
            int limitValue = limit != null ? Math.min(limit, 100) : 50;
            int offsetValue = offset != null ? Math.max(offset, 0) : 0;
            boolean includeTablesValue = includeTables != null ? includeTables : true;
            boolean includeColumnsValue = includeColumns != null ? includeColumns : false;

            log.debug("Listing schemas: limit={}, offset={}, includeTables={}, includeColumns={}", 
                    limitValue, offsetValue, includeTablesValue, includeColumnsValue);

            List<SchemaInfo> schemas = new ArrayList<>();
            
            // Get allowed schemas from policy
            Set<String> allowedSchemas = policyService.getAllowedSchemas();
            
            // Query database for schema information
            String sql = """
                SELECT schema_name, 
                       COUNT(table_name) as table_count
                FROM information_schema.schemata s
                LEFT JOIN information_schema.tables t ON s.schema_name = t.table_schema
                WHERE schema_name = ANY(?)
                GROUP BY schema_name
                ORDER BY schema_name
                LIMIT ? OFFSET ?
                """;
            
            List<Map<String, Object>> schemaRows = jdbcTemplate.queryForList(sql, 
                    allowedSchemas.toArray(), limitValue, offsetValue);
            
            for (Map<String, Object> row : schemaRows) {
                String schemaName = (String) row.get("schema_name");
                Long tableCount = ((Number) row.get("table_count")).longValue();
                
                SchemaInfo schemaInfo = new SchemaInfo();
                schemaInfo.setName(schemaName);
                schemaInfo.setTableCount(tableCount);
                
                if (includeTablesValue) {
                    schemaInfo.setTables(getTablesForSchema(schemaName, includeColumnsValue));
                }
                
                schemas.add(schemaInfo);
            }
            
            log.info("✅ Listed {} schemas (limit={}, offset={})", schemas.size(), limitValue, offsetValue);
            
            return new SchemaListResult(schemas, schemas.size(), offsetValue, limitValue);
            
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
}
