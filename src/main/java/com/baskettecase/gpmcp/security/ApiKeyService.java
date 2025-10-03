package com.baskettecase.gpmcp.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * API Key Service
 *
 * Handles generation, validation, and management of API keys.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApiKeyService {

    private final ApiKeyRepository repository;
    private final CredentialEncryptionService encryptionService;
    private final SecureRandom secureRandom = new SecureRandom();

    @PostConstruct
    public void init() {
        // Initialize the api_keys table
        try {
            repository.initializeTable();
            log.info("✅ API key service initialized");

            List<ApiKey> existingKeys = repository.findAll();
            if (existingKeys.isEmpty()) {
                log.info("╔══════════════════════════════════════════════════════════════╗");
                log.info("║  🔐 NO API KEYS CONFIGURED                                   ║");
                log.info("║                                                              ║");
                log.info("║  Generate your first API key using the web UI:              ║");
                log.info("║  http://localhost:8082/admin/api-keys                       ║");
                log.info("║                                                              ║");
                log.info("║  Or use the direct generation endpoint (for automation):    ║");
                log.info("║  POST http://localhost:8082/admin/api-keys/generate         ║");
                log.info("╚══════════════════════════════════════════════════════════════╝");
            } else {
                log.info("Found {} existing API key(s)", existingKeys.size());
            }

        } catch (Exception e) {
            log.warn("Could not initialize api_keys table: {}", e.getMessage());
        }
    }

    /**
     * Generate a new API key with Greenplum user credentials
     *
     * Format: {id}.{secret}
     * Example: gpmcp_live_a1b2c3d4.xyz789abc...
     *
     * @param environment "live" or "test"
     * @param description User-provided description
     * @param createdBy Username or identifier of creator
     * @param username Greenplum database username
     * @param password Greenplum database password
     * @param expiresInDays Optional expiration in days (null = no expiration)
     * @return The complete API key (only shown once!)
     */
    public ApiKeyResult generateKey(
        String environment,
        String description,
        String createdBy,
        String username,
        String password,
        Integer expiresInDays
    ) {
        // Validate environment
        if (!Arrays.asList("live", "test").contains(environment)) {
            throw new IllegalArgumentException("Environment must be 'live' or 'test'");
        }

        // Validate required credentials
        if (username == null || username.trim().isEmpty()) {
            throw new IllegalArgumentException("Greenplum username is required");
        }
        if (password == null || password.trim().isEmpty()) {
            throw new IllegalArgumentException("Greenplum password is required");
        }

        // TODO: Verify credentials by testing connection to Greenplum
        // This will be implemented after DatabaseConnectionManager is updated

        // Generate ID part: gpmcp_{env}_{randomId}
        byte[] idBytes = new byte[6]; // 6 bytes = 8 base64 chars
        secureRandom.nextBytes(idBytes);
        String randomId = Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(idBytes);

        String id = String.format("gpmcp_%s_%s", environment, randomId);

        // Generate secret part (24 bytes = 32 base64 chars)
        byte[] secretBytes = new byte[24];
        secureRandom.nextBytes(secretBytes);
        String secret = Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(secretBytes);

        // Build full key: {id}.{secret}
        String fullKey = id + "." + secret;

        // Hash only the secret part for storage (SHA-256)
        String secretHash = hashKey(secret);

        // Encrypt credentials
        String encryptedUsername = encryptionService.encrypt(username);
        String encryptedPassword = encryptionService.encrypt(password);

        // Create API key entity
        ApiKey apiKey = new ApiKey();
        apiKey.setId(id);
        apiKey.setSecretHash(secretHash);
        apiKey.setEnvironment(environment);
        apiKey.setDescription(description);
        apiKey.setActive(true);
        apiKey.setCreatedBy(createdBy);

        // Greenplum user credentials (encrypted)
        apiKey.setEncryptedUsername(encryptedUsername);
        apiKey.setEncryptedPassword(encryptedPassword);

        if (expiresInDays != null) {
            apiKey.setExpiresAt(Instant.now().plus(expiresInDays, ChronoUnit.DAYS));
        }

        // Save to database
        repository.save(apiKey);

        log.info("🔑 Generated new API key: {} for GP user: {}",
            apiKey.getDisplayKey(), username);

        return new ApiKeyResult(fullKey, apiKey);
    }

    /**
     * Validate an API key
     *
     * Format: {id}.{secret}
     * Example: gpmcp_live_a1b2c3d4.xyz789abc...
     *
     * @param key The full API key
     * @return Optional containing the ApiKey if valid
     */
    public Optional<ApiKey> validateKey(String key) {
        if (key == null || key.trim().isEmpty()) {
            return Optional.empty();
        }

        // Parse the key: {id}.{secret}
        String[] parts = key.split("\\.", 2);
        if (parts.length != 2) {
            log.warn("⚠️ Invalid API key format (missing dot separator)");
            return Optional.empty();
        }

        String id = parts[0];
        String secret = parts[1];

        // Validate ID format
        if (!id.startsWith("gpmcp_")) {
            log.warn("⚠️ Invalid API key ID format");
            return Optional.empty();
        }

        // Look up by ID
        Optional<ApiKey> apiKeyOpt = repository.findById(id);

        if (apiKeyOpt.isEmpty()) {
            log.warn("⚠️ API key not found: {}", id);
            return Optional.empty();
        }

        ApiKey apiKey = apiKeyOpt.get();

        // Hash the provided secret and compare
        String providedSecretHash = hashKey(secret);
        if (!providedSecretHash.equals(apiKey.getSecretHash())) {
            log.warn("⚠️ Invalid secret for API key: {}", apiKey.getDisplayKey());
            return Optional.empty();
        }

        // Check if valid
        if (!apiKey.isValid()) {
            log.warn("⚠️ Inactive or expired API key attempted: {}", apiKey.getDisplayKey());
            return Optional.empty();
        }

        // Update last used timestamp
        repository.updateLastUsed(apiKey.getId());

        log.debug("✅ Valid API key: {}", apiKey.getDisplayKey());
        return Optional.of(apiKey);
    }

    /**
     * List all API keys (without revealing full keys)
     */
    public List<ApiKey> listKeys() {
        return repository.findAll();
    }

    /**
     * List API keys by environment
     */
    public List<ApiKey> listKeysByEnvironment(String environment) {
        return repository.findByEnvironment(environment);
    }

    /**
     * Revoke (deactivate) an API key
     */
    public void revokeKey(String keyId) {
        repository.revoke(keyId);
        log.info("🔒 Revoked API key: {}", keyId);
    }

    /**
     * Delete an API key permanently
     */
    public void deleteKey(String keyId) {
        repository.delete(keyId);
        log.info("🗑️ Deleted API key: {}", keyId);
    }

    /**
     * NOTE: Database and schema access control is now handled by Greenplum's native RBAC.
     * The GP user associated with the API key determines what they can access.
     * No need for application-level permission checking.
     */

    /**
     * Hash an API key using SHA-256
     */
    private String hashKey(String key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(key.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * Decrypt username from API key
     */
    public String decryptUsername(ApiKey apiKey) {
        return encryptionService.decrypt(apiKey.getEncryptedUsername());
    }

    /**
     * Decrypt password from API key
     */
    public String decryptPassword(ApiKey apiKey) {
        return encryptionService.decrypt(apiKey.getEncryptedPassword());
    }

    /**
     * Result of API key generation
     */
    public record ApiKeyResult(
        String fullKey,    // The complete key (only shown once!)
        ApiKey apiKey      // The stored key metadata
    ) {}
}
