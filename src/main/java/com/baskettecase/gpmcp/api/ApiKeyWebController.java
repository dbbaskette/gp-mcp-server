package com.baskettecase.gpmcp.api;

import com.baskettecase.gpmcp.security.ApiKey;
import com.baskettecase.gpmcp.security.ApiKeyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * API Key Web Controller
 *
 * Provides web UI and endpoints for self-service API key generation.
 */
@Slf4j
@Controller
@RequestMapping("/admin/api-keys")
@RequiredArgsConstructor
public class ApiKeyWebController {

    private final ApiKeyService apiKeyService;
    private final ObjectMapper objectMapper;

    /**
     * Serve the API key management web UI
     */
    @GetMapping
    public String apiKeysPage() {
        return "api-keys.html";
    }

    /**
     * Test database connection with provided credentials
     */
    @PostMapping("/test-connection")
    @ResponseBody
    public ResponseEntity<ConnectionTestResponse> testConnection(
        @RequestBody ConnectionTestRequest request
    ) {
        log.info("Testing connection to: {}", request.targetHost);

        try {
            // Parse host:port
            String[] parts = request.targetHost.split(":");
            String host = parts[0];
            String port = parts.length > 1 ? parts[1] : "5432";

            // Build JDBC URL
            String jdbcUrl = String.format("jdbc:postgresql://%s:%s/%s",
                host, port, request.defaultDatabase);

            // Create temporary connection
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(jdbcUrl);
            config.setUsername(request.username);
            config.setPassword(request.password);
            config.setMaximumPoolSize(1);
            config.setConnectionTimeout(5000);
            config.setValidationTimeout(3000);

            try (HikariDataSource dataSource = new HikariDataSource(config)) {
                JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

                // Test query
                String version = jdbcTemplate.queryForObject(
                    "SELECT version()", String.class);

                log.info("✅ Connection test successful: {}", request.targetHost);

                return ResponseEntity.ok(new ConnectionTestResponse(
                    true,
                    "Connection successful!",
                    version
                ));
            }

        } catch (Exception e) {
            log.error("❌ Connection test failed for {}: {}",
                request.targetHost, e.getMessage());

            return ResponseEntity.ok(new ConnectionTestResponse(
                false,
                "Connection failed: " + e.getMessage(),
                null
            ));
        }
    }

    /**
     * Generate a new API key
     */
    @PostMapping("/generate")
    @ResponseBody
    public ResponseEntity<ApiKeyGenerationResponse> generateApiKey(
        @RequestBody ApiKeyGenerationRequest request
    ) {
        log.info("Generating API key for GP user: {}", request.username);

        try {
            // Generate API key with GP user credentials
            ApiKeyService.ApiKeyResult result = apiKeyService.generateKey(
                request.environment != null ? request.environment : "live",
                request.description,
                "web-ui",
                request.username,
                request.password,
                request.expiresInDays
            );

            log.info("✅ Generated API key: {}", result.apiKey().getDisplayKey());

            return ResponseEntity.ok(new ApiKeyGenerationResponse(
                true,
                result.fullKey(),
                result.apiKey().getDisplayKey(),
                "API key generated successfully! Save this key - it will not be shown again."
            ));

        } catch (Exception e) {
            log.error("❌ Failed to generate API key", e);

            return ResponseEntity.status(500).body(new ApiKeyGenerationResponse(
                false,
                null,
                null,
                "Failed to generate API key: " + e.getMessage()
            ));
        }
    }

    /**
     * List all API keys (without showing full keys)
     */
    @GetMapping("/list")
    @ResponseBody
    public ResponseEntity<List<ApiKeySummary>> listApiKeys() {
        List<ApiKey> keys = apiKeyService.listKeys();

        List<ApiKeySummary> summaries = keys.stream()
            .map(key -> new ApiKeySummary(
                key.getId(),
                key.getDisplayKey(),
                key.getEnvironment(),
                key.getDescription(),
                key.isActive(),
                key.getCreatedAt().toString(),
                key.getExpiresAt() != null ? key.getExpiresAt().toString() : null
            ))
            .toList();

        return ResponseEntity.ok(summaries);
    }

    /**
     * Request for testing database connection
     */
    public record ConnectionTestRequest(
        String targetHost,
        String username,
        String password,
        String defaultDatabase
    ) {}

    /**
     * Response for connection test
     */
    public record ConnectionTestResponse(
        boolean success,
        String message,
        String serverVersion
    ) {}

    /**
     * Request for generating API key
     * Now simplified - only GP user credentials needed
     */
    public record ApiKeyGenerationRequest(
        String username,        // Greenplum username
        String password,        // Greenplum password
        String environment,     // "live" or "test"
        String description,     // User-provided description
        Integer expiresInDays   // Optional expiration
    ) {}

    /**
     * Response for API key generation
     */
    public record ApiKeyGenerationResponse(
        boolean success,
        String apiKey,          // Full key (only shown once!)
        String displayKey,
        String message
    ) {}

    /**
     * API key summary (without sensitive data)
     */
    public record ApiKeySummary(
        String id,
        String displayKey,
        String environment,
        String description,
        boolean active,
        String createdAt,
        String expiresAt
    ) {}
}
