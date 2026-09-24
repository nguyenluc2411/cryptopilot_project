package com.cryptopilot.market.client;

import java.time.Instant;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads one text frame of a combined stream, {@code {"stream":"<name>","data":<payload>}}, into a
 * {@link StreamMessage}.
 *
 * <p>The payload's event type decides the reading: {@code kline}, {@code 24hrTicker} and
 * {@code markPriceUpdate}. Any other event — a {@code serverShutdown} notice, a subscription answer, a type
 * the exchange adds later — is not an error and yields nothing; the connection's own handling covers a
 * shutdown. A message of a known type that is not the documented shape (a decimal that is not a string, a
 * missing field, an interval this system never asked for) is {@link IllegalArgumentException}: one bad frame
 * is logged by the caller and skipped, it never ends the connection.
 *
 * <p>The field letters are the exchange's, verified against the live streams on 2026-09-24 (TECHNICAL_DESIGN
 * 7.1.1). Decimals go from the string to {@code BigDecimal} without a {@code double}; times and counts are
 * JSON integers.
 *
 * <p>Rule: NSF-03; BR-08; TECHNICAL_DESIGN 5.4 and 7.1.
 *
 * <p>Reference: Binance. <i>WebSocket Streams for Binance</i> (Spot), "Kline/Candlestick Streams" and
 * "Individual Symbol Ticker Streams"; <i>USDⓈ-M Futures</i>, "Kline/Candlestick Streams" and "Mark Price
 * Stream".
 */
public final class BinanceStreamParser {

    private final JsonMapper json;

    public BinanceStreamParser(JsonMapper json) {
        this.json = json;
    }

    /**
     * The message this frame carries, or empty for an event type NSF-03 does not read.
     *
     * @throws IllegalArgumentException when the frame is not a combined-stream message of the documented shape
     */
    public Optional<StreamMessage> parse(String frame) {
        JsonNode data;
        try {
            data = json.readTree(frame).required("data");
        } catch (RuntimeException notJson) {
            throw new IllegalArgumentException("not a combined-stream message: " + notJson.getMessage(), notJson);
        }
        try {
            return switch (data.path("e").asString("")) {
                case "kline" -> Optional.of(kline(data));
                case "24hrTicker" -> Optional.of(ticker(data));
                case "markPriceUpdate" -> Optional.of(markPrice(data));
                default -> Optional.empty();
            };
        } catch (IllegalArgumentException malformed) {
            throw malformed;
        } catch (RuntimeException malformed) {
            throw new IllegalArgumentException(
                    "a " + data.path("e").asString("") + " message is not the documented shape: "
                            + malformed.getMessage(),
                    malformed);
        }
    }

    private static StreamMessage kline(JsonNode data) {
        JsonNode k = data.required("k");
        String code = k.required("i").stringValue();
        MarketInterval interval = MarketInterval.fromCode(code)
                .orElseThrow(() -> new IllegalArgumentException("unknown interval " + code));
        JsonNode closed = k.required("x");
        if (!closed.isBoolean()) {
            throw new IllegalArgumentException("expected a boolean closed flag, found " + closed);
        }
        Kline kline = new Kline(
                BinanceResponseParser.instant(k.required("t")),
                BinanceResponseParser.instant(k.required("T")),
                BinanceResponseParser.decimal(k.required("o")),
                BinanceResponseParser.decimal(k.required("h")),
                BinanceResponseParser.decimal(k.required("l")),
                BinanceResponseParser.decimal(k.required("c")),
                BinanceResponseParser.decimal(k.required("v")),
                BinanceResponseParser.decimal(k.required("q")),
                BinanceResponseParser.count(k.required("n")));
        return new StreamMessage.KlineMessage(symbol(data), interval, kline, closed.booleanValue(), eventTime(data));
    }

    private static StreamMessage ticker(JsonNode data) {
        return new StreamMessage.TickerMessage(
                symbol(data),
                BinanceResponseParser.decimal(data.required("c")),
                BinanceResponseParser.decimal(data.required("b")),
                BinanceResponseParser.decimal(data.required("a")),
                BinanceResponseParser.decimal(data.required("h")),
                BinanceResponseParser.decimal(data.required("l")),
                BinanceResponseParser.decimal(data.required("P")),
                BinanceResponseParser.decimal(data.required("v")),
                BinanceResponseParser.decimal(data.required("q")),
                eventTime(data));
    }

    private static StreamMessage markPrice(JsonNode data) {
        return new StreamMessage.MarkPriceMessage(
                symbol(data),
                BinanceResponseParser.decimal(data.required("p")),
                BinanceResponseParser.decimal(data.required("i")),
                BinanceResponseParser.decimal(data.required("r")),
                BinanceResponseParser.instant(data.required("T")),
                eventTime(data));
    }

    private static String symbol(JsonNode data) {
        return data.required("s").stringValue();
    }

    private static Instant eventTime(JsonNode data) {
        return BinanceResponseParser.instant(data.required("E"));
    }
}
