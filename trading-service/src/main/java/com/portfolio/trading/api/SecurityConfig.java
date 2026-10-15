package com.portfolio.trading.api;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

import java.io.IOException;

/**
 * Same model as the ledger: an OAuth2 resource server validating Keycloak JWTs, stateless, no CSRF (no cookies).
 * Roles are not needed here. Who may use an account is decided by the ledger (see AccountAccess).
 */
@Configuration
@EnableWebSecurity
class SecurityConfig {

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http) throws Exception {
        return http
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
