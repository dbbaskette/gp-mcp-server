package com.baskettecase.gpmcp.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for API Key persistence
 * Uses the gp_assistant database for storage
 */
@Slf4j
@Repository
public class ApiKeyRepository {

    private final JdbcTemplate jdbcTemplate;

    public ApiKeyRepository(@Qualifier("gpAssistantJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<ApiKey> ROW_MAPPER = new RowMapper<ApiKey>() {
        @Override
        public ApiKey mapRow(ResultSet rs, int rowNum) throws SQLException {
            ApiKey key = new ApiKey();
            key.setId(rs.getString("id"));
            key.setSecretHash(rs.getString("secret_hash"));
            key.setEnvironment(rs.getString("environment"));
            key.setDescription(rs.getString("description"));
            key.setActive(rs.getBoolean("active"));
            key.setCreatedBy(rs.getString("created_by"));

            // Greenplum user credentials (encrypted)
            key.setEncryptedUsername(rs.getString("encrypted_username"));
            key.setEncryptedPassword(rs.getString("encrypted_password"));

            Timestamp createdAt = rs.getTimestamp("created_at");
            if (createdAt != null) {
                key.setCreatedAt(createdAt.toInstant());
            }

            Timestamp lastUsedAt = rs.getTimestamp("last_used_at");
            if (lastUsedAt != null) {
                key.setLastUsedAt(lastUsedAt.toInstant());
            }

            Timestamp expiresAt = rs.getTimestamp("expires_at");
            if (expiresAt != null) {
                key.setExpiresAt(expiresAt.toInstant());
            }

            return key;
        }
    };

    /**
     * Save a new API key
     */
    public void save(ApiKey apiKey) {
        if (apiKey.getId() == null) {
            apiKey.setId(UUID.randomUUID().toString());
        }
        if (apiKey.getCreatedAt() == null) {
            apiKey.setCreatedAt(Instant.now());
        }

        String sql = """
            INSERT INTO api_keys (
                id, secret_hash, environment, description,
                active, created_by,
                encrypted_username, encrypted_password,
                created_at, last_used_at, expires_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        jdbcTemplate.update(sql,
            apiKey.getId(),
            apiKey.getSecretHash(),
            apiKey.getEnvironment(),
            apiKey.getDescription(),
            apiKey.isActive(),
            apiKey.getCreatedBy(),
            apiKey.getEncryptedUsername(),
            apiKey.getEncryptedPassword(),
            Timestamp.from(apiKey.getCreatedAt()),
            apiKey.getLastUsedAt() != null ? Timestamp.from(apiKey.getLastUsedAt()) : null,
            apiKey.getExpiresAt() != null ? Timestamp.from(apiKey.getExpiresAt()) : null
        );

        log.info("✅ Saved API key: {}", apiKey.getDisplayKey());
    }

    /**
     * Find API key by ID (the public part before the dot)
     * Example: For "gpmcp_live_a1b2c3d4.secret", this finds by "gpmcp_live_a1b2c3d4"
     */
    public Optional<ApiKey> findById(String id) {
        String sql = """
            SELECT * FROM api_keys
            WHERE id = ? AND active = true
            """;

        List<ApiKey> results = jdbcTemplate.query(sql, ROW_MAPPER, id);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Find all API keys
     */
    public List<ApiKey> findAll() {
        String sql = "SELECT * FROM api_keys ORDER BY created_at DESC";
        return jdbcTemplate.query(sql, ROW_MAPPER);
    }

    /**
     * Find API keys by environment
     */
    public List<ApiKey> findByEnvironment(String environment) {
        String sql = "SELECT * FROM api_keys WHERE environment = ? ORDER BY created_at DESC";
        return jdbcTemplate.query(sql, ROW_MAPPER, environment);
    }

    /**
     * Update last used timestamp
     */
    public void updateLastUsed(String id) {
        String sql = "UPDATE api_keys SET last_used_at = ? WHERE id = ?";
        jdbcTemplate.update(sql, Timestamp.from(Instant.now()), id);
    }

    /**
     * Revoke (deactivate) an API key
     */
    public void revoke(String id) {
        String sql = "UPDATE api_keys SET active = false WHERE id = ?";
        int updated = jdbcTemplate.update(sql, id);
        if (updated > 0) {
            log.info("✅ Revoked API key: {}", id);
        }
    }

    /**
     * Delete an API key
     */
    public void delete(String id) {
        String sql = "DELETE FROM api_keys WHERE id = ?";
        int deleted = jdbcTemplate.update(sql, id);
        if (deleted > 0) {
            log.info("✅ Deleted API key: {}", id);
        }
    }

    /**
     * Initialize the api_keys table if it doesn't exist
     * Handles schema migration from old format to new simplified format
     */
    public void initializeTable() {
        try {
            // Check if table exists and has old schema
            boolean tableExists = checkTableExists();
            boolean hasOldSchema = tableExists && checkOldSchemaExists();

            if (hasOldSchema) {
                log.info("🔄 Migrating api_keys table to new schema...");
                migrateSchema();
            } else if (!tableExists) {
                log.info("📦 Creating api_keys table with new schema...");
                createNewTable();
            } else {
                log.info("✅ API keys table already exists with current schema");
            }

            // Ensure index exists
            ensureIndex();

            log.info("✅ API keys table initialized");
        } catch (Exception e) {
            log.error("❌ Failed to initialize api_keys table", e);
            throw e;
        }
    }

    private boolean checkTableExists() {
        String sql = "SELECT COUNT(*) FROM pg_tables WHERE tablename = 'api_keys'";
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class);
        return count != null && count > 0;
    }

    private boolean checkOldSchemaExists() {
        try {
            String sql = """
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_name = 'api_keys'
                AND column_name IN ('target_host', 'default_database', 'allowed_databases', 'allowed_schemas', 'key_prefix', 'key_hash')
                """;
            Integer count = jdbcTemplate.queryForObject(sql, Integer.class);
            return count != null && count > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void migrateSchema() {
        // Drop old columns that are no longer needed
        String[] columnsToDrop = {
            "target_host",
            "default_database",
            "allowed_databases",
            "allowed_schemas",
            "key_prefix",
            "key_hash"
        };

        for (String column : columnsToDrop) {
            try {
                String sql = "ALTER TABLE api_keys DROP COLUMN IF EXISTS " + column;
                jdbcTemplate.execute(sql);
                log.info("  ✅ Dropped column: {}", column);
            } catch (Exception e) {
                log.warn("  ⚠️ Could not drop column {}: {}", column, e.getMessage());
            }
        }

        // Add new secret_hash column if it doesn't exist
        try {
            String checkColumnSql = """
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_name = 'api_keys' AND column_name = 'secret_hash'
                """;
            Integer columnExists = jdbcTemplate.queryForObject(checkColumnSql, Integer.class);

            if (columnExists == null || columnExists == 0) {
                String addColumnSql = "ALTER TABLE api_keys ADD COLUMN secret_hash VARCHAR(64)";
                jdbcTemplate.execute(addColumnSql);
                log.info("  ✅ Added column: secret_hash");
            }
        } catch (Exception e) {
            log.warn("  ⚠️ Could not add column secret_hash: {}", e.getMessage());
        }

        log.info("✅ Schema migration completed");
    }

    private void createNewTable() {
        String createTableSql = """
            CREATE TABLE api_keys (
                id VARCHAR(50) PRIMARY KEY,
                secret_hash VARCHAR(64) NOT NULL,
                environment VARCHAR(10) NOT NULL,
                description TEXT,
                active BOOLEAN DEFAULT true,
                created_by VARCHAR(255),
                encrypted_username TEXT NOT NULL,
                encrypted_password TEXT NOT NULL,
                created_at TIMESTAMP NOT NULL,
                last_used_at TIMESTAMP,
                expires_at TIMESTAMP
            )
            """;
        jdbcTemplate.execute(createTableSql);
        log.info("✅ Created api_keys table");
    }

    private void ensureIndex() {
        // Drop old index if it exists
        try {
            String dropIndexSql = "DROP INDEX IF EXISTS api_keys_key_hash_idx";
            jdbcTemplate.execute(dropIndexSql);
            log.debug("Dropped old index api_keys_key_hash_idx if it existed");
        } catch (Exception e) {
            log.debug("Could not drop old index: {}", e.getMessage());
        }

        // Create new index on secret_hash
        String checkIndexSql = """
            SELECT COUNT(*) FROM pg_indexes
            WHERE indexname = 'api_keys_secret_hash_idx'
            """;

        Integer indexCount = jdbcTemplate.queryForObject(checkIndexSql, Integer.class);

        if (indexCount == null || indexCount == 0) {
            // Greenplum requires UNIQUE index to include distribution key column (id)
            String createIndexSql = """
                CREATE UNIQUE INDEX api_keys_secret_hash_idx
                ON api_keys(secret_hash, id)
                """;
            jdbcTemplate.execute(createIndexSql);
            log.info("✅ Created unique index on api_keys(secret_hash, id)");
        } else {
            log.debug("Index api_keys_secret_hash_idx already exists");
        }
    }
}
