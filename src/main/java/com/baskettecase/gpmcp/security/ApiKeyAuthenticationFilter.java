package com.baskettecase.gpmcp.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.Optional;

/**
 * API Key Authentication Filter
 *
 * Validates API keys from Authorization or X-API-Key headers.
 * Expected format: "Bearer {id}.{secret}" or "{id}.{secret}"
 * Example: "Bearer gpmcp_live_a1b2c3d4.xyz789..." or "gpmcp_live_a1b2c3d4.xyz789..."
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private final ApiKeyService apiKeyService;

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {

        String requestPath = request.getRequestURI();
        log.debug("🔍 API Key Filter processing: {} {}", request.getMethod(), requestPath);

        // Skip authentication for health/metrics endpoints
        if (isPublicEndpoint(requestPath)) {
            log.debug("⏭️  Public endpoint, skipping authentication: {}", requestPath);
            filterChain.doFilter(request, response);
            return;
        }

        // Extract API key from Authorization header
        String authHeader = request.getHeader("Authorization");
        log.debug("📋 Authorization header: {}", authHeader != null ? "present" : "missing");
        String apiKey = extractApiKey(authHeader);

        if (apiKey == null) {
            // Also check for X-API-Key header as fallback
            apiKey = request.getHeader("X-API-Key");
        }

        // Validate API key
        if (apiKey != null) {
            log.debug("🔑 Validating API key...");
            Optional<ApiKey> validKey = apiKeyService.validateKey(apiKey);

            if (validKey.isPresent()) {
                // Store API key in request attribute for later use
                request.setAttribute("apiKey", validKey.get());

                // Set Spring Security authentication context
                UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                        validKey.get().getId(),  // Principal = API key ID
                        null,  // Credentials = null (already authenticated)
                        Collections.singletonList(new SimpleGrantedAuthority("ROLE_API_USER"))
                    );
                SecurityContextHolder.getContext().setAuthentication(authentication);

                log.debug("✅ Authenticated request with API key: {}, SecurityContext set: {}",
                    validKey.get().getDisplayKey(),
                    SecurityContextHolder.getContext().getAuthentication() != null);
                filterChain.doFilter(request, response);
                return;
            } else {
                log.warn("❌ Invalid API key attempted from {}", request.getRemoteAddr());
            }
        } else {
            log.debug("⚠️  No API key found in request");
        }

        // No valid API key - return 401 Unauthorized
        log.debug("🚫 Returning 401 Unauthorized");
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write(
            "{\"error\":\"Unauthorized\",\"message\":\"Valid API key required. " +
            "Provide in Authorization header as 'Bearer {id}.{secret}' or in X-API-Key header as '{id}.{secret}'\"}"
        );
    }

    /**
     * Extract API key from Authorization header
     * Supports: "Bearer {id}.{secret}" or "{id}.{secret}"
     * Example: "Bearer gpmcp_live_a1b2c3d4.xyz789..." or "gpmcp_live_a1b2c3d4.xyz789..."
     */
    private String extractApiKey(String authHeader) {
        if (authHeader == null || authHeader.trim().isEmpty()) {
            return null;
        }

        authHeader = authHeader.trim();

        // Remove "Bearer " prefix if present
        if (authHeader.toLowerCase().startsWith("bearer ")) {
            authHeader = authHeader.substring(7).trim();
        }

        // Validate format: must start with gpmcp_ and contain a dot separator
        if (authHeader.startsWith("gpmcp_") && authHeader.contains(".")) {
            return authHeader;
        }

        return null;
    }

    /**
     * Check if endpoint is public (doesn't require authentication)
     */
    private boolean isPublicEndpoint(String path) {
        return path.startsWith("/actuator/health") ||
               path.startsWith("/actuator/info") ||
               path.startsWith("/actuator/prometheus") ||
               path.startsWith("/actuator/metrics") ||
               path.startsWith("/admin/");  // Allow unauthenticated access to admin endpoints (key generation)
    }
}
