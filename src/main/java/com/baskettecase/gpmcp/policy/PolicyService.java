package com.baskettecase.gpmcp.policy;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Policy Service for Security Enforcement
 * 
 * Loads and enforces security policies from YAML configuration.
 * Validates queries against allowed schemas, tables, and columns.
 * Applies redaction rules and resource limits.
 */
@Slf4j
@Service
public class PolicyService {

    @Value("${gp.mcp.policy-path:classpath:policy.yml}")
    private Resource policyResource;

    private PolicyConfig policy;

    @PostConstruct
    public void loadPolicy() {
        try {
            // For now, create a default policy - in production, load from YAML
            this.policy = createDefaultPolicy();
            log.info("📋 Loaded security policy: {} schemas, {} tables allowed", 
                    policy.getAllowedSchemas().size(), 
                    policy.getAllowedTables().size());
        } catch (Exception e) {
            log.error("❌ Failed to load policy configuration", e);
            throw new RuntimeException("Policy configuration failed", e);
        }
    }

    /**
     * Get all allowed schemas
     */
    public Set<String> getAllowedSchemas() {
        return policy.getAllowedSchemas();
    }

    /**
     * Validate if a schema is allowed
     */
    public boolean isSchemaAllowed(String schemaName) {
        return policy.getAllowedSchemas().contains(schemaName.toLowerCase());
    }

    /**
     * Validate if a table is allowed
     */
    public boolean isTableAllowed(String schemaName, String tableName) {
        String key = schemaName.toLowerCase() + "." + tableName.toLowerCase();
        return policy.getAllowedTables().contains(key);
    }

    /**
     * Validate if a column is allowed
     */
    public boolean isColumnAllowed(String schemaName, String tableName, String columnName) {
        String key = schemaName.toLowerCase() + "." + tableName.toLowerCase() + "." + columnName.toLowerCase();
        return policy.getAllowedColumns().contains(key);
    }

    /**
     * Get redaction rule for a column
     */
    public RedactionRule getRedactionRule(String schemaName, String tableName, String columnName) {
        String key = schemaName.toLowerCase() + "." + tableName.toLowerCase() + "." + columnName.toLowerCase();
        return policy.getRedactionRules().get(key);
    }

    /**
     * Get maximum rows allowed
     */
    public int getMaxRows() {
        return policy.getMaxRows();
    }

    /**
     * Get maximum bytes allowed
     */
    public long getMaxBytes() {
        return policy.getMaxBytesMB() * 1024 * 1024;
    }

    /**
     * Get statement timeout in milliseconds
     */
    public int getStatementTimeoutMs() {
        return policy.getStatementTimeoutMs();
    }

    /**
     * Create default policy configuration
     */
    private PolicyConfig createDefaultPolicy() {
        PolicyConfig config = new PolicyConfig();
        
        // Allow common schemas
        config.setAllowedSchemas(Set.of("public", "information_schema", "pg_catalog"));
        
        // Allow all tables in public schema (can be restricted later)
        config.setAllowedTables(Set.of("public.*"));
        
        // Allow all columns (can be restricted later)
        config.setAllowedColumns(Set.of("*"));
        
        // Set limits
        config.setMaxRows(10000);
        config.setMaxBytesMB(100);
        config.setStatementTimeoutMs(5000);
        
        // Redaction rules
        Map<String, RedactionRule> redactionRules = new HashMap<>();
        redactionRules.put("public.users.email", new RedactionRule("MASK", "***@***.***"));
        redactionRules.put("public.users.phone", new RedactionRule("MASK", "***-***-****"));
        config.setRedactionRules(redactionRules);
        
        return config;
    }

    /**
     * Policy configuration
     */
    @Data
    @Configuration
    @ConfigurationProperties(prefix = "gp.mcp.policy")
    public static class PolicyConfig {
        private Set<String> allowedSchemas = new HashSet<>();
        private Set<String> allowedTables = new HashSet<>();
        private Set<String> allowedColumns = new HashSet<>();
        private Map<String, RedactionRule> redactionRules = new HashMap<>();
        private int maxRows = 10000;
        private int maxBytesMB = 100;
        private int statementTimeoutMs = 5000;
    }

    /**
     * Redaction rule for sensitive data
     */
    @Data
    public static class RedactionRule {
        private String type; // MASK, DROP, HASH
        private String replacement;

        public RedactionRule() {}

        public RedactionRule(String type, String replacement) {
            this.type = type;
            this.replacement = replacement;
        }
    }
}
