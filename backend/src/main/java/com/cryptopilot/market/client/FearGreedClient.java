package com.cryptopilot.market.client;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the latest Crypto Fear &amp; Greed Index from alternative.me: {@code GET /fng/?limit=1}. One call, no retry; the
 * index changes once a day and the market page shows the last one read. The whole call, body included, is bounded by
 * {@code read-timeout}. Not final, so the system log can watch it
 * (UC-52).
 *
 * <p>Reference: Alternative.me, "Crypto Fear &amp; Greed Index" API, alternative.me/crypto/fear-and-greed-index.
 */
public class FearGreedClient implements AutoCloseable {

    private final FearGreedProperties properties;
    private final JsonMapper json;
    private final HttpClient http;

    public FearGreedClient(FearGreedProperties properties, JsonMapper json) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.json = Objects.requireNonNull(json, "json must not be null");
        this.http = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Today's reading.
     *
     * @throws FearGreedException when it could not be read
     */
    public FearGreedReading latest() {
        String base = properties.baseUrl().toString().replaceAll("/+$", "");
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/fng/?limit=1"))
                .timeout(properties.readTimeout())
                .header("Accept", "application/json")
                .GET()
                .build();
        // The request timeout stops at the headers; the deadline bounds the body too.
        CompletableFuture<HttpResponse<String>> call = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> response;
        try {
            response = call.get(properties.readTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            call.cancel(true);
            throw new FearGreedException(
                    "the Fear & Greed Index did not finish within " + properties.readTimeout(), 0, e);
        } catch (ExecutionException e) {
            throw new FearGreedException("the Fear & Greed Index did not answer: " + e.getCause(), 0, e.getCause());
        } catch (InterruptedException e) {
            call.cancel(true);
            Thread.currentThread().interrupt();
            throw new FearGreedException("interrupted while waiting for the Fear & Greed Index", 0, e);
        }
        if (response.statusCode() != 200) {
            throw new FearGreedException(
                    "the Fear & Greed Index answered HTTP " + response.statusCode(), response.statusCode(), null);
        }
        try {
            JsonNode first = json.readTree(response.body()).path("data").path(0);
            int value = Integer.parseInt(first.path("value").asString(""));
            long timestamp = Long.parseLong(first.path("timestamp").asString(""));
            String label = first.path("value_classification").asString("");
            if (value < 0 || value > 100 || label.isBlank()) {
                throw new FearGreedException("the Fear & Greed Index answered a value out of range", 0, null);
            }
            return new FearGreedReading(value, label.strip(), Instant.ofEpochSecond(timestamp));
        } catch (JacksonException | NumberFormatException e) {
            throw new FearGreedException("the Fear & Greed Index answered something unreadable", 0, e);
        }
    }

    @Override
    public void close() {
        http.close();
    }
}
