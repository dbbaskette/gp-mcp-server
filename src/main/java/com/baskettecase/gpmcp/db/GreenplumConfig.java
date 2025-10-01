package com.baskettecase.gpmcp.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;

/**
 * Greenplum Database Configuration
 * 
 * Configures HikariCP connection pool with security settings and session parameters.
 * Enforces read-only access patterns and connection timeouts.
 * 
 * @see <a href="https://greenplum.org/docs/">Greenplum Documentation</a>
 */
@Slf4j
@Configuration
public class GreenplumConfig {

    @Value("${spring.datasource.url}")
    private String jdbcUrl;

    @Value("${spring.datasource.username}")
    private String username;

    @Value("${spring.datasource.password}")
    private String password;

    @Value("${gp.mcp.search-path:public}")
    private String searchPath;

    @Value("${gp.mcp.statement-timeout-ms:5000}")
    private int statementTimeoutMs;

    @Value("${gp.mcp.idle-tx-timeout-ms:2000}")
    private int idleTxTimeoutMs;

    @Bean
    public DataSource dataSource() {
        HikariConfig config = new HikariConfig();
        
        // Basic connection settings
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        
        // Connection pool settings
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(30000);
        config.setIdleTimeout(300000);
        config.setMaxLifetime(1800000);
        
        // Connection validation
        config.setConnectionTestQuery("SELECT 1");
        config.setValidationTimeout(5000);
        
        // Security settings
        config.setConnectionInitSql(buildConnectionInitSql());
        
        // Application identification
        config.addDataSourceProperty("application_name", "gp-mcp-server");
        config.addDataSourceProperty("read_only", "true");
        
        // Performance settings
        config.addDataSourceProperty("defaultRowFetchSize", "1000");
        config.addDataSourceProperty("prepareThreshold", "0"); // Always use prepared statements
        
        HikariDataSource dataSource = new HikariDataSource(config);
        
        log.info("🔗 Configured HikariCP connection pool for Greenplum");
        log.info("📊 Pool settings: max={}, min={}, timeout={}ms", 
                config.getMaximumPoolSize(), config.getMinimumIdle(), config.getConnectionTimeout());
        
        return dataSource;
    }

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        JdbcTemplate template = new JdbcTemplate(dataSource);
        template.setFetchSize(1000); // Optimize for streaming results
        return template;
    }

    /**
     * Builds connection initialization SQL to enforce security policies
     */
    private String buildConnectionInitSql() {
        return String.format(
            "SET search_path = %s; " +
            "SET statement_timeout = %d; " +
            "SET idle_in_transaction_session_timeout = %d; " +
            "SET application_name = 'gp-mcp-server'; " +
            "SET default_transaction_read_only = true;",
            searchPath, statementTimeoutMs, idleTxTimeoutMs
        );
    }

    /**
     * Test database connectivity and log connection info
     */
    @Bean
    public DatabaseInfo databaseInfo(DataSource dataSource) {
        try (Connection conn = dataSource.getConnection()) {
            String version = conn.getMetaData().getDatabaseProductVersion();
            String product = conn.getMetaData().getDatabaseProductName();
            
            log.info("✅ Connected to {} version {}", product, version);
            
            return new DatabaseInfo(product, version, conn.getMetaData().getURL());
        } catch (SQLException e) {
            log.error("❌ Failed to connect to database", e);
            throw new RuntimeException("Database connection failed", e);
        }
    }

    /**
     * Database connection information
     */
    public record DatabaseInfo(String productName, String version, String url) {}
}
