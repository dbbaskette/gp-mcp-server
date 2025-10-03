package com.baskettecase.gpmcp.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Configuration for gp_assistant database connection.
 *
 * This database stores API keys and other MCP server metadata.
 * Unlike the main datasource, this connection is writable.
 */
@Slf4j
@Configuration
public class GpAssistantDataSourceConfig {

    @Value("${spring.datasource.url}")
    private String defaultJdbcUrl;

    @Value("${spring.datasource.username}")
    private String username;

    @Value("${spring.datasource.password}")
    private String password;

    /**
     * Create a writable DataSource for gp_assistant database
     */
    @Bean(name = "gpAssistantDataSource")
    public DataSource gpAssistantDataSource() {
        // First, ensure gp_assistant database exists
        ensureGpAssistantDatabaseExists();

        // Build JDBC URL for gp_assistant database
        String gpAssistantUrl = buildGpAssistantUrl();

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(gpAssistantUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setPoolName("gp-assistant-pool");

        // Small pool for metadata operations
        config.setMaximumPoolSize(3);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(10000);
        config.setValidationTimeout(5000);

        // IMPORTANT: This connection must be writable for DDL/DML operations
        config.setReadOnly(false);

        // Application identification
        config.addDataSourceProperty("application_name", "gp-mcp-server-admin");

        HikariDataSource dataSource = new HikariDataSource(config);
        log.info("✅ Created writable connection pool for gp_assistant database");

        return dataSource;
    }

    /**
     * JdbcTemplate for gp_assistant database operations
     */
    @Bean(name = "gpAssistantJdbcTemplate")
    public JdbcTemplate gpAssistantJdbcTemplate(DataSource gpAssistantDataSource) {
        return new JdbcTemplate(gpAssistantDataSource);
    }

    /**
     * Ensure gp_assistant database exists before creating the DataSource
     */
    private void ensureGpAssistantDatabaseExists() {
        log.info("🔧 Checking for gp_assistant database...");

        HikariConfig tempConfig = new HikariConfig();
        tempConfig.setJdbcUrl(defaultJdbcUrl);
        tempConfig.setUsername(username);
        tempConfig.setPassword(password);
        tempConfig.setMaximumPoolSize(1);
        tempConfig.setConnectionTimeout(5000);

        try (HikariDataSource tempDs = new HikariDataSource(tempConfig);
             var conn = tempDs.getConnection();
             var stmt = conn.createStatement()) {

            // Check if database exists
            var rs = stmt.executeQuery("SELECT 1 FROM pg_database WHERE datname = 'gp_assistant'");
            boolean exists = rs.next();

            if (!exists) {
                log.info("📦 Creating gp_assistant database...");
                stmt.execute("CREATE DATABASE gp_assistant");
                log.info("✅ Created gp_assistant database");
            } else {
                log.info("✅ gp_assistant database already exists");
            }
        } catch (Exception e) {
            log.warn("⚠️ Could not create gp_assistant database: {}. This is okay if it already exists.", e.getMessage());
        }
    }

    /**
     * Build JDBC URL for gp_assistant database
     */
    private String buildGpAssistantUrl() {
        // Parse the default URL to extract host and port
        // Format: jdbc:postgresql://host:port/database
        String baseUrl = defaultJdbcUrl;
        int lastSlash = baseUrl.lastIndexOf('/');
        if (lastSlash > 0) {
            String baseWithoutDb = baseUrl.substring(0, lastSlash);
            return baseWithoutDb + "/gp_assistant";
        }

        // Fallback: replace database name
        return defaultJdbcUrl.replaceFirst("/[^/]+$", "/gp_assistant");
    }
}
