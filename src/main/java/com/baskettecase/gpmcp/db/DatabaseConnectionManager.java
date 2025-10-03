package com.baskettecase.gpmcp.db;

import com.baskettecase.gpmcp.security.ApiKey;
import com.baskettecase.gpmcp.security.ApiKeyService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Database Connection Manager
 *
 * Manages per-API-key connection pools to Greenplum.
 * Each API key has its own connection pool using the GP user credentials from the key.
 * The database host/port is configured in application.yml (shared for all users).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DatabaseConnectionManager {

    private final ApiKeyService apiKeyService;

    // Database connection configuration from application.yml (shared host/port)
    @Value("${spring.datasource.url}")
    private String defaultJdbcUrl;

    @Value("${gp.mcp.search-path:public}")
    private String searchPath;

    @Value("${gp.mcp.statement-timeout-ms:5000}")
    private int statementTimeoutMs;

    @Value("${gp.mcp.idle-tx-timeout-ms:2000}")
    private int idleTxTimeoutMs;

    // Cache of database connections per API key
    private final Map<String, HikariDataSource> dataSources = new ConcurrentHashMap<>();
    private final Map<String, JdbcTemplate> jdbcTemplates = new ConcurrentHashMap<>();

    /**
     * Get JdbcTemplate for a specific database using API key credentials
     *
     * @param apiKey API key containing GP user credentials
     * @param databaseName Database name (null for default from URL)
     * @return JdbcTemplate for the database
     */
    public JdbcTemplate getJdbcTemplate(ApiKey apiKey, String databaseName) {
        // Create connection profile with decrypted credentials
        ConnectionProfile profile = new ConnectionProfile(
            apiKeyService.decryptUsername(apiKey),
            apiKeyService.decryptPassword(apiKey),
            apiKey.getId()
        );

        String connectionKey = profile.getConnectionKey(databaseName);

        return jdbcTemplates.computeIfAbsent(connectionKey, key -> {
            HikariDataSource dataSource = getDataSource(profile, databaseName);
            JdbcTemplate template = new JdbcTemplate(dataSource);
            template.setFetchSize(1000);
            log.info("✅ Created JdbcTemplate for: {} (user: {})", connectionKey, profile.getUsername());
            return template;
        });
    }

    /**
     * Get DataSource for a specific database using connection profile
     */
    private HikariDataSource getDataSource(ConnectionProfile profile, String databaseName) {
        String connectionKey = profile.getConnectionKey(databaseName);

        return dataSources.computeIfAbsent(connectionKey, key -> {
            String jdbcUrl = buildJdbcUrl(databaseName);
            log.info("🔗 Creating connection pool for: {} (user: {})", connectionKey, profile.getUsername());
            return createDataSource(profile, jdbcUrl, connectionKey);
        });
    }

    /**
     * Build JDBC URL for a specific database
     * Uses the host/port from application.yml and optionally switches database
     */
    private String buildJdbcUrl(String databaseName) {
        if (databaseName == null || databaseName.trim().isEmpty()) {
            return defaultJdbcUrl;
        }

        // Replace database name in URL: jdbc:postgresql://host:port/dbname
        int lastSlash = defaultJdbcUrl.lastIndexOf('/');
        if (lastSlash > 0) {
            String baseUrl = defaultJdbcUrl.substring(0, lastSlash);
            return baseUrl + "/" + databaseName;
        }

        return defaultJdbcUrl;
    }

    /**
     * Create a new HikariDataSource with GP user credentials
     */
    private HikariDataSource createDataSource(ConnectionProfile profile, String jdbcUrl, String poolName) {
        HikariConfig config = new HikariConfig();

        // Basic connection settings - USE PROFILE CREDENTIALS (not application.yml)
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(profile.getUsername());
        config.setPassword(profile.getPassword());
        config.setPoolName(poolName);

        // Connection pool settings - smaller pools for per-user connections
        config.setMaximumPoolSize(5);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(30000);
        config.setIdleTimeout(600000);  // 10 minutes idle timeout
        config.setMaxLifetime(1800000);  // 30 minutes max lifetime

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
        config.addDataSourceProperty("prepareThreshold", "0");

        HikariDataSource dataSource = new HikariDataSource(config);

        log.info("📊 Pool '{}': max={}, min={}, idle={}ms",
                poolName, config.getMaximumPoolSize(), config.getMinimumIdle(), config.getIdleTimeout());

        return dataSource;
    }

    /**
     * Builds connection initialization SQL
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
     * Clean up all connection pools on shutdown
     */
    @PreDestroy
    public void cleanup() {
        log.info("🔌 Shutting down {} database connection pools", dataSources.size());
        dataSources.values().forEach(ds -> {
            if (!ds.isClosed()) {
                ds.close();
            }
        });
        dataSources.clear();
        jdbcTemplates.clear();
    }

    /**
     * Get statistics about active connections
     */
    public Map<String, Integer> getConnectionStats() {
        Map<String, Integer> stats = new ConcurrentHashMap<>();
        dataSources.forEach((name, ds) -> {
            if (!ds.isClosed()) {
                stats.put(name, ds.getHikariPoolMXBean().getActiveConnections());
            }
        });
        return stats;
    }

    /**
     * Verify credentials by attempting a connection
     * Used during API key creation to validate GP user credentials
     *
     * @param username Greenplum username
     * @param password Greenplum password
     * @return true if credentials are valid
     * @throws Exception if connection fails
     */
    public boolean verifyCredentials(String username, String password) throws Exception {
        log.info("🔐 Verifying credentials for GP user: {}", username);

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(defaultJdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(1);
        config.setConnectionTimeout(10000);

        try (HikariDataSource testDs = new HikariDataSource(config);
             var conn = testDs.getConnection();
             var stmt = conn.createStatement()) {

            // Query user info to verify it worked
            var rs = stmt.executeQuery("SELECT current_user, session_user");
            if (rs.next()) {
                String currentUser = rs.getString(1);
                log.info("✅ Credentials valid for user: {}", currentUser);
                return true;
            }
            return false;
        } catch (Exception e) {
            log.error("❌ Failed to verify credentials for user {}: {}", username, e.getMessage());
            throw e;
        }
    }
}
