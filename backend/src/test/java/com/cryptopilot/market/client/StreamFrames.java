package com.cryptopilot.market.client;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/**
 * Stream frames shaped exactly as the exchange sent them on 2026-09-24: the first two are verbatim captures
 * of the Spot and futures streams, the builders fill the same shapes with a test's own values.
 */
public final class StreamFrames {

    /** A closed Spot 1m candle, captured from {@code stream.binance.com}. */
    public static final String SPOT_KLINE_CAPTURED = "{\"stream\":\"btcusdt@kline_1m\",\"data\":{\"e\":\"kline\","
            + "\"E\":1790259540026,\"s\":\"BTCUSDT\",\"k\":{\"t\":1790259480000,\"T\":1790259539999,\"s\":\"BTCUSDT\","
            + "\"i\":\"1m\",\"f\":6710335076,\"L\":6710338274,\"o\":\"84281.32000000\",\"c\":\"84282.00000000\","
            + "\"h\":\"84290.00000000\",\"l\":\"84252.00000000\",\"v\":\"11.30572000\",\"n\":3199,\"x\":true,"
            + "\"q\":\"952813.20737560\",\"V\":\"2.83796000\",\"Q\":\"239164.57652920\",\"B\":\"0\"}}}";

    /** A futures mark price, captured from {@code fstream.binance.com/market}, with its extra {@code st} field. */
    public static final String MARK_PRICE_CAPTURED = "{\"stream\":\"btcusdt@markPrice@1s\",\"data\":{\"e\":"
            + "\"markPriceUpdate\",\"E\":1790259565000,\"s\":\"BTCUSDT\",\"p\":\"84286.39852899\",\"ap\":"
            + "\"84286.39852899\",\"P\":\"84172.28272530\",\"i\":\"84316.26956522\",\"r\":\"0.00001583\","
            + "\"T\":1790265600000,\"st\":1}}";

    /** A futures candle as the futures host sends it, with spaces after the commas. */
    public static final String FUTURES_KLINE_CAPTURED = "{\"stream\":\"btcusdt@kline_1m\",\"data\":{\"e\":\"kline\","
            + "\"E\":1790259564856,\"s\":\"BTCUSDT\",\"k\":{\"t\":1790259540000, \"T\":1790259599999, \"s\":\"BTCUSDT\","
            + " \"i\":\"1m\", \"f\":8116160622, \"L\":8116162358, \"o\":\"84240.20\", \"c\":\"84285.90\","
            + " \"h\":\"84286.00\", \"l\":\"84240.10\", \"v\":\"44.812\", \"n\":1734, \"x\":false,"
            + " \"q\":\"3776033.15300\", \"V\":\"35.927\", \"Q\":\"3027343.64690\", \"B\":\"0\"}}}";

    private StreamFrames() {}

    /** A candle of this symbol and timeframe opening at {@code open}, closed or forming. */
    public static String kline(String symbol, MarketInterval interval, Instant open, boolean closed) {
        Instant close = open.plus(interval.duration()).minusMillis(1);
        return ("{\"stream\":\"%s@kline_%s\",\"data\":{\"e\":\"kline\",\"E\":%d,\"s\":\"%s\",\"k\":{\"t\":%d,"
                        + "\"T\":%d,\"s\":\"%s\",\"i\":\"%s\",\"f\":1,\"L\":2,\"o\":\"100.10\",\"c\":\"101.20\","
                        + "\"h\":\"102.30\",\"l\":\"99.40\",\"v\":\"12.5\",\"n\":42,\"x\":%s,\"q\":\"1260.75\","
                        + "\"V\":\"6\",\"Q\":\"600\",\"B\":\"0\"}}}")
                .formatted(
                        symbol.toLowerCase(Locale.ROOT),
                        interval.code(),
                        (closed ? close.plusMillis(1) : open.plus(Duration.ofSeconds(1))).toEpochMilli(),
                        symbol,
                        open.toEpochMilli(),
                        close.toEpochMilli(),
                        symbol,
                        interval.code(),
                        closed);
    }

    /** A Spot 24-hour ticker of this symbol at this instant. */
    public static String ticker(String symbol, Instant at) {
        return ("{\"stream\":\"%s@ticker\",\"data\":{\"e\":\"24hrTicker\",\"E\":%d,\"s\":\"%s\",\"p\":\"-198.89\","
                        + "\"P\":\"-0.235\",\"w\":\"84048.3\",\"x\":\"84480.89\",\"c\":\"84282.01\",\"Q\":\"0.04578\","
                        + "\"b\":\"84282.00\",\"B\":\"4.39\",\"a\":\"84282.01\",\"A\":\"7.9\",\"o\":\"84480.90\","
                        + "\"h\":\"84794.01\",\"l\":\"82874.93\",\"v\":\"21165.54049\",\"q\":\"1778929165.5786042\","
                        + "\"O\":1,\"C\":2,\"F\":3,\"L\":4,\"n\":3410122}}")
                .formatted(symbol.toLowerCase(Locale.ROOT), at.toEpochMilli(), symbol);
    }

    /** A futures mark price of this symbol at this instant. */
    public static String markPrice(String symbol, Instant at) {
        return ("{\"stream\":\"%s@markPrice@1s\",\"data\":{\"e\":\"markPriceUpdate\",\"E\":%d,\"s\":\"%s\","
                        + "\"p\":\"84286.39852899\",\"ap\":\"84286.39852899\",\"P\":\"84172.2827253\","
                        + "\"i\":\"84316.26956522\",\"r\":\"0.00001583\",\"T\":1790265600000,\"st\":1}}")
                .formatted(symbol.toLowerCase(Locale.ROOT), at.toEpochMilli(), symbol);
    }
}
