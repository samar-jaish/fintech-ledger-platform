package com.portfolio.trading.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

/**
 * Chooses the MarketDataProvider from {@code market.provider}: simulated (default), replay or live, optionally cached in Redis.
 * Nothing outside this package knows which one is active.
 */
@Configuration
public class MarketDataConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * The one MarketDataProvider bean. Picks the source from market.provider and, when market.cache.enabled=true,
     * puts the Redis quote cache in front of it.
     */
    @Bean
    MarketDataProvider marketDataProvider(Clock clock, PriceHistoryRepository history, ObjectProvider<QuoteCache> cache,
            @Value("${market.provider:simulated}") String kind,
            @Value("${market.cache.ttl:5s}") Duration cacheTtl,
            @Value("${market.simulated.seed:42}") long seed,
            @Value("${market.simulated.latency-ms:0}") long latencyMillis,
            @Value("${market.simulated.failure-rate:0.0}") double failureRate,
            @Value("${market.live.finnhub.base-url:https://finnhub.io/api/v1}") String finnhubBaseUrl,
            @Value("${market.live.finnhub.api-key:}") String finnhubKey,
            @Value("${market.live.twelvedata.base-url:https://api.twelvedata.com}") String twelveDataBaseUrl,
            @Value("${market.live.twelvedata.api-key:}") String twelveDataKey) {
        MarketDataProvider provider = switch (kind) {
            case "simulated" -> new SimulatedMarketDataProvider(seed, clock, latencyMillis, failureRate);
            case "replay" -> new ReplayMarketDataProvider(history);
            case "live" -> {
                // Fail at startup instead of silently serving fake or empty data.
                if (finnhubKey.isBlank() || twelveDataKey.isBlank()) {
                    throw new IllegalStateException(
                            "market.provider=live requires FINNHUB_API_KEY and TWELVEDATA_API_KEY to be set");
                }
                HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
                yield new LiveMarketDataProvider(http, new ObjectMapper(),
                        finnhubBaseUrl, finnhubKey, twelveDataBaseUrl, twelveDataKey);
            }
            default -> throw new IllegalStateException(
                    "Unknown market.provider '" + kind + "' (expected simulated, replay or live)");
        };
        QuoteCache quoteCache = cache.getIfAvailable();
        return quoteCache == null ? provider : new CachingMarketDataProvider(provider, quoteCache, cacheTtl);
    }

    /** Only exists when market.cache.enabled=true, so the service runs without Redis by default. */
    @Bean
    @ConditionalOnProperty(name = "market.cache.enabled", havingValue = "true")
    QuoteCache quoteCache(StringRedisTemplate redis) {
        return new RedisQuoteCache(redis);
    }
}
