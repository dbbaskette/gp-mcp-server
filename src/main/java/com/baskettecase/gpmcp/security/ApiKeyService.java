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

        // Generate random token (32 characters)
        byte[] randomBytes = new byte[24]; // 24 bytes = 32 base64 chars
        secureRandom.nextBytes(randomBytes);
        String token = Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(randomBytes);

        // Build full key: gpmcp_{env}_{token}
        String fullKey = String.format("gpmcp_%s_%s", environment, token);

        // Hash the full key for storage
        String keyHash = hashKey(fullKey);

        // Extract prefix (first 8 chars of token)
        String keyPrefix = token.substring(0, Math.min(8, token.length()));

        // Encrypt credentials
        String encryptedUsername = encryptionService.encrypt(username);
        String encryptedPassword = encryptionService.encrypt(password);

        // Create API key entity
        ApiKey apiKey = new ApiKey();
        apiKey.setKeyPrefix(keyPrefix);
        apiKey.setKeyHash(keyHash);
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
     * @param key The full API key
     * @return Optional containing the ApiKey if valid
     */
    public Optional<ApiKey> validateKey(String key) {
        if (key == null || !key.startsWith("gpmcp_")) {
            return Optional.empty();
        }

        // Hash the provided key
        String keyHash = hashKey(key);

        // Look up in database
        Optional<ApiKey> apiKeyOpt = repository.findByKeyHash(keyHash);

        if (apiKeyOpt.isEmpty()) {
            log.warn("⚠️ Invalid API key attempted");
            return Optional.empty();
        }

        ApiKey apiKey = apiKeyOpt.get();

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
