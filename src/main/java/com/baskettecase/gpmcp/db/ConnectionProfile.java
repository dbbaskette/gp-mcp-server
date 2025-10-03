package com.baskettecase.gpmcp.db;

import lombok.Data;

/**
 * Connection profile containing decrypted Greenplum user credentials.
 * Never log or persist this object - credentials are in plaintext!
 *
 * The database host/port comes from application.yml configuration.
 * This profile only carries user-specific credentials.
 */
@Data
public class ConnectionProfile {
    private final String username;  // Greenplum username
    private final String password;  // Greenplum password
    private final String apiKeyId;  // For connection pool naming

    /**
     * Get connection key for caching (apiKeyId + database)
     */
    public String getConnectionKey(String databaseName) {
        return String.format("%s:%s", apiKeyId, databaseName != null ? databaseName : "default");
    }
}
