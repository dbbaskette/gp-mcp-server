package com.baskettecase.gpmcp.db;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Database Initializer
 *
 * Creates the gp_assistant database if it doesn't exist.
 * This database stores API keys and other MCP server metadata.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class DatabaseInitializer {

    @Value("${spring.datasource.url}")
    private String jdbcUrl;

    @Value("${spring.datasource.username}")
    private String username;

    @Value("${spring.datasource.password}")
    private String password;

    private final DataSource dataSource;

    @Bean
    public CommandLineRunner initializeGpAssistantDatabase() {
        return args -> {
            log.info("🔧 Checking for gp_assistant database...");

            try (Connection conn = dataSource.getConnection()) {
                // Temporarily disable read-only for DDL operations
                conn.setReadOnly(false);

                try (Statement stmt = conn.createStatement()) {
                    // Check if database exists
                    String checkDbSql = "SELECT 1 FROM pg_database WHERE datname = 'gp_assistant'";
                    boolean exists = stmt.executeQuery(checkDbSql).next();

                    if (!exists) {
                        log.info("📦 Creating gp_assistant database...");
                        // Create database - must be done outside transaction
                        conn.setAutoCommit(true);
                        stmt.execute("CREATE DATABASE gp_assistant");
                        log.info("✅ Created gp_assistant database");
                    } else {
                        log.info("✅ gp_assistant database already exists");
                    }
                }
            } catch (SQLException e) {
                // If we can't create the database, it might already exist or we don't have permissions
                log.warn("Could not create gp_assistant database: {}. This is okay if it already exists.",
                    e.getMessage());
            }
        };
    }
}
