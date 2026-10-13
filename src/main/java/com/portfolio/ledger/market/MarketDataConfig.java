package com.portfolio.ledger.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

/**
 * Chooses the MarketDataProvider from {@code market.provider}: simulated (default), replay or live.
 * Nothing outside this package knows which one is active.
 */
@Configuration
public class MarketDataConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnProperty(name = "market.provider", havingValue = "simulated", matchIfMissing = true)
    MarketDataProvider simulatedMarketDataProvider(Clock clock,
            @Value("${market.simulated.seed:42}") long seed,
            @Value("${market.simulated.latency-ms:0}") long latencyMillis,
            @Value("${market.simulated.failure-rate:0.0}") double failureRate) {
        return new SimulatedMarketDataProvider(seed, clock, latencyMillis, failureRate);
    }

    @Bean
    @ConditionalOnProperty(name = "market.provider", havingValue = "replay")
    MarketDataProvider replayMarketDataProvider(PriceHistoryRepository history) {
        return new ReplayMarketDataProvider(history);
    }

    @Bean
    @ConditionalOnProperty(name = "market.provider", havingValue = "live")
    MarketDataProvider liveMarketDataProvider(
            @Value("${market.live.finnhub.base-url:https://finnhub.io/api/v1}") String finnhubBaseUrl,
            @Value("${market.live.finnhub.api-key:}") String finnhubKey,
            @Value("${market.live.twelvedata.base-url:https://api.twelvedata.com}") String twelveDataBaseUrl,
            @Value("${market.live.twelvedata.api-key:}") String twelveDataKey) {
        // Fail at startup instead of silently serving fake or empty data.
        if (finnhubKey.isBlank() || twelveDataKey.isBlank()) {
            throw new IllegalStateException(
                    "market.provider=live requires FINNHUB_API_KEY and TWELVEDATA_API_KEY to be set");
        }
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        return new LiveMarketDataProvider(http, new ObjectMapper(),
                finnhubBaseUrl, finnhubKey, twelveDataBaseUrl, twelveDataKey);
    }
}
