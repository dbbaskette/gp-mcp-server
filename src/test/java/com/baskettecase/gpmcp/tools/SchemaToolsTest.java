package com.baskettecase.gpmcp.tools;

import com.baskettecase.gpmcp.policy.PolicyService;
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
    private PolicyService policyService;

    private SchemaTools schemaTools;

    @BeforeEach
    void setUp() {
        schemaTools = new SchemaTools(jdbcTemplate, policyService, null);
    }

    @Test
    void testListSchemasWithDefaultParameters() {
        // Mock policy service
        when(policyService.getAllowedSchemas()).thenReturn(
            java.util.Set.of("public", "information_schema")
        );

        // Mock database response
        List<Map<String, Object>> mockRows = Arrays.asList(
            Map.of("schema_name", "public", "table_count", 5L),
            Map.of("schema_name", "information_schema", "table_count", 10L)
        );
        when(jdbcTemplate.queryForList(anyString(), any(), any(), any()))
            .thenReturn(mockRows);

        // Execute test
        SchemaTools.SchemaListResult result = schemaTools.listSchemas(null, null, null, null);

        // Verify results
        assertNotNull(result);
        assertEquals(2, result.getCount());
        assertEquals(0, result.getOffset());
        assertEquals(50, result.getLimit());
        assertEquals(2, result.getSchemas().size());
        
        SchemaTools.SchemaInfo publicSchema = result.getSchemas().get(0);
        assertEquals("public", publicSchema.getName());
        assertEquals(5L, publicSchema.getTableCount());
    }

    @Test
    void testListSchemasWithCustomParameters() {
        // Mock policy service
        when(policyService.getAllowedSchemas()).thenReturn(
            java.util.Set.of("public")
        );

        // Mock database response
        List<Map<String, Object>> mockRows = Arrays.asList(
            Map.of("schema_name", "public", "table_count", 3L)
        );
        when(jdbcTemplate.queryForList(anyString(), any(), any(), any()))
            .thenReturn(mockRows);

        // Execute test with custom parameters
        SchemaTools.SchemaListResult result = schemaTools.listSchemas(10, 5, true, false);

        // Verify results
        assertNotNull(result);
        assertEquals(1, result.getCount());
        assertEquals(5, result.getOffset());
        assertEquals(10, result.getLimit());
    }

    @Test
    void testListSchemasWithLargeLimit() {
        // Mock policy service
        when(policyService.getAllowedSchemas()).thenReturn(
            java.util.Set.of("public")
        );

        // Mock database response
        List<Map<String, Object>> mockRows = Arrays.asList(
            Map.of("schema_name", "public", "table_count", 1L)
        );
        when(jdbcTemplate.queryForList(anyString(), any(), any(), any()))
            .thenReturn(mockRows);

        // Execute test with limit exceeding maximum
        SchemaTools.SchemaListResult result = schemaTools.listSchemas(200, null, null, null);

        // Verify limit is capped at 100
        assertEquals(100, result.getLimit());
    }

    @Test
    void testListSchemasWithNegativeOffset() {
        // Mock policy service
        when(policyService.getAllowedSchemas()).thenReturn(
            java.util.Set.of("public")
        );

        // Mock database response
        List<Map<String, Object>> mockRows = Arrays.asList(
            Map.of("schema_name", "public", "table_count", 1L)
        );
        when(jdbcTemplate.queryForList(anyString(), any(), any(), any()))
            .thenReturn(mockRows);

        // Execute test with negative offset
        SchemaTools.SchemaListResult result = schemaTools.listSchemas(null, -5, null, null);

        // Verify offset is set to 0
        assertEquals(0, result.getOffset());
    }
}
