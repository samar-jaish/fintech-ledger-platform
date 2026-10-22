package com.portfolio.notification;

import jakarta.servlet.DispatcherType;
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
 * Same model as the other services: a stateless OAuth2 resource server (bearer JWT from Keycloak, validated here), CORS
 * for the web UI's origin, and operations endpoints on their own port that need no token.
 * Any signed-in user may call the API; what they see is limited to their own notifications in the controller.
 */
@Configuration
@EnableWebSecurity
class SecurityConfig {

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
                .cors(Customizer.withDefaults())   // answers the browser's preflight before authentication
                .csrf(csrf -> csrf.disable())      // no cookies are used, so there is nothing to forge
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // These two are second passes inside the server for a request that was already checked: the end of a
                        // live stream (async) and the error page for a failed request. They cannot be started from outside.
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers("/api/v1/**").authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint((req, res, e) -> problem(res, HttpServletResponse.SC_UNAUTHORIZED,
                                "Unauthorized", "UNAUTHENTICATED", "A valid bearer token is required."))
                        .accessDeniedHandler((req, res, e) -> problem(res, HttpServletResponse.SC_FORBIDDEN,
                                "Forbidden", "FORBIDDEN", "You do not have permission to do this.")))
                .build();
    }

    /** Only the listed origins may call from a browser. Last-Event-ID is sent by a client resuming a stream. */
    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${app.cors.allowed-origins:}") List<String> allowedOrigins) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(allowedOrigins.stream().map(String::trim).filter(o -> !o.isEmpty()).toList());
        cors.setAllowedMethods(List.of("GET", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "Last-Event-ID"));
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
