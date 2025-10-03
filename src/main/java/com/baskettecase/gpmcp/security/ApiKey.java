package com.baskettecase.gpmcp.security;

import lombok.Data;
import java.time.Instant;

/**
 * API Key Entity
 *
 * Represents an API key for authenticating MCP client requests.
 * Keys follow the format: gpmcp_{env}_{token}
 */
@Data
public class ApiKey {

    private String id;
    private String keyPrefix;      // First 8 chars of token for display (e.g., "a1b2c3d4")
    private String keyHash;         // SHA-256 hash of full key for secure storage
    private String environment;     // "live" or "test"
    private String description;     // User-provided description
    private boolean active;
    private Instant createdAt;
    private Instant lastUsedAt;
    private Instant expiresAt;      // Optional expiration

    // Greenplum user credentials (encrypted)
    // Each API key maps to a specific GP user - GP's RBAC determines access
    private String encryptedUsername;    // Encrypted Greenplum username
    private String encryptedPassword;    // Encrypted Greenplum password

    // Metadata
    private String createdBy;

    /**
     * Get display-friendly key representation
     */
    public String getDisplayKey() {
        return String.format("gpmcp_%s_%s***", environment, keyPrefix);
    }

    /**
     * Check if key is expired
     */
    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }

    /**
     * Check if key is valid for use
     */
    public boolean isValid() {
        return active && !isExpired();
    }
}
