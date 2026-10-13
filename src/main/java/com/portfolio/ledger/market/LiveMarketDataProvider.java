package com.portfolio.ledger.market;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Real market data. Quotes come from Finnhub, daily history from Twelve Data.
 *
 * Error messages never include request URLs or response bodies, because the Finnhub key travels in the
 * query string and must not end up in logs. Exceptions from the HTTP client are deliberately not chained
 * for the same reason.
 */
public class LiveMarketDataProvider implements MarketDataProvider {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final String finnhubBaseUrl;
    private final String finnhubKey;
    private final String twelveDataBaseUrl;
    private final String twelveDataKey;

    public LiveMarketDataProvider(HttpClient http, ObjectMapper mapper,
                                  String finnhubBaseUrl, String finnhubKey,
                                  String twelveDataBaseUrl, String twelveDataKey) {
        this.http = http;
        this.mapper = mapper;
        this.finnhubBaseUrl = stripTrailingSlash(finnhubBaseUrl);
        this.finnhubKey = finnhubKey;
        this.twelveDataBaseUrl = stripTrailingSlash(twelveDataBaseUrl);
        this.twelveDataKey = twelveDataKey;
    }

    @Override
    public String name() {
        return "live";
    }

    @Override
    public Quote getQuote(String symbol) {
        String s = Symbols.normalize(symbol);
        String url = finnhubBaseUrl + "/quote?symbol=" + encode(s) + "&token=" + encode(finnhubKey);
        JsonNode root = fetch(HttpRequest.newBuilder(URI.create(url)), "Finnhub");

        // Finnhub answers unknown symbols with zeros rather than an error.
        BigDecimal price = decimal(root.path("c"));
        long epochSeconds = root.path("t").asLong(0);
        if (price == null || price.signum() <= 0 || epochSeconds <= 0) {
            throw new MarketDataException("No quote available for " + s + " from Finnhub");
        }
        return new Quote(s, price, Quote.DEFAULT_CURRENCY, Instant.ofEpochSecond(epochSeconds), "finnhub");
    }

    @Override
    public List<PricePoint> getDailyHistory(String symbol, LocalDate from, LocalDate to) {
        String s = Symbols.normalize(symbol);
        if (from == null || to == null || from.isAfter(to)) {
            throw new IllegalArgumentException("from must not be after to");
        }
        String url = twelveDataBaseUrl + "/time_series?symbol=" + encode(s)
                + "&interval=1day&order=asc&outputsize=5000"
                + "&start_date=" + from + "&end_date=" + to;
        JsonNode root = fetch(HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "apikey " + twelveDataKey), "Twelve Data");

        if ("error".equalsIgnoreCase(root.path("status").asText())) {
            throw new MarketDataException("Twelve Data error for " + s + ": " + root.path("message").asText("unknown"));
        }
        List<PricePoint> points = new ArrayList<>();
        for (JsonNode row : root.path("values")) {
            String datetime = row.path("datetime").asText("");
            BigDecimal close = decimal(row.path("close"));
            if (datetime.length() < 10 || close == null || close.signum() <= 0) {
                continue;
            }
            LocalDate date = LocalDate.parse(datetime.substring(0, 10));
            if (!date.isBefore(from) && !date.isAfter(to)) {
                points.add(new PricePoint(s, date, close, "twelvedata"));
            }
        }
        points.sort(Comparator.comparing(PricePoint::date));
        return points;
    }

    private JsonNode fetch(HttpRequest.Builder builder, String vendor) {
        HttpRequest request = builder.timeout(TIMEOUT).header("Accept", "application/json").GET().build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new MarketDataException(vendor + " request failed (" + e.getClass().getSimpleName() + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MarketDataException(vendor + " request interrupted");
        }
        int status = response.statusCode();
        if (status == 429) {
            throw new MarketDataException(vendor + " rate limit reached (HTTP 429)");
        }
        if (status / 100 != 2) {
            throw new MarketDataException(vendor + " returned HTTP " + status);
        }
        try {
            return mapper.readTree(response.body());
        } catch (JsonProcessingException e) {
            throw new MarketDataException(vendor + " returned invalid JSON");
        }
    }

    private static BigDecimal decimal(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        try {
            return new BigDecimal(node.asText());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
