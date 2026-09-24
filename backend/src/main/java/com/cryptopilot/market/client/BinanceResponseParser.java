package com.cryptopilot.market.client;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns the exchange's JSON into the client's own types, and is the only class that knows what that
 * JSON looks like.
 *
 * <h2>Decimals never pass through a double</h2>
 *
 * <p>The exchange sends every price, quantity, rate, ratio and open interest as a JSON <em>string</em>,
 * precisely so that a client can keep every digit. Each one is read with {@code stringValue()} — which
 * refuses a node that is not a string — and handed to {@code new BigDecimal(String)}. A JSON number in a
 * decimal field is a malformed response, not something to coerce: coercing it would read it through a
 * {@code double} and lose exactly what the string was there to keep (ADR-008).
 *
 * <p>The string-only rule is for decimals and nothing else. The fields the documentation sends as JSON
 * numbers are read as numbers and must be integral: the kline open and close times and trade count
 * (array positions 0, 6 and 8), {@code nextFundingTime} and {@code time} of the premium index,
 * {@code fundingTime}, {@code time} of open interest and {@code timestamp} of the long/short ratio. Numeric
 * fields the system does not read — {@code serverTime}, {@code baseAssetPrecision}, {@code maxNumOrders}
 * and the like — are not looked at at all, whatever their type (TECHNICAL_DESIGN 7.1.1 cites the pages).
 *
 * <h2>A response is read whole or refused</h2>
 *
 * <p>A missing field, a field of the wrong type or a body that is not JSON makes the whole response
 * {@link BinanceClientException.Kind#MALFORMED}. A half-read exchange information response would drop
 * the symbols after the bad one, and a half-read candle page would leave a gap nobody detects.
 *
 * <p>Rule: BR-09; TECHNICAL_DESIGN 5.4 and 7.1.2; ADR-008.
 *
 * <p>Reference: Evans, E. (2003). <i>Domain-Driven Design</i>. Addison-Wesley, ch. 14 ("Anticorruption
 * Layer": translate another system's model at the boundary so that it never leaks into your own).
 */
final class BinanceResponseParser {

    private final JsonMapper json;

    BinanceResponseParser(JsonMapper json) {
        this.json = json;
    }

    /** {@code exchangeInfo}, Spot or futures: one {@link ExchangeSymbol} per entry of {@code symbols}. */
    List<ExchangeSymbol> exchangeSymbols(BinanceVenue venue, String body) {
        return parse(venue, body, root -> {
            List<ExchangeSymbol> symbols = new ArrayList<>();
            for (JsonNode symbol : array(root.required("symbols"))) {
                symbols.add(exchangeSymbol(symbol));
            }
            return symbols;
        });
    }

    /** {@code klines}, Spot or futures: an array of arrays, in the documented field order. */
    List<Kline> klines(BinanceVenue venue, String body) {
        return parse(venue, body, root -> {
            List<Kline> klines = new ArrayList<>();
            for (JsonNode row : array(root)) {
                klines.add(new Kline(
                        instant(row.required(0)),
                        instant(row.required(6)),
                        decimal(row.required(1)),
                        decimal(row.required(2)),
                        decimal(row.required(3)),
                        decimal(row.required(4)),
                        decimal(row.required(5)),
                        decimal(row.required(7)),
                        count(row.required(8))));
            }
            return klines;
        });
    }

    /** {@code premiumIndex} for one symbol: a single object. */
    PremiumIndex premiumIndex(String body) {
        return parse(
                BinanceVenue.USD_M_FUTURES,
                body,
                root -> new PremiumIndex(
                        root.required("symbol").stringValue(),
                        decimal(root.required("markPrice")),
                        decimal(root.required("indexPrice")),
                        decimal(root.required("lastFundingRate")),
                        instant(root.required("nextFundingTime")),
                        instant(root.required("time"))));
    }

    /** {@code fundingRate}: settled rates, oldest first as the exchange orders them. */
    List<FundingRate> fundingRates(String body) {
        return parse(BinanceVenue.USD_M_FUTURES, body, root -> {
            List<FundingRate> rates = new ArrayList<>();
            for (JsonNode rate : array(root)) {
                rates.add(new FundingRate(
                        rate.required("symbol").stringValue(),
                        instant(rate.required("fundingTime")),
                        decimal(rate.required("fundingRate")),
                        optionalDecimal(rate.get("markPrice"))));
            }
            return rates;
        });
    }

    /** {@code openInterest} for one symbol. */
    OpenInterest openInterest(String body) {
        return parse(
                BinanceVenue.USD_M_FUTURES,
                body,
                root -> new OpenInterest(
                        root.required("symbol").stringValue(),
                        decimal(root.required("openInterest")),
                        instant(root.required("time"))));
    }

    /** {@code globalLongShortAccountRatio}: one entry per period. */
    List<LongShortRatio> longShortRatios(String body) {
        return parse(BinanceVenue.USD_M_FUTURES, body, root -> {
            List<LongShortRatio> ratios = new ArrayList<>();
            for (JsonNode ratio : array(root)) {
                ratios.add(new LongShortRatio(
                        ratio.required("symbol").stringValue(),
                        decimal(ratio.required("longShortRatio")),
                        decimal(ratio.required("longAccount")),
                        decimal(ratio.required("shortAccount")),
                        instant(ratio.required("timestamp"))));
            }
            return ratios;
        });
    }

    /**
     * One entry of {@code symbols}. The filters are a list of objects keyed by {@code filterType}; the
     * minimum order value is {@code NOTIONAL.minNotional} or {@code MIN_NOTIONAL.minNotional} on Spot
     * and {@code MIN_NOTIONAL.notional} on futures, and whichever is present is taken.
     */
    private static ExchangeSymbol exchangeSymbol(JsonNode symbol) {
        BigDecimal tickSize = null;
        BigDecimal stepSize = null;
        BigDecimal minQuantity = null;
        BigDecimal minNotional = null;
        for (JsonNode filter : array(symbol.path("filters"))) {
            switch (filter.required("filterType").stringValue()) {
                case "PRICE_FILTER" -> tickSize = decimal(filter.required("tickSize"));
                case "LOT_SIZE" -> {
                    stepSize = decimal(filter.required("stepSize"));
                    minQuantity = decimal(filter.required("minQty"));
                }
                case "NOTIONAL", "MIN_NOTIONAL" ->
                    minNotional = filter.has("minNotional")
                            ? decimal(filter.get("minNotional"))
                            : decimal(filter.required("notional"));
                default -> {
                    // Filters the system does not use (PERCENT_PRICE, MAX_NUM_ORDERS, ...) are ignored.
                }
            }
        }
        return new ExchangeSymbol(
                symbol.required("symbol").stringValue(),
                symbol.required("status").stringValue(),
                symbol.required("baseAsset").stringValue(),
                symbol.required("quoteAsset").stringValue(),
                symbol.has("contractType") ? symbol.get("contractType").stringValue() : null,
                tickSize,
                stepSize,
                minQuantity,
                minNotional);
    }

    private <T> T parse(BinanceVenue venue, String body, Function<JsonNode, T> reader) {
        try {
            return reader.apply(json.readTree(body));
        } catch (RuntimeException malformed) {
            throw new BinanceClientException(
                    BinanceClientException.Kind.MALFORMED,
                    venue,
                    venue + " sent a body that is not the documented shape: " + malformed.getMessage(),
                    null,
                    malformed);
        }
    }

    private static Iterable<JsonNode> array(JsonNode node) {
        if (!node.isArray()) {
            throw new IllegalArgumentException("expected an array, found " + node.getNodeType());
        }
        return node.values();
    }

    /** A decimal the exchange sent as a string, read without passing through a double. */
    static BigDecimal decimal(JsonNode node) {
        return new BigDecimal(node.stringValue());
    }

    private static BigDecimal optionalDecimal(JsonNode node) {
        return node == null || node.isNull() || node.stringValue().isEmpty() ? null : decimal(node);
    }

    /** A count the exchange sends as a JSON integer, such as the number of trades of a candle. */
    static long count(JsonNode integer) {
        if (!integer.isIntegralNumber()) {
            throw new IllegalArgumentException("expected an integer, found " + integer);
        }
        return integer.longValue();
    }

    static Instant instant(JsonNode epochMillis) {
        if (!epochMillis.isIntegralNumber()) {
            throw new IllegalArgumentException("expected epoch milliseconds, found " + epochMillis);
        }
        return Instant.ofEpochMilli(epochMillis.longValue());
    }
}
