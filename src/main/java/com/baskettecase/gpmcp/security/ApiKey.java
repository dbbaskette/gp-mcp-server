package com.baskettecase.gpmcp.security;

import lombok.Data;
import java.time.Instant;

/**
 * API Key Entity
 *
 * Represents an API key for authenticating MCP client requests.
 * Keys follow the Spring AI MCP format: {id}.{secret}
 *
 * Format: gpmcp_{env}_{randomId}.{secret}
 * Example: gpmcp_live_a1b2c3d4.xyz789...
 */
@Data
public class ApiKey {

    // Public identifier (used for lookup)
    // Format: gpmcp_{env}_{randomId}
    // Example: "gpmcp_live_a1b2c3d4"
    private String id;

    // SHA-256 hash of the secret part (never store plaintext secret)
    private String secretHash;

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
     * Shows ID + masked secret
     */
    public String getDisplayKey() {
        return id + ".***";
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
