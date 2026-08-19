package com.example.myGithubAction.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * CORS Configuration for Frontend Access
 *
 * Allows frontend applications (React, Vue, etc) from specified domains
 * to access backend APIs with proper CORS headers.
 *
 * This configuration:
 * - Allows specific origins (development and production)
 * - Supports JWT tokens via Authorization header
 * - Allows credentials (cookies, tokens)
 * - Caches preflight requests for 1 hour
 *
 * @author Backend Team
 */
@Configuration
public class CorsConfig {

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();

        // Development
        configuration.addAllowedOrigin("http://localhost:3000");

        // configuration.addAllowedOrigin("https://yourdomain.com");
        // configuration.addAllowedOrigin("https://www.yourdomain.com");

        configuration.addAllowedMethod("GET");
        configuration.addAllowedMethod("POST");
        configuration.addAllowedMethod("PUT");
        configuration.addAllowedMethod("DELETE");
        configuration.addAllowedMethod("PATCH");
        configuration.addAllowedMethod("OPTIONS");

        configuration.addAllowedHeader("Access-Control-Allow-Headers");
        configuration.addAllowedHeader("Access-Control-Allow-Origin");
        configuration.addAllowedHeader("Content-Type");
        configuration.addAllowedHeader("Authorization");      // For JWT token
        configuration.addAllowedHeader("X-Requested-With");
        configuration.addAllowedHeader("Accept");
        configuration.addAllowedHeader("Origin");

        // These headers are accessible to JavaScript in the browser
        configuration.addExposedHeader("Access-Control-Allow-Origin");
        configuration.addExposedHeader("Access-Control-Allow-Credentials");
        configuration.addExposedHeader("Authorization");
        configuration.addExposedHeader("Content-Type");
        configuration.addExposedHeader("X-Total-Count");       // For pagination
        configuration.addExposedHeader("X-Page-Number");
        configuration.addExposedHeader("X-Page-Size");

        // ✅ Allow Credentials
        // Allows sending cookies and Authorization header with requests
        configuration.setAllowCredentials(true);

        // ✅ Max Age
        // Browser caches preflight response for 3600 seconds (1 hour)
        configuration.setMaxAge(3600L);

        // ✅ Register CORS configuration for all paths
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);

        return source;
    }
}
