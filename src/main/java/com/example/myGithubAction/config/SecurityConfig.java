package com.example.myGithubAction.config;

import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

import com.example.myGithubAction.auth.security.JwtAuthenticationFilter;

/**
 * Security Configuration for JWT-based Authentication
 *
 */
@Configuration
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final CorsConfigurationSource corsConfigurationSource;

    /** Dev pages under /dev/** are public only when this is on (default off). */
    @Value("${app.dev-ui.enabled:false}")
    private boolean devUiEnabled;

    public SecurityConfig(
            JwtAuthenticationFilter jwtAuthenticationFilter,
            CorsConfigurationSource corsConfigurationSource
    ) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.corsConfigurationSource = corsConfigurationSource;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // Enable CORS
            .cors(cors -> cors.configurationSource(corsConfigurationSource))

            // Disable CSRF for stateless API
            .csrf(csrf -> csrf.disable())

            // Configure authorization rules
            .authorizeHttpRequests(auth -> {
              if (devUiEnabled) {
                  // The page holds no data; its API calls still carry a JWT.
                  auth.requestMatchers("/dev/**").permitAll();
              }
              auth
                // Async re-dispatch (e.g. SseEmitter completion) of a request that was already
                // authorized on its REQUEST dispatch. The JWT filter (OncePerRequestFilter) does
                // not run again on it, so without this it is denied on a committed response.
                // Same for the ERROR dispatch to /error after a failed request: its real status
                // (400, 500, ...) must not turn into 401. Only container-internal dispatches
                // match; a direct GET /error is a REQUEST dispatch and still needs a token.
                .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                // Public endpoints - no authentication required
                .requestMatchers(
                    "/v3/api-docs/**",
                    "/swagger-ui/**",
                    "/swagger-ui.html",
                    "/api/auth/**"
                ).permitAll()
                // Manual status override of executions/steps: admin only (authenticated non-admin
                // gets 403 from the access-denied handler, unauthenticated 401 from the entry point)
                .requestMatchers(HttpMethod.PATCH, "/api/workflow-executions/**").hasRole("ADMIN")
                // Protected endpoints - require authentication (valid JWT token)
                .requestMatchers("/api/**").authenticated()

                // All other requests require authentication
                .anyRequest().authenticated();
            })

            // Set session management to STATELESS
            .sessionManagement(sess ->
                sess.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )

            // Missing/invalid token -> 401 (the default without a login mechanism is 403)
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
            );

        // Register JWT filter before UsernamePasswordAuthenticationFilter
        http.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
