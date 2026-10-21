package com.portfolio.trading.api;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.IOException;
import java.util.List;

/**
 * Same model as the ledger: an OAuth2 resource server validating Keycloak JWTs, stateless, no CSRF (no cookies).
 * Roles are not needed here. Who may use an account is decided by the ledger (see AccountAccess).
 */
@Configuration
@EnableWebSecurity
class SecurityConfig {

    /**
     * Operations endpoints (health, Prometheus) are served on their own management port, which is not part of the public
     * API and is not published to the outside in production. This chain lets them through without a token so Prometheus
     * and the container health checks can reach them. On the API port these paths do not exist (404).
     */
    @Bean
    @Order(1)
    SecurityFilterChain operationsSecurity(HttpSecurity http) throws Exception {
        return http
                .securityMatcher("/actuator/**")
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http) throws Exception {
        return http
                .cors(Customizer.withDefaults())   // uses the corsConfigurationSource bean below; answers preflight before authentication
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/**").authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> { })
                        .authenticationEntryPoint((req, res, e) -> problem(res, HttpServletResponse.SC_UNAUTHORIZED,
                                "Unauthorized", "UNAUTHENTICATED", "A valid bearer token is required."))
                        .accessDeniedHandler((req, res, e) -> problem(res, HttpServletResponse.SC_FORBIDDEN,
                                "Forbidden", "FORBIDDEN", "You do not have permission to do this.")))
                .build();
    }

    /**
     * Browser calls from the web UI (a different origin from the API) need CORS. Only the origins listed in
     * app.cors.allowed-origins may call, with the few methods and headers the UI uses. No cookies are involved (bearer
     * tokens only), so credentials are not enabled. Calls without an Origin header (curl, other services) are unaffected.
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${app.cors.allowed-origins:}") List<String> allowedOrigins) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(allowedOrigins.stream().map(String::trim).filter(o -> !o.isEmpty()).toList());
        cors.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key"));
        cors.setExposedHeaders(List.of("Location"));
        cors.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        return source;
    }

    /** Fixed text only; nothing from the request is echoed back. */
    private static void problem(HttpServletResponse res, int status, String title, String code, String detail)
            throws IOException {
        res.setStatus(status);
        res.setContentType("application/problem+json");
        if (status == HttpServletResponse.SC_UNAUTHORIZED) {
            res.setHeader("WWW-Authenticate", "Bearer");
        }
        res.getWriter().write("{\"type\":\"about:blank\",\"title\":\"" + title + "\",\"status\":" + status
                + ",\"detail\":\"" + detail + "\",\"code\":\"" + code + "\"}");
    }
}
