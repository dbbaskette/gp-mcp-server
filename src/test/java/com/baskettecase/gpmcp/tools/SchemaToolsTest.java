package com.baskettecase.gpmcp.tools;

import com.baskettecase.gpmcp.db.DatabaseConnectionManager;
import com.baskettecase.gpmcp.policy.PolicyService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Unit tests for SchemaTools
 */
@ExtendWith(MockitoExtension.class)
class SchemaToolsTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private DatabaseConnectionManager connectionManager;

    @Mock
    private PolicyService policyService;

    private MeterRegistry meterRegistry;
    private SchemaTools schemaTools;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();

        // Mock the connection manager to return the mocked JdbcTemplate
        when(connectionManager.getJdbcTemplate(any(), any())).thenReturn(jdbcTemplate);

        schemaTools = new SchemaTools(connectionManager, policyService, meterRegistry);
        schemaTools.initializeMetrics(); // Initialize counters and timers
    }

    @Test
    void testListSchemasWithDefaultParameters() {
        // Mock policy service
        when(policyService.getAllowedSchemas()).thenReturn(
            java.util.Set.of("public", "information_schema")
        );

        // Mock database response
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> mockRows = (List<Map<String, Object>>) (List<?>) Arrays.asList(
            Map.of("schema_name", "public", "table_count", 5L),
            Map.of("schema_name", "information_schema", "table_count", 10L)
        );
        when(jdbcTemplate.queryForList(anyString(), any(), any(), any()))
            .thenReturn((List) mockRows);

        // Execute test (databaseName, limit, offset)
        String result = schemaTools.listSchemas(null, null, null);

        // Verify results
        assertNotNull(result);
        assertTrue(result.contains("Found 2 database schemas"));
        assertTrue(result.contains("```json"));
        assertTrue(result.contains("public"));
        assertTrue(result.contains("information_schema"));
    }

    @Test
    void testListSchemasWithCustomParameters() {
        // Mock policy service
        when(policyService.getAllowedSchemas()).thenReturn(
            java.util.Set.of("public")
        );

        // Mock database response
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> mockRows = (List<Map<String, Object>>) (List<?>) Arrays.asList(
            Map.of("schema_name", "public", "table_count", 3L)
        );
        when(jdbcTemplate.queryForList(anyString(), any(), any(), any()))
            .thenReturn((List) mockRows);

        // Execute test with custom parameters (databaseName, limit, offset)
        String result = schemaTools.listSchemas(null, 10, 5);

        // Verify results
        assertNotNull(result);
        assertTrue(result.contains("Found 1 database schemas"));
        assertTrue(result.contains("```json"));
    }

    @Test
    void testListSchemasWithLargeLimit() {
        // Mock policy service
        when(policyService.getAllowedSchemas()).thenReturn(
            java.util.Set.of("public")
        );

        // Mock database response
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> mockRows = (List<Map<String, Object>>) (List<?>) Arrays.asList(
            Map.of("schema_name", "public", "table_count", 1L)
        );
        when(jdbcTemplate.queryForList(anyString(), any(), any(), any()))
            .thenReturn((List) mockRows);

        // Execute test with limit exceeding maximum (databaseName, limit, offset)
        String result = schemaTools.listSchemas(null, 200, null);

        // Verify result contains schema
        assertNotNull(result);
        assertTrue(result.contains("Found 1 database schemas"));
    }

    @Test
    void testListSchemasWithNegativeOffset() {
        // Mock policy service
        when(policyService.getAllowedSchemas()).thenReturn(
            java.util.Set.of("public")
        );

        // Mock database response
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> mockRows = (List<Map<String, Object>>) (List<?>) Arrays.asList(
            Map.of("schema_name", "public", "table_count", 1L)
        );
        when(jdbcTemplate.queryForList(anyString(), any(), any(), any()))
            .thenReturn((List) mockRows);

        // Execute test with negative offset (databaseName, limit, offset)
        String result = schemaTools.listSchemas(null, null, -5);

        // Verify result contains schema
        assertNotNull(result);
        assertTrue(result.contains("Found 1 database schemas"));
    }
}
