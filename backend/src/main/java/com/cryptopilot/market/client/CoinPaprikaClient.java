package com.cryptopilot.market.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads CoinPaprika for the names and logos of the coins Binance lists without them.
 *
 * <p>Four GETs: {@code /coins}, every coin with its ticker, name and rank in one answer of several megabytes, and
 * {@code /tickers}, the ranked coins with their supply, both read as a stream one coin at a time so neither is held
 * whole; {@code /coins/{id}}, one coin with its logo, which the list does not carry; and {@code /global}, the whole
 * market's cap. No retry here: the coins are described again after the next symbol synchronisation. Not final, so
 * the system log can watch it (UC-52).
 *
 * <p>Reference: CoinPaprika API v1, "List coins" and "Get coin by ID", api.coinpaprika.com.
 */
public class CoinPaprikaClient implements AutoCloseable {

    private final CoinPaprikaProperties properties;
    private final JsonMapper json;
    private final HttpClient http;

    public CoinPaprikaClient(CoinPaprikaProperties properties, JsonMapper json) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.json = Objects.requireNonNull(json, "json must not be null");
        this.http = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Every coin CoinPaprika lists; one without an id, a ticker or a name is left out.
     *
     * @throws CoinPaprikaException when the list could not be read
     */
    public List<CoinPaprikaCoin> coins() {
        List<CoinPaprikaCoin> coins = new ArrayList<>();
        stream("/coins", "coin list", node -> {
            String id = text(node, "id");
            String symbol = text(node, "symbol");
            String name = text(node, "name");
            if (id != null && symbol != null && name != null) {
                coins.add(new CoinPaprikaCoin(
                        id,
                        symbol.toUpperCase(Locale.ROOT),
                        name,
                        node.path("rank").asInt(0),
                        node.path("is_active").asBoolean(false)));
            }
        });
        return coins;
    }

    /**
     * The ranked coins with their supply, the largest first; one without an id, a ticker or a rank is left out. The
     * circulating supply is CoinPaprika's own when it gives one, else its market cap over its price, which is how it
     * computes the market cap.
     *
     * @throws CoinPaprikaException when the list could not be read
     */
    public List<CoinPaprikaTicker> tickers() {
        List<CoinPaprikaTicker> tickers = new ArrayList<>();
        stream("/tickers", "ticker list", node -> {
            String id = text(node, "id");
            String symbol = text(node, "symbol");
            int rank = node.path("rank").asInt(0);
            if (id == null || symbol == null || rank <= 0) {
                return;
            }
            tickers.add(new CoinPaprikaTicker(id, symbol.toUpperCase(Locale.ROOT), rank, supply(node)));
        });
        return tickers;
    }

    /**
     * The whole market: its cap, volume and Bitcoin's share.
     *
     * @throws CoinPaprikaException when it could not be read
     */
    public CoinPaprikaGlobal global() {
        HttpResponse<InputStream> response = get("/global");
        try (InputStream body = response.body()) {
            JsonNode node = json.readTree(body);
            BigDecimal cap = decimal(node, "market_cap_usd");
            if (cap == null) {
                throw new CoinPaprikaException("CoinPaprika's global figures carry no market cap", 0, null);
            }
            long updated = node.path("last_updated").asLong(0);
            return new CoinPaprikaGlobal(
                    cap,
                    decimal(node, "market_cap_change_24h"),
                    decimal(node, "volume_24h_usd"),
                    decimal(node, "bitcoin_dominance_percentage"),
                    updated > 0 ? Instant.ofEpochSecond(updated) : null);
        } catch (IOException | JacksonException e) {
            throw new CoinPaprikaException("CoinPaprika's global figures could not be read: " + e.getMessage(), 0, e);
        }
    }

    /** Reads a JSON array one element at a time, never holding it whole. */
    private void stream(String path, String what, Consumer<JsonNode> each) {
        HttpResponse<InputStream> response = get(path);
        ObjectReader reader = json.readerFor(JsonNode.class).without(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        try (InputStream body = response.body();
                JsonParser parser = reader.createParser(body)) {
            if (parser.nextToken() != JsonToken.START_ARRAY) {
                throw new CoinPaprikaException("CoinPaprika answered something that is not a " + what, 0, null);
            }
            while (parser.nextToken() == JsonToken.START_OBJECT) {
                each.accept(reader.readValue(parser));
            }
        } catch (IOException | JacksonException e) {
            throw new CoinPaprikaException("CoinPaprika's " + what + " could not be read: " + e.getMessage(), 0, e);
        }
    }

    private static BigDecimal supply(JsonNode node) {
        BigDecimal circulating = decimal(node, "circulating_supply");
        if (circulating != null && circulating.signum() > 0) {
            return circulating;
        }
        JsonNode usd = node.path("quotes").path("USD");
        BigDecimal cap = decimal(usd, "market_cap");
        BigDecimal price = decimal(usd, "price");
        if (cap != null && price != null && cap.signum() > 0 && price.signum() > 0) {
            return cap.divide(price, 0, RoundingMode.HALF_UP);
        }
        BigDecimal total = decimal(node, "total_supply");
        return total != null && total.signum() > 0 ? total : null;
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isNumber()) {
            return null;
        }
        return value.decimalValue();
    }

    /**
     * The logo of one coin, an https address, or empty when it has none.
     *
     * @throws CoinPaprikaException when the coin could not be read
     */
    public Optional<String> logo(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        HttpResponse<InputStream> response = get("/coins/" + URLEncoder.encode(id.strip(), StandardCharsets.UTF_8));
        try (InputStream body = response.body()) {
            JsonNode node = json.readTree(body);
            String logo = node == null ? null : text(node, "logo");
            return logo != null && logo.startsWith("https://") ? Optional.of(logo) : Optional.empty();
        } catch (IOException | JacksonException e) {
            throw new CoinPaprikaException("CoinPaprika's coin " + id + " could not be read: " + e.getMessage(), 0, e);
        }
    }

    private HttpResponse<InputStream> get(String path) {
        String base = properties.baseUrl().toString().replaceAll("/+$", "");
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(properties.readTimeout())
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<InputStream> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new CoinPaprikaException("CoinPaprika did not answer: " + e.getMessage(), 0, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CoinPaprikaException("interrupted while waiting for CoinPaprika", 0, e);
        }
        if (response.statusCode() != 200) {
            discard(response);
            throw new CoinPaprikaException(
                    "CoinPaprika answered HTTP " + response.statusCode() + " for " + path, response.statusCode(), null);
        }
        return response;
    }

    private static void discard(HttpResponse<InputStream> response) {
        try (InputStream body = response.body()) {
            body.transferTo(OutputStream.nullOutputStream());
        } catch (IOException ignored) {
            // The status is the answer; the body does not matter.
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString()) {
            return null;
        }
        String text = value.asString().strip();
        return text.isEmpty() ? null : text;
    }

    @Override
    public void close() {
        http.close();
    }
}
