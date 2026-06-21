package com.example.myGithubAction.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;

/**
 * JWT Authentication Filter
 *
 */
@Component
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;

    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider) {
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain) throws ServletException, IOException {
        try {
            // Step 1: Extract JWT token from Authorization header
            String token = extractTokenFromRequest(request);

            // Step 2: Validate token if present
            if (token != null && jwtTokenProvider.validateToken(token)) {
                // Step 3: Check if token is expired
                if (jwtTokenProvider.isTokenExpired(token)) {
                    log.warn("Token has expired for request: {}", request.getRequestURI());
                    filterChain.doFilter(request, response);
                    return;
                }

                // Step 4: Extract username from token
                String username = jwtTokenProvider.getUsernameFromToken(token);

                // Step 5: Set authentication in SecurityContext
                // This allows Spring Security to recognize the user as authenticated
                UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                        username,
                        null,
                        new ArrayList<>() // authorities - you can add roles here
                    );
                SecurityContextHolder.getContext().setAuthentication(authentication);
                log.debug("Set authentication for user: {} on URI: {}", username, request.getRequestURI());
            } else if (token != null) {
                log.warn("Invalid JWT token for request: {}", request.getRequestURI());
            }

        } catch (Exception e) {
            log.error("Cannot set user authentication in security context: {}", e.getMessage());
        }

        // Continue with the filter chain   
        filterChain.doFilter(request, response);
    }

    /**
     * Extracts JWT token from Authorization header
     *
     * @param request HttpServletRequest
     * @return JWT token or null if not found
     */
    private String extractTokenFromRequest(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");

        if (authHeader == null) {
            return null;
        }

        // Check if header starts with "Bearer "
        if (!authHeader.startsWith("Bearer ")) {
            log.debug("Authorization header does not start with 'Bearer ': {}", request.getRequestURI());
            return null;
        }

        // Extract token (remove "Bearer " prefix)
        String token = authHeader.substring(7);

        if (token.isEmpty()) {
            log.debug("Empty token in Authorization header: {}", request.getRequestURI());
            return null;
        }

        return token;
    }
}
