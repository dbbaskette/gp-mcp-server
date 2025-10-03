package com.baskettecase.gpmcp.security;

import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Utility to access the current API key from request context.
 */
public class ApiKeyContext {

    private static final String API_KEY_ATTRIBUTE = "apiKey";

    /**
     * Get the current API key from the request context.
     * Throws IllegalStateException if no API key is found.
     */
    public static ApiKey getCurrentApiKey() {
        HttpServletRequest request = getCurrentRequest();
        ApiKey apiKey = (ApiKey) request.getAttribute(API_KEY_ATTRIBUTE);

        if (apiKey == null) {
            throw new IllegalStateException(
                "No API key found in request context. " +
                "Ensure ApiKeyAuthenticationFilter is configured correctly."
            );
        }

        return apiKey;
    }

    /**
     * Get the current HTTP request from Spring's RequestContextHolder.
     */
    private static HttpServletRequest getCurrentRequest() {
        ServletRequestAttributes attributes =
            (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();

        if (attributes == null) {
            throw new IllegalStateException("No request context available");
        }

        return attributes.getRequest();
    }
}
