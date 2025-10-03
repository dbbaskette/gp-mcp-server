package com.baskettecase.gpmcp.api;

import com.baskettecase.gpmcp.db.DatabaseConnectionManager;
import com.baskettecase.gpmcp.security.ApiKey;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Metadata Controller
 *
 * Provides REST APIs for client UI to populate dropdowns and discover available resources.
 * These are NOT MCP tools - they are standard HTTP endpoints for the client application.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class MetadataController {

    private final DatabaseConnectionManager connectionManager;
    private final ObjectMapper objectMapper;

    /**
     * Get API key from request context
     */
    private ApiKey getApiKey(HttpServletRequest request) {
        ApiKey apiKey = (ApiKey) request.getAttribute("apiKey");
        if (apiKey == null) {
            throw new IllegalStateException("No API key in request context");
        }
        return apiKey;
    }

    /**
     * Get list of databases available to the current API key
     * Now queries Greenplum directly for user's database privileges
     */
    @GetMapping("/databases")
    public ResponseEntity<DatabaseListResponse> getDatabases(HttpServletRequest request) {
        ApiKey apiKey = getApiKey(request);

        try {
            // Get JDBC template for this user (connects to default database)
            JdbcTemplate jdbcTemplate = connectionManager.getJdbcTemplate(apiKey, null);

            // Query databases user can connect to
            String sql = """
                SELECT d.datname
                FROM pg_database d
                WHERE d.datname NOT IN ('template0', 'template1')
                  AND has_database_privilege(d.datname, 'connect')
                ORDER BY d.datname
                """;

            List<String> databases = jdbcTemplate.queryForList(sql, String.class);

            DatabaseListResponse response = new DatabaseListResponse(
                databases.isEmpty() ? null : databases.get(0),  // First as "default"
                databases,
                null  // No targetHost in response anymore
            );

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Failed to fetch databases", e);
            return ResponseEntity.status(500)
                .body(new DatabaseListResponse(null, null, "Error: " + e.getMessage()));
        }
    }

    /**
     * Get list of schemas in a specific database
     * Now queries Greenplum directly for user's schema privileges
     */
    @GetMapping("/databases/{databaseName}/schemas")
    public ResponseEntity<SchemaListResponse> getSchemas(
        @PathVariable String databaseName,
        HttpServletRequest request
    ) {
        ApiKey apiKey = getApiKey(request);

        try {
            // Get JDBC template for this database
            JdbcTemplate jdbcTemplate = connectionManager.getJdbcTemplate(apiKey, databaseName);

            // Query for schemas user can access
            String sql = """
                SELECT n.nspname AS schema_name
                FROM pg_namespace n
                WHERE n.nspname NOT LIKE 'pg_%'
                  AND n.nspname <> 'information_schema'
                  AND n.nspname NOT LIKE 'gp_%'
                  AND has_schema_privilege(n.oid, 'usage')
                ORDER BY schema_name
                """;

            List<String> schemas = jdbcTemplate.queryForList(sql, String.class);

            return ResponseEntity.ok(new SchemaListResponse(schemas, null));

        } catch (Exception e) {
            log.error("Failed to fetch schemas for database: {}", databaseName, e);
            return ResponseEntity.status(500)
                .body(new SchemaListResponse(null, "Failed to fetch schemas: " + e.getMessage()));
        }
    }


    /**
     * Response for /databases endpoint
     */
    public record DatabaseListResponse(
        String defaultDatabase,
        List<String> allowedDatabases,  // null means all databases allowed
        String targetHost
    ) {}

    /**
     * Response for /databases/{db}/schemas endpoint
     */
    public record SchemaListResponse(
        List<String> schemas,
        String error
    ) {}
}
