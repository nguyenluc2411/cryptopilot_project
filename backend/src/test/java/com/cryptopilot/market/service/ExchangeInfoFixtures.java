package com.cryptopilot.market.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Exchange information snapshots for the synchronisation tests: real Binance responses of 2026-09-24,
 * trimmed to a handful of symbols. Spot holds BTCUSDT, ETHUSDT, XRPUSDT and SHIBUSDT; futures holds the
 * BTCUSDT and ETHUSDT perpetuals, the 1000SHIBUSDT perpetual and the BTCUSDT_260925 quarterly.
 *
 * <p>A test changes one field of one symbol — a status, a tick size — or removes a symbol, and serves the
 * result from the stand-in exchange, so every case starts from the same real shape.
 */
public final class ExchangeInfoFixtures {

    public static final String SPOT_PATH = "/api/v3/exchangeInfo";
    public static final String FUTURES_PATH = "/fapi/v1/exchangeInfo";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ObjectNode root;

    private ExchangeInfoFixtures(ObjectNode root) {
        this.root = root;
    }

    public static ExchangeInfoFixtures spot() {
        return load("/binance/exchange-info-spot.json");
    }

    public static ExchangeInfoFixtures futures() {
        return load("/binance/exchange-info-futures.json");
    }

    /** Changes one symbol in place. */
    public ExchangeInfoFixtures with(String symbol, Consumer<ObjectNode> change) {
        change.accept(symbolNode(symbol));
        return this;
    }

    /** Sets one filter field of one symbol, e.g. {@code PRICE_FILTER.tickSize}. */
    public ExchangeInfoFixtures filter(String symbol, String filterType, String field, String value) {
        for (JsonNode filter : symbolNode(symbol).get("filters")) {
            if (filterType.equals(filter.get("filterType").stringValue())) {
                ((ObjectNode) filter).put(field, value);
                return this;
            }
        }
        throw new IllegalArgumentException(symbol + " has no " + filterType);
    }

    /** Removes a symbol, as a delisting does. */
    public ExchangeInfoFixtures without(String symbol) {
        ArrayNode symbols = (ArrayNode) root.get("symbols");
        for (int i = 0; i < symbols.size(); i++) {
            if (symbol.equals(symbols.get(i).get("symbol").stringValue())) {
                symbols.remove(i);
                return this;
            }
        }
        throw new IllegalArgumentException("no symbol " + symbol);
    }

    public String json() {
        return JSON.writeValueAsString(root);
    }

    private ObjectNode symbolNode(String symbol) {
        for (JsonNode node : root.get("symbols")) {
            if (symbol.equals(node.get("symbol").stringValue())) {
                return (ObjectNode) node;
            }
        }
        throw new IllegalArgumentException("no symbol " + symbol);
    }

    private static ExchangeInfoFixtures load(String resource) {
        try (InputStream in = ExchangeInfoFixtures.class.getResourceAsStream(resource)) {
            return new ExchangeInfoFixtures((ObjectNode) JSON.readTree(in));
        } catch (IOException missing) {
            throw new UncheckedIOException(missing);
        }
    }
}
