package com.portfolio.ledger.api;

import com.portfolio.ledger.domain.Account;
import com.portfolio.ledger.service.LedgerService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real security filter chain with a stubbed token decoder: who gets in, and which roles the token maps to. */
@WebMvcTest(LedgerController.class)
@Import(SecurityConfig.class)
class SecurityRulesTest {

    @Autowired MockMvc mvc;
    @MockitoBean LedgerService ledger;
    @MockitoBean AccountAccess access;
    @MockitoBean JwtDecoder jwtDecoder;

    private static final UUID ID = UUID.randomUUID();

    private Jwt token(String subject, String... realmRoles) {
        return Jwt.withTokenValue("t-" + subject)
                .header("alg", "none")
                .subject(subject)
                .claim("realm_access", Map.of("roles", List.of(realmRoles)))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
    }

    private String bearer(String subject, String... realmRoles) {
        Jwt jwt = token(subject, realmRoles);
        when(jwtDecoder.decode(jwt.getTokenValue())).thenReturn(jwt);
        return "Bearer " + jwt.getTokenValue();
    }

    @Test
    void noToken_returns401_withProblemBody() throws Exception {
        mvc.perform(get("/api/v1/accounts/" + ID))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void invalidToken_returns401() throws Exception {
        when(jwtDecoder.decode("forged")).thenThrow(new BadJwtException("signature check failed"));

        mvc.perform(get("/api/v1/accounts/" + ID).header("Authorization", "Bearer forged"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void validUserToken_reachesTheController_asTheTokenSubject() throws Exception {
        when(ledger.getAccount(ID)).thenReturn(new Account(ID, "Alice", "USD", BigDecimal.TEN, false));

        mvc.perform(get("/api/v1/accounts/" + ID).header("Authorization", bearer("user-1", "USER")))
                .andExpect(status().isOk());

        ArgumentCaptor<Authentication> auth = ArgumentCaptor.forClass(Authentication.class);
        verify(access).require(auth.capture(), eq(ID));
        assertEquals("user-1", auth.getValue().getName());
        assertTrue(auth.getValue().getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_USER")));
    }

    @Test
    void reconciliation_isForbiddenForUsers_andAllowedForAdmins() throws Exception {
        mvc.perform(get("/api/v1/reconciliation").header("Authorization", bearer("user-1", "USER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mvc.perform(get("/api/v1/reconciliation").header("Authorization", bearer("admin-1", "USER", "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consistent").value(true));
    }

    @Test
    void pathsOutsideTheApi_areDenied() throws Exception {
        mvc.perform(get("/internal/anything").header("Authorization", bearer("admin-1", "ADMIN")))
                .andExpect(status().isForbidden());
    }

    @Test
    void realmRoles_mapToRoleAuthorities_andMissingClaimMapsToNone() {
        assertEquals(List.of("ROLE_USER", "ROLE_ADMIN"),
                SecurityConfig.realmRoles(token("u", "USER", "ADMIN")).stream().map(a -> a.getAuthority()).toList());

        Jwt noRoles = Jwt.withTokenValue("x").header("alg", "none").subject("u").build();
        assertTrue(SecurityConfig.realmRoles(noRoles).isEmpty());
    }
}
