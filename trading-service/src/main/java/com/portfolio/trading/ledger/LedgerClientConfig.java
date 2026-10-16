package com.portfolio.trading.ledger;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/** The LedgerClient the rest of the service sees: the HTTP client wrapped in retries and a circuit breaker. */
@Configuration
class LedgerClientConfig {

    @Bean
    LedgerClient ledgerClient(RestClient.Builder builder, ServiceTokens tokens,
            @Value("${ledger-client.base-url}") String baseUrl,
            @Value("${ledger-client.resilience.max-attempts:3}") int maxAttempts,
            @Value("${ledger-client.resilience.initial-backoff:200ms}") Duration initialBackoff,
            @Value("${ledger-client.resilience.window-size:10}") int windowSize,
            @Value("${ledger-client.resilience.minimum-calls:5}") int minimumCalls,
            @Value("${ledger-client.resilience.failure-rate-percent:50}") float failureRatePercent,
            @Value("${ledger-client.resilience.open-duration:10s}") Duration openDuration,
            @Value("${ledger-client.resilience.half-open-calls:2}") int halfOpenCalls) {
        ResilienceSettings settings = new ResilienceSettings(maxAttempts, initialBackoff, windowSize, minimumCalls,
                failureRatePercent, openDuration, halfOpenCalls);
        return new ResilientLedgerClient(new HttpLedgerClient(builder, baseUrl, tokens), settings);
    }
}
