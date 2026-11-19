package com.cryptopilot.market.client.fallback;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads OKX's public market API for the display-only fallback prices of a market whose exchange data is lost (D-158).
 *
 * <p>Two GETs, no key: {@code /api/v5/market/tickers?instType=SPOT|SWAP}, every ticker of a market in one answer, so a
 * poll costs one request per market whatever the number of pairs; and {@code /api/v5/market/candles}, the latest
 * one-minute candles of one instrument. Every call is bounded as a whole by {@code read-timeout}. No retry here: the
 * next poll reads again. OKX answers {@code "code": "0"} on success; any other code is a failure.
 *
 * <p>Reference: OKX API v5, "Market Data: Get tickers" and "Get candlesticks", www.okx.com/docs-v5. Nygard, M. T.
 * (2018). <i>Release It!</i> (2nd ed.). Pragmatic Bookshelf ("Timeouts").
 */
public class OkxClient implements AutoCloseable {

    private final OkxProperties properties;
    private final JsonMapper json;
    private final HttpClient http;

    public OkxClient(OkxProperties properties, JsonMapper json) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.json = Objects.requireNonNull(json, "json must not be null");
        this.http = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Every Spot ticker; one without an instrument or a last price is left out.
     *
     * @throws OkxException when they could not be read
     */
    public List<OkxTicker> spotTickers() {
        return tickers("SPOT");
    }

    /**
     * Every perpetual swap ticker, the Futures of the exchange; one without an instrument or a last price is left out.
     *
     * @throws OkxException when they could not be read
     */
    public List<OkxTicker> swapTickers() {
        return tickers("SWAP");
    }

    private List<OkxTicker> tickers(String type) {
        List<OkxTicker> tickers = new ArrayList<>();
        for (JsonNode node : data("/api/v5/market/tickers?instType=" + type, "tickers")) {
            String instrument = text(node, "instId");
            BigDecimal last = decimal(node, "last");
            if (instrument == null || last == null) {
                continue;
            }
            long at = node.path("ts").asLong(0);
            tickers.add(new OkxTicker(
                    instrument,
                    last,
                    decimal(node, "open24h"),
                    decimal(node, "high24h"),
                    decimal(node, "low24h"),
                    decimal(node, "vol24h"),
                    at > 0 ? Instant.ofEpochMilli(at) : null));
        }
        return tickers;
    }

    /**
     * The latest one-minute candles of an instrument, oldest first, at most {@code limit} (1 to 300).
     *
     * @throws OkxException when they could not be read
     */
    public List<OkxCandle> minuteCandles(String instrumentId, int limit) {
        if (limit < 1 || limit > 300) {
            throw new IllegalArgumentException("limit must be 1 to 300, not " + limit);
        }
        String path = "/api/v5/market/candles?bar=1m&limit=" + limit + "&instId="
                + URLEncoder.encode(instrumentId, StandardCharsets.UTF_8);
        List<OkxCandle> candles = new ArrayList<>();
        for (JsonNode row : data(path, "candles")) {
            if (!row.isArray() || row.size() < 6) {
                throw new OkxException("OKX answered a candle that is not a row of values", 0, null);
            }
            candles.addFirst(new OkxCandle(
                    Instant.ofEpochMilli(row.get(0).asLong()),
                    new BigDecimal(row.get(1).asString()),
                    new BigDecimal(row.get(2).asString()),
                    new BigDecimal(row.get(3).asString()),
                    new BigDecimal(row.get(4).asString()),
                    new BigDecimal(row.get(5).asString()),
                    row.size() > 8 && "1".equals(row.get(8).asString())));
        }
        return candles;
    }

    @Override
    public void close() {
        http.close();
    }

    /** The {@code data} array of a successful answer. */
    private JsonNode data(String path, String what) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(properties.baseUrl() + path))
                .timeout(properties.readTimeout())
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<byte[]> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new OkxException("OKX " + what + " not read: " + e.getMessage(), 0, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OkxException("OKX " + what + " not read: interrupted", 0, e);
        }
        if (response.statusCode() != 200) {
            throw new OkxException(
                    "OKX answered " + response.statusCode() + " for " + what, response.statusCode(), null);
        }
        try {
            JsonNode body = json.readTree(response.body());
            if (!"0".equals(body.path("code").asString(""))) {
                throw new OkxException(
                        "OKX refused the " + what + ": code "
                                + body.path("code").asString("?") + " "
                                + body.path("msg").asString(""),
                        200,
                        null);
            }
            JsonNode data = body.path("data");
            if (!data.isArray()) {
                throw new OkxException("OKX answered " + what + " without a data list", 200, null);
            }
            return data;
        } catch (JacksonException | NumberFormatException e) {
            throw new OkxException("OKX " + what + " could not be read: " + e.getMessage(), 0, e);
        }
    }

    private static String text(JsonNode node, String field) {
        String value = node.path(field).asString("");
        return value.isBlank() ? null : value;
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
