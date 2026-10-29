package com.cryptopilot.market.client;

import com.cryptopilot.market.model.enums.MarketInterval;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads one text frame of a combined stream, {@code {"stream":"<name>","data":<payload>}}, into a
 * {@link StreamMessage}.
 *
 * <p>The payload's event type decides the reading: {@code kline}, {@code 24hrTicker}, {@code markPriceUpdate} and
 * {@code aggTrade}. The Spot {@code bookTicker} and partial {@code depth} payloads carry no event type, so the stream
 * name decides those: {@code <symbol>@bookTicker} and {@code <symbol>@depth<levels>[@100ms]}; they carry no event
 * time either, and the instant this server read them stands for it (TR-03). Their Futures counterparts carry an event
 * type, {@code bookTicker} and {@code depthUpdate}, and the depth's levels under {@code b} and {@code a} with its last
 * update id under {@code u} (TR-38, read from the live streams on 2026-10-29); they are stamped with the read instant
 * too, as the age of a book is judged by when it reached this server. Any other event — a {@code serverShutdown} notice, a subscription answer, a type
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
 * "Individual Symbol Ticker Streams", "Aggregate Trade Streams", "Individual Symbol Book Ticker Streams" and "Partial
 * Book Depth Streams"; <i>USDⓈ-M Futures</i>, "Kline/Candlestick Streams", "Mark Price Stream", "Aggregate Trade
 * Streams", "Individual Symbol Book Ticker Streams" and "Partial Book Depth Streams".
 */
public final class BinanceStreamParser {

    private final JsonMapper json;
    private final Clock clock;

    /** A parser that stamps the payloads without an event time with the system clock. */
    public BinanceStreamParser(JsonMapper json) {
        this(json, Clock.systemUTC());
    }

    /**
     * @param clock stamps the {@code bookTicker} and {@code depth} payloads, which carry no event time
     */
    public BinanceStreamParser(JsonMapper json, Clock clock) {
        this.json = json;
        this.clock = clock;
    }

    /**
     * The message this frame carries, or empty for an event type NSF-03 does not read.
     *
     * @throws IllegalArgumentException when the frame is not a combined-stream message of the documented shape
     */
    public Optional<StreamMessage> parse(String frame) {
        JsonNode root;
        JsonNode data;
        try {
            root = json.readTree(frame);
            data = root.required("data");
        } catch (RuntimeException notJson) {
            throw new IllegalArgumentException("not a combined-stream message: " + notJson.getMessage(), notJson);
        }
        try {
            return switch (data.path("e").asString("")) {
                case "kline" -> Optional.of(kline(data));
                case "24hrTicker" -> Optional.of(ticker(data));
                case "markPriceUpdate" -> Optional.of(markPrice(data));
                case "aggTrade" -> Optional.of(aggTrade(data));
                case "bookTicker" -> Optional.of(futuresBookTicker(data));
                case "depthUpdate" -> Optional.of(futuresDepth(data));
                case "" -> byStreamName(root.path("stream").asString(""), data);
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

    /** The payloads without an event type, read by the name of the stream that carried them. */
    private Optional<StreamMessage> byStreamName(String stream, JsonNode data) {
        int at = stream.indexOf('@');
        if (at <= 0) {
            return Optional.empty();
        }
        String symbol = stream.substring(0, at).toUpperCase(Locale.ROOT);
        String kind = stream.substring(at + 1);
        if (kind.equals("bookTicker")) {
            return Optional.of(bookTicker(data));
        }
        if (kind.startsWith("depth")) {
            return Optional.of(depth(symbol, data));
        }
        return Optional.empty();
    }

    private static StreamMessage aggTrade(JsonNode data) {
        JsonNode buyerMaker = data.required("m");
        if (!buyerMaker.isBoolean()) {
            throw new IllegalArgumentException("expected a boolean buyer-maker flag, found " + buyerMaker);
        }
        return new StreamMessage.AggTradeMessage(
                symbol(data),
                BinanceResponseParser.count(data.required("a")),
                BinanceResponseParser.decimal(data.required("p")),
                BinanceResponseParser.decimal(data.required("q")),
                BinanceResponseParser.instant(data.required("T")),
                buyerMaker.booleanValue(),
                eventTime(data));
    }

    private StreamMessage bookTicker(JsonNode data) {
        return new StreamMessage.BookTickerMessage(
                symbol(data),
                BinanceResponseParser.count(data.required("u")),
                BinanceResponseParser.decimal(data.required("b")),
                BinanceResponseParser.decimal(data.required("B")),
                BinanceResponseParser.decimal(data.required("a")),
                BinanceResponseParser.decimal(data.required("A")),
                clock.instant());
    }

    private StreamMessage depth(String symbol, JsonNode data) {
        return new StreamMessage.DepthMessage(
                symbol,
                BinanceResponseParser.count(data.required("lastUpdateId")),
                levels(data.required("bids")),
                levels(data.required("asks")),
                clock.instant());
    }

    private StreamMessage futuresBookTicker(JsonNode data) {
        return new StreamMessage.BookTickerMessage(
                symbol(data),
                BinanceResponseParser.count(data.required("u")),
                BinanceResponseParser.decimal(data.required("b")),
                BinanceResponseParser.decimal(data.required("B")),
                BinanceResponseParser.decimal(data.required("a")),
                BinanceResponseParser.decimal(data.required("A")),
                clock.instant());
    }

    private StreamMessage futuresDepth(JsonNode data) {
        return new StreamMessage.DepthMessage(
                symbol(data),
                BinanceResponseParser.count(data.required("u")),
                levels(data.required("b")),
                levels(data.required("a")),
                clock.instant());
    }

    private static List<StreamMessage.Level> levels(JsonNode side) {
        if (!side.isArray()) {
            throw new IllegalArgumentException("expected an array of price levels, found " + side);
        }
        List<StreamMessage.Level> levels = new ArrayList<>(side.size());
        for (JsonNode level : side) {
            levels.add(new StreamMessage.Level(
                    BinanceResponseParser.decimal(level.required(0)),
                    BinanceResponseParser.decimal(level.required(1))));
        }
        return levels;
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
