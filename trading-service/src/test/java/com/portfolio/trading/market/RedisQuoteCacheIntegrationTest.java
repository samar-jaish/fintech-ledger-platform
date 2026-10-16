package com.portfolio.trading.market;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** The quote cache against a real Redis, plus the fail-open behaviour when nothing is listening. */
@Testcontainers
class RedisQuoteCacheIntegrationTest {

    @Container
    static GenericContainer<?> redisContainer = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static LettuceConnectionFactory factory;
    static RedisQuoteCache cache;

    private static final Quote QUOTE = new Quote("AAPL", new BigDecimal("90.4829"), "USD", Instant.parse("2026-10-16T06:29:55Z"), "simulated");

    @BeforeAll
    static void connect() {
        factory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(redisContainer.getHost(), redisContainer.getMappedPort(6379)),
                LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(500)).build());
        factory.afterPropertiesSet();
        StringRedisTemplate template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        cache = new RedisQuoteCache(template);
    }

    @AfterAll
    static void disconnect() {
        factory.destroy();
    }

    @Test
    void storesAndReadsAQuote() {
        cache.put("simulated", QUOTE, Duration.ofSeconds(30));

        assertThat(cache.get("simulated", "AAPL")).contains(QUOTE);
        assertThat(cache.get("simulated", "MSFT")).isEmpty();
    }

    @Test
    void aQuoteExpiresAfterItsTtl() throws InterruptedException {
        cache.put("simulated", new Quote("TSLA", new BigDecimal("200"), "USD", Instant.now(), "simulated"), Duration.ofMillis(300));
        assertThat(cache.get("simulated", "TSLA")).isPresent();

        Thread.sleep(600);

        assertThat(cache.get("simulated", "TSLA")).isEmpty();
    }

    @Test
    void failsOpenWhenRedisIsNotReachable() {
        LettuceConnectionFactory dead = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("localhost", 1),   // nothing listens on port 1
                LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(300)).build());
        dead.afterPropertiesSet();
        StringRedisTemplate template = new StringRedisTemplate(dead);
        template.afterPropertiesSet();
        RedisQuoteCache unreachable = new RedisQuoteCache(template);

        assertThat(unreachable.get("simulated", "AAPL")).isEmpty();   // a miss, not an exception
        unreachable.put("simulated", QUOTE, Duration.ofSeconds(5));   // swallowed

        dead.destroy();
    }
}
