package com.portfolio.trading.market;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Quotes in Redis, one key per provider and symbol, e.g. {@code quote:simulated:AAPL}, expiring after a short TTL.
 * The value is a plain delimited string (price|currency|asOf|source) so it can be read with redis-cli and needs no
 * serialization library.
 *
 * Fails open: if Redis is down, slow or returns something unreadable, the call behaves like a cache miss and the
 * caller goes to the real provider. Trading must keep working without the cache.
 */
public class RedisQuoteCache implements QuoteCache {

    private static final Logger log = LoggerFactory.getLogger(RedisQuoteCache.class);
    private static final String SEPARATOR = "|";

    private final StringRedisTemplate redis;

    public RedisQuoteCache(StringRedisTemplate redis) {
        this.redis = redis;
    }

    static String key(String provider, String symbol) {
        return "quote:" + provider + ":" + symbol;
    }

    @Override
    public Optional<Quote> get(String provider, String symbol) {
        try {
            String raw = redis.opsForValue().get(key(provider, symbol));
            return raw == null ? Optional.empty() : Optional.of(decode(symbol, raw));
        } catch (RuntimeException e) {
            log.warn("Quote cache read failed for {}, using the provider instead: {}", symbol, e.toString());
            return Optional.empty();
        }
    }

    @Override
    public void put(String provider, Quote quote, Duration ttl) {
        try {
            redis.opsForValue().set(key(provider, quote.symbol()), encode(quote), ttl);
        } catch (RuntimeException e) {
            log.warn("Quote cache write failed for {}, continuing without it: {}", quote.symbol(), e.toString());
        }
    }

    static String encode(Quote q) {
        return q.price().toPlainString() + SEPARATOR + q.currency() + SEPARATOR + q.asOf() + SEPARATOR + q.source();
    }

    static Quote decode(String symbol, String raw) {
        String[] p = raw.split("\\|", 4);
        if (p.length != 4) {
            throw new IllegalArgumentException("Unreadable cached quote");
        }
        return new Quote(symbol, new BigDecimal(p[0]), p[1], Instant.parse(p[2]), p[3]);
    }
}
