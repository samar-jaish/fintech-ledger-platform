package com.portfolio.trading.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Clock;
import java.time.Instant;

/**
 * Gets a token for this service from Keycloak using the OAuth2 client credentials grant (the service logs in with
 * its own client id and secret; there is no user). The token is cached and renewed shortly before it expires.
 */
@Component
class OAuthServiceTokens implements ServiceTokens {

    private static final long RENEW_MARGIN_SECONDS = 30;

    private final RestClient http;
    private final String tokenUrl;
    private final String clientId;
    private final String clientSecret;
    private final Clock clock;

    private String token;
    private Instant expiresAt = Instant.MIN;

    OAuthServiceTokens(RestClient.Builder builder,
                       @Value("${ledger-client.token-url}") String tokenUrl,
                       @Value("${ledger-client.client-id}") String clientId,
                       @Value("${ledger-client.client-secret}") String clientSecret,
                       Clock clock) {
        this.http = builder.build();
        this.tokenUrl = tokenUrl;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.clock = clock;
    }

    @Override
    public synchronized String token() {
        if (token == null || clock.instant().isAfter(expiresAt.minusSeconds(RENEW_MARGIN_SECONDS))) {
            refresh();
        }
        return token;
    }

    private void refresh() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        try {
            JsonNode response = http.post().uri(tokenUrl).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form).retrieve().body(JsonNode.class);
            if (response == null || !response.hasNonNull("access_token")) {
                throw new LedgerUnavailableException("Identity provider returned no access token");
            }
            token = response.get("access_token").asText();
            expiresAt = clock.instant().plusSeconds(response.path("expires_in").asLong(60));
        } catch (RestClientException e) {
            throw new LedgerUnavailableException("Could not obtain a service token", e);
        }
    }
}
