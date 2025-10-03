package com.baskettecase.gpmcp.security;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Security Configuration
 *
 * Configures API key authentication for the MCP server using Spring Security.
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final ApiKeyAuthenticationFilter apiKeyAuthenticationFilter;

    @Value("${gp.mcp.security.api-key-enabled:true}")
    private boolean apiKeyEnabled;

    /**
     * Configure Spring Security to use API key authentication
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())  // Disable CSRF - using API keys, not sessions
            .httpBasic(basic -> basic.disable())  // Disable HTTP Basic - using API keys
            .formLogin(form -> form.disable())  // Disable form login
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))  // No sessions
            .authorizeHttpRequests(authz -> authz
                // Public endpoints
                .requestMatchers("/actuator/**").permitAll()
                .requestMatchers("/admin/**").permitAll()  // Allow admin endpoints (key generation)
                .requestMatchers("/mcp").authenticated()  // MCP endpoint requires API key auth
                .requestMatchers("/mcp/**").authenticated()  // MCP sub-paths require API key auth
                // All other endpoints require authentication
                .anyRequest().authenticated()
            );

        // Add our API key filter if enabled
        if (apiKeyEnabled) {
            http.addFilterBefore(apiKeyAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        }

        return http.build();
    }

    /**
     * Register the API key authentication filter
     * Note: This is now also added to Spring Security chain above
     */
    @Bean
    public FilterRegistrationBean<ApiKeyAuthenticationFilter> apiKeyFilterRegistration() {
        FilterRegistrationBean<ApiKeyAuthenticationFilter> registration =
            new FilterRegistrationBean<>(apiKeyAuthenticationFilter);

        // Disable automatic registration - we add it to Spring Security chain instead
        registration.setEnabled(false);

        return registration;
    }
}
