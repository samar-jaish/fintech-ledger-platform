package com.portfolio.ledger.api;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * The API is an OAuth2 resource server: every request carries a bearer JWT issued by Keycloak, which this service
 * validates (signature, issuer, expiry). There are no sessions and no CSRF tokens because no cookies are used.
 *
 * Roles come from Keycloak's realm roles (claim realm_access.roles) and become ROLE_USER / ROLE_ADMIN.
 * Who may touch which account is decided separately, in AccountAccess.
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
                        .requestMatchers(HttpMethod.GET, "/api/v1/reconciliation").hasRole("ADMIN")
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/**").authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint((req, res, e) -> problem(res, HttpServletResponse.SC_UNAUTHORIZED,
                                "Unauthorized", "UNAUTHENTICATED", "A valid bearer token is required."))
                        .accessDeniedHandler((req, res, e) -> problem(res, HttpServletResponse.SC_FORBIDDEN,
                                "Forbidden", "FORBIDDEN", "You do not have permission to do this.")))
                .build();
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(SecurityConfig::realmRoles);
        return converter;
    }

    /** Maps Keycloak realm roles to Spring authorities: "ADMIN" becomes ROLE_ADMIN. */
    static Collection<GrantedAuthority> realmRoles(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
        Object roles = realmAccess == null ? null : realmAccess.get("roles");
        if (!(roles instanceof Collection<?> names)) {
            return List.of();
        }
        return names.stream()
                .<GrantedAuthority>map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
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
