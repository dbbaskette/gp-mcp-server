package com.baskettecase.gpmcp.sql;

import com.baskettecase.gpmcp.policy.PolicyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Unit tests for SqlValidator
 */
@ExtendWith(MockitoExtension.class)
class SqlValidatorTest {

    @Mock
    private PolicyService policyService;

    private SqlValidator sqlValidator;

    @BeforeEach
    void setUp() {
        sqlValidator = new SqlValidator(policyService);
        
        // Mock policy service responses
        when(policyService.isSchemaAllowed("public")).thenReturn(true);
        when(policyService.isSchemaAllowed("private")).thenReturn(false);
        when(policyService.isTableAllowed("public", "users")).thenReturn(true);
        when(policyService.isTableAllowed("public", "secrets")).thenReturn(false);
        when(policyService.isColumnAllowed("public", "users", "name")).thenReturn(true);
        when(policyService.isColumnAllowed("public", "users", "password")).thenReturn(false);
    }

    @Test
    void testValidSelectQuery() {
        String sql = "SELECT name, email FROM public.users WHERE id = :id";
        
        SqlValidator.ValidationResult result = sqlValidator.validate(sql);
        
        assertTrue(result.isValid());
        assertTrue(result.errors().isEmpty());
    }

    @Test
    void testInvalidUpdateQuery() {
        String sql = "UPDATE users SET name = 'test' WHERE id = 1";
        
        SqlValidator.ValidationResult result = sqlValidator.validate(sql);
        
        assertFalse(result.isValid());
        assertTrue(result.errors().contains("Only SELECT statements are allowed"));
    }

    @Test
    void testMultiStatementQuery() {
        String sql = "SELECT * FROM users; SELECT * FROM orders;";
        
        SqlValidator.ValidationResult result = sqlValidator.validate(sql);
        
        assertFalse(result.isValid());
        assertTrue(result.errors().contains("Multi-statement queries are not allowed"));
    }

    @Test
    void testDangerousFunction() {
        String sql = "SELECT pg_read_file('/etc/passwd') FROM users";
        
        SqlValidator.ValidationResult result = sqlValidator.validate(sql);
        
        assertFalse(result.isValid());
        assertTrue(result.errors().contains("Dangerous function/statement detected: pg_read_file"));
    }

    @Test
    void testSelectStarWarning() {
        String sql = "SELECT * FROM public.users";
        
        SqlValidator.ValidationResult result = sqlValidator.validate(sql);
        
        assertTrue(result.isValid());
        assertTrue(result.warnings().contains("SELECT * queries may expose sensitive columns"));
    }

    @Test
    void testInvalidSqlSyntax() {
        String sql = "SELECT * FROM users WHERE invalid syntax";
        
        SqlValidator.ValidationResult result = sqlValidator.validate(sql);
        
        assertFalse(result.isValid());
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("Invalid SQL syntax")));
    }

    @Test
    void testComplexSelectStatement() {
        String sql = "SELECT u.name, u.email FROM public.users u JOIN public.orders o ON u.id = o.user_id";
        
        SqlValidator.ValidationResult result = sqlValidator.validate(sql);
        
        assertTrue(result.isValid());
        assertTrue(result.errors().isEmpty());
    }

    @Test
    void testInsertStatement() {
        String sql = "INSERT INTO users (name, email) VALUES ('test', 'test@example.com')";
        
        SqlValidator.ValidationResult result = sqlValidator.validate(sql);
        
        assertFalse(result.isValid());
        assertTrue(result.errors().contains("Only SELECT statements are allowed"));
    }

    @Test
    void testDeleteStatement() {
        String sql = "DELETE FROM users WHERE id = 1";
        
        SqlValidator.ValidationResult result = sqlValidator.validate(sql);
        
        assertFalse(result.isValid());
        assertTrue(result.errors().contains("Only SELECT statements are allowed"));
    }

    @Test
    void testCreateStatement() {
        String sql = "CREATE TABLE test (id INT)";
        
        SqlValidator.ValidationResult result = sqlValidator.validate(sql);
        
        assertFalse(result.isValid());
        assertTrue(result.errors().contains("Only SELECT statements are allowed"));
    }
}
