package com.portfolio.trading.market;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Deterministic fake market: a seeded random walk per symbol, with no network and no state.
 *
 * Daily closes come from a path that starts on a fixed anchor date and moves only on weekdays, so
 * history and quotes are consistent with each other and identical for the same seed on every run.
 * A quote adds a small per-minute wobble on top of the day's close.
 *
 * Optional failure injection (latency and a failure probability) lets circuit-breaker and fallback
 * behaviour be exercised without a real outage.
 *
 * Starting prices are illustrative round numbers, not real quotes.
 */
public class SimulatedMarketDataProvider implements MarketDataProvider {

    static final LocalDate ANCHOR = LocalDate.of(2020, 1, 1);
    private static final double DAILY_DRIFT = 0.0003;
    private static final double INTRADAY_NOISE = 0.002;

    private static final Map<String, Double> ANCHOR_PRICES = Map.ofEntries(
            Map.entry("AAPL", 75.0), Map.entry("MSFT", 160.0), Map.entry("GOOGL", 68.0),
            Map.entry("AMZN", 94.0), Map.entry("TSLA", 28.0), Map.entry("NVDA", 59.0),
            Map.entry("META", 210.0), Map.entry("JPM", 140.0), Map.entry("V", 190.0),
            Map.entry("KO", 60.0), Map.entry("DIS", 145.0), Map.entry("NFLX", 320.0));

    private final long seed;
    private final Clock clock;
    private final long latencyMillis;
    private final double failureRate;

    public SimulatedMarketDataProvider(long seed, Clock clock, long latencyMillis, double failureRate) {
        if (latencyMillis < 0) {
            throw new IllegalArgumentException("latencyMillis must not be negative");
        }
        if (failureRate < 0 || failureRate > 1) {
            throw new IllegalArgumentException("failureRate must be between 0 and 1");
        }
        this.seed = seed;
        this.clock = clock;
        this.latencyMillis = latencyMillis;
        this.failureRate = failureRate;
    }

    public SimulatedMarketDataProvider(long seed, Clock clock) {
        this(seed, clock, 0, 0.0);
    }

    @Override
    public String name() {
        return "simulated";
    }

    @Override
    public Quote getQuote(String symbol) {
        String s = Symbols.normalize(symbol);
        simulateNetwork(s);
        LocalDate today = LocalDate.now(clock);
        double[] path = path(s, today);
        double dayClose = path[path.length - 1];
        long minute = clock.instant().getEpochSecond() / 60;
        double noise = new Random(streamSeed(s) * 31 + minute).nextGaussian() * INTRADAY_NOISE;
        double price = dayClose * (1 + noise);
        return new Quote(s, toMoney(price), Quote.DEFAULT_CURRENCY, clock.instant(), name());
    }

    @Override
    public List<PricePoint> getDailyHistory(String symbol, LocalDate from, LocalDate to) {
        String s = Symbols.normalize(symbol);
        if (from == null || to == null || from.isAfter(to)) {
            throw new IllegalArgumentException("from must not be after to");
        }
        simulateNetwork(s);
        LocalDate last = to.isAfter(LocalDate.now(clock)) ? LocalDate.now(clock) : to;
        LocalDate first = from.isBefore(ANCHOR) ? ANCHOR : from;
        if (last.isBefore(first)) {
            return List.of();
        }
        double[] path = path(s, last);
        List<PricePoint> points = new ArrayList<>();
        for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(1)) {
            if (isWeekday(d)) {
                int index = (int) ChronoUnit.DAYS.between(ANCHOR, d);
                points.add(new PricePoint(s, d, toMoney(path[index]), name()));
            }
        }
        return points;
    }

    /** Closing prices for every day from the anchor to {@code end}, inclusive. Weekends repeat Friday. */
    private double[] path(String symbol, LocalDate end) {
        if (end.isBefore(ANCHOR)) {
            throw new IllegalArgumentException("No simulated data before " + ANCHOR);
        }
        int days = (int) ChronoUnit.DAYS.between(ANCHOR, end) + 1;
        double[] path = new double[days];
        Random random = new Random(streamSeed(symbol));
        double vol = 0.012 + Math.floorMod(symbol.hashCode(), 10) * 0.002;
        double price = anchorPrice(symbol);
        for (int i = 0; i < days; i++) {
            if (isWeekday(ANCHOR.plusDays(i))) {
                price *= Math.exp(DAILY_DRIFT + vol * random.nextGaussian());
            }
            path[i] = price;
        }
        return path;
    }

    private long streamSeed(String symbol) {
        return seed * 31 + symbol.hashCode();
    }

    private static double anchorPrice(String symbol) {
        Double known = ANCHOR_PRICES.get(symbol);
        return known != null ? known : 20 + Math.floorMod(symbol.hashCode(), 280);
    }

    private static boolean isWeekday(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY;
    }

    private static BigDecimal toMoney(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_EVEN);
    }

    private void simulateNetwork(String symbol) {
        if (latencyMillis > 0) {
            try {
                Thread.sleep(latencyMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new MarketDataException("Interrupted while simulating latency");
            }
        }
        if (failureRate > 0 && ThreadLocalRandom.current().nextDouble() < failureRate) {
            throw new MarketDataException("Simulated provider failure for " + symbol);
        }
    }
}
