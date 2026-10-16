package com.portfolio.trading.market;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The fail-open rules of the Redis cache, without a Redis. (The real thing is in RedisQuoteCacheIntegrationTest.) */
class RedisQuoteCacheTest {

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final RedisQuoteCache cache = new RedisQuoteCache(redis);

    private static final Quote QUOTE = new Quote("AAPL", new BigDecimal("90.4829"), "USD",
            Instant.parse("2026-10-16T06:29:55.651509Z"), "simulated");

    RedisQuoteCacheTest() {
        when(redis.opsForValue()).thenReturn(values);
    }

    @Test
    void encodesAndDecodesAQuote() {
        Quote back = RedisQuoteCache.decode("AAPL", RedisQuoteCache.encode(QUOTE));

        assertThat(back).isEqualTo(QUOTE);
    }

    @Test
    void usesOneKeyPerProviderAndSymbol() {
        cache.put("simulated", QUOTE, Duration.ofSeconds(5));

        verify(values).set(eq("quote:simulated:AAPL"), anyString(), eq(Duration.ofSeconds(5)));
    }

    @Test
    void aReadErrorIsAMiss() {
        when(values.get(anyString())).thenThrow(new IllegalStateException("redis down"));

        assertThat(cache.get("simulated", "AAPL")).isEmpty();
    }

    @Test
    void aWriteErrorIsSwallowed() {
        doThrow(new IllegalStateException("redis down")).when(values).set(anyString(), anyString(), any(Duration.class));

        cache.put("simulated", QUOTE, Duration.ofSeconds(5));   // must not throw
    }

    @Test
    void garbageInTheCacheIsAMiss() {
        when(values.get("quote:simulated:AAPL")).thenReturn("not|a|quote");

        Optional<Quote> result = cache.get("simulated", "AAPL");

        assertThat(result).isEmpty();
    }
}
