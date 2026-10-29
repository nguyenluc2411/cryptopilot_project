package com.cryptopilot.market.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cryptopilot.market.client.StreamMessage.AggTradeMessage;
import com.cryptopilot.market.client.StreamMessage.BookTickerMessage;
import com.cryptopilot.market.client.StreamMessage.DepthMessage;
import com.cryptopilot.market.client.StreamMessage.KlineMessage;
import com.cryptopilot.market.client.StreamMessage.Level;
import com.cryptopilot.market.client.StreamMessage.MarkPriceMessage;
import com.cryptopilot.market.client.StreamMessage.TickerMessage;
import com.cryptopilot.market.model.enums.MarketInterval;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The stream parser against frames captured from the exchange on 2026-09-24: every decimal read exactly from
 * its string, the closed flag honoured, unknown events ignored and malformed frames refused.
 *
 * <p>Rule: NSF-03; BR-08; TECHNICAL_DESIGN 5.4.
 */
class BinanceStreamParserTest {

    private final BinanceStreamParser parser =
            new BinanceStreamParser(JsonMapper.builder().build());

    @Test
    void NSF03_aCapturedSpotKline_isReadExactly() {
        KlineMessage kline =
                (KlineMessage) parser.parse(StreamFrames.SPOT_KLINE_CAPTURED).orElseThrow();

        assertThat(kline.symbol()).isEqualTo("BTCUSDT");
        assertThat(kline.interval()).isEqualTo(MarketInterval.ONE_MINUTE);
        assertThat(kline.closed()).isTrue();
        assertThat(kline.eventTime()).isEqualTo(Instant.ofEpochMilli(1790259540026L));
        assertThat(kline.kline().openTime()).isEqualTo(Instant.ofEpochMilli(1790259480000L));
        assertThat(kline.kline().closeTime()).isEqualTo(Instant.ofEpochMilli(1790259539999L));
        assertThat(kline.kline().open()).isEqualTo(new BigDecimal("84281.32000000"));
        assertThat(kline.kline().high()).isEqualTo(new BigDecimal("84290.00000000"));
        assertThat(kline.kline().low()).isEqualTo(new BigDecimal("84252.00000000"));
        assertThat(kline.kline().close()).isEqualTo(new BigDecimal("84282.00000000"));
        assertThat(kline.kline().volume()).isEqualTo(new BigDecimal("11.30572000"));
        assertThat(kline.kline().quoteVolume()).isEqualTo(new BigDecimal("952813.20737560"));
        assertThat(kline.kline().tradeCount()).isEqualTo(3199);
    }

    /** BR-08: the forming candle is marked as such, so nobody stores it. */
    @Test
    void BR08_aCapturedFuturesKlineStillForming_isNotClosed() {
        KlineMessage kline =
                (KlineMessage) parser.parse(StreamFrames.FUTURES_KLINE_CAPTURED).orElseThrow();

        assertThat(kline.closed()).isFalse();
        assertThat(kline.kline().close()).isEqualTo(new BigDecimal("84285.90"));
    }

    @Test
    void NSF03_aCapturedMarkPrice_isReadExactly() {
        MarkPriceMessage mark = (MarkPriceMessage)
                parser.parse(StreamFrames.MARK_PRICE_CAPTURED).orElseThrow();

        assertThat(mark.symbol()).isEqualTo("BTCUSDT");
        assertThat(mark.markPrice()).isEqualTo(new BigDecimal("84286.39852899"));
        assertThat(mark.indexPrice()).isEqualTo(new BigDecimal("84316.26956522"));
        assertThat(mark.fundingRate()).isEqualTo(new BigDecimal("0.00001583"));
        assertThat(mark.nextFundingTime()).isEqualTo(Instant.ofEpochMilli(1790265600000L));
        assertThat(mark.eventTime()).isEqualTo(Instant.ofEpochMilli(1790259565000L));
    }

    @Test
    void NSF03_aTicker_isReadExactly() {
        Instant at = Instant.parse("2026-09-24T10:00:00Z");

        TickerMessage ticker =
                (TickerMessage) parser.parse(StreamFrames.ticker("ETHUSDT", at)).orElseThrow();

        assertThat(ticker.symbol()).isEqualTo("ETHUSDT");
        assertThat(ticker.eventTime()).isEqualTo(at);
        assertThat(ticker.lastPrice()).isEqualTo(new BigDecimal("84282.01"));
        assertThat(ticker.bestBidPrice()).isEqualTo(new BigDecimal("84282.00"));
        assertThat(ticker.bestAskPrice()).isEqualTo(new BigDecimal("84282.01"));
        assertThat(ticker.highPrice24h()).isEqualTo(new BigDecimal("84794.01"));
        assertThat(ticker.lowPrice24h()).isEqualTo(new BigDecimal("82874.93"));
        assertThat(ticker.priceChangePercent24h()).isEqualTo(new BigDecimal("-0.235"));
        assertThat(ticker.baseVolume24h()).isEqualTo(new BigDecimal("21165.54049"));
        assertThat(ticker.quoteVolume24h()).isEqualTo(new BigDecimal("1778929165.5786042"));
    }

    /** A shutdown notice or any event NSF-03 does not read is not an error. */
    @Test
    void NSF03_anUnknownEvent_yieldsNothing() {
        assertThat(parser.parse("{\"stream\":\"x\",\"data\":{\"e\":\"serverShutdown\",\"E\":1}}"))
                .isEmpty();
        assertThat(parser.parse("{\"stream\":\"x\",\"data\":{}}")).isEmpty();
    }

    @Test
    void NSF03_aFrameThatIsNotACombinedStreamMessage_isRefused() {
        assertThatThrownBy(() -> parser.parse("not json")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse("{\"result\":null,\"id\":1}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("combined-stream");
    }

    /** TECHNICAL_DESIGN 5.4: a decimal sent as a JSON number would pass through a double; refused. */
    @Test
    void NSF03_aDecimalSentAsANumber_isRefused() {
        String frame = StreamFrames.MARK_PRICE_CAPTURED.replace("\"p\":\"84286.39852899\"", "\"p\":84286.39852899");

        assertThatThrownBy(() -> parser.parse(frame))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("markPriceUpdate");
    }

    @Test
    void NSF03_aKlineOfAnIntervalNeverAskedFor_isRefused() {
        String frame = StreamFrames.SPOT_KLINE_CAPTURED.replace("\"i\":\"1m\"", "\"i\":\"3m\"");

        assertThatThrownBy(() -> parser.parse(frame)).hasMessageContaining("unknown interval 3m");
    }

    @Test
    void BR08_aClosedFlagThatIsNotABoolean_isRefused() {
        String frame = StreamFrames.SPOT_KLINE_CAPTURED.replace("\"x\":true", "\"x\":\"true\"");

        assertThatThrownBy(() -> parser.parse(frame)).hasMessageContaining("boolean closed flag");
    }

    @Test
    void TR03_anAggregatedTrade_isReadExactly() {
        Instant at = Instant.parse("2026-10-10T08:00:00.123Z");

        AggTradeMessage trade =
                (AggTradeMessage) parser.parse(StreamFrames.aggTrade("BTCUSDT", 12345, "84282.01", "0.00150", at, true))
                        .orElseThrow();

        assertThat(trade.symbol()).isEqualTo("BTCUSDT");
        assertThat(trade.aggregateTradeId()).isEqualTo(12345);
        assertThat(trade.price()).isEqualTo(new BigDecimal("84282.01"));
        assertThat(trade.quantity()).isEqualTo(new BigDecimal("0.00150"));
        assertThat(trade.tradeTime()).isEqualTo(at);
        assertThat(trade.eventTime()).isEqualTo(at.plusMillis(3));
        assertThat(trade.buyerMaker()).isTrue();
    }

    /** The Spot payload has no event type: the stream name says it is a book ticker, and the read instant stamps it. */
    @Test
    void TR03_aBookTicker_isReadByItsStreamName_andStampedWhenRead() {
        Instant now = Instant.parse("2026-10-10T08:00:01Z");
        BinanceStreamParser stamped =
                new BinanceStreamParser(JsonMapper.builder().build(), Clock.fixed(now, ZoneOffset.UTC));

        BookTickerMessage book =
                (BookTickerMessage) stamped.parse(StreamFrames.bookTicker("BTCUSDT", 400900217, "84282.00", "84282.01"))
                        .orElseThrow();

        assertThat(book.symbol()).isEqualTo("BTCUSDT");
        assertThat(book.updateId()).isEqualTo(400900217);
        assertThat(book.bidPrice()).isEqualTo(new BigDecimal("84282.00"));
        assertThat(book.bidQuantity()).isEqualTo(new BigDecimal("31.21"));
        assertThat(book.askPrice()).isEqualTo(new BigDecimal("84282.01"));
        assertThat(book.askQuantity()).isEqualTo(new BigDecimal("40.66"));
        assertThat(book.eventTime()).isEqualTo(now);
    }

    /** The Spot payload names no symbol either: it comes from the stream name. */
    @Test
    void TR03_aPartialDepth_takesItsSymbolFromTheStreamName_bestLevelsFirst() {
        Instant now = Instant.parse("2026-10-10T08:00:02Z");
        BinanceStreamParser stamped =
                new BinanceStreamParser(JsonMapper.builder().build(), Clock.fixed(now, ZoneOffset.UTC));

        DepthMessage depth =
                (DepthMessage) stamped.parse(StreamFrames.depth("ETHUSDT", 160)).orElseThrow();

        assertThat(depth.symbol()).isEqualTo("ETHUSDT");
        assertThat(depth.lastUpdateId()).isEqualTo(160);
        assertThat(depth.bids())
                .containsExactly(
                        new Level(new BigDecimal("84282.00"), new BigDecimal("4.39")),
                        new Level(new BigDecimal("84281.50"), new BigDecimal("0.10")));
        assertThat(depth.asks())
                .containsExactly(
                        new Level(new BigDecimal("84282.01"), new BigDecimal("7.90")),
                        new Level(new BigDecimal("84283.00"), new BigDecimal("1.25")));
        assertThat(depth.eventTime()).isEqualTo(now);
    }

    /** The Futures payload carries its event type; like Spot's, the read instant stamps it. */
    @Test
    void TR38_aFuturesBookTicker_isReadByItsEventType_andStampedWhenRead() {
        Instant at = Instant.parse("2026-10-29T08:00:01Z");
        Instant now = at.plusMillis(40);
        BinanceStreamParser stamped =
                new BinanceStreamParser(JsonMapper.builder().build(), Clock.fixed(now, ZoneOffset.UTC));

        BookTickerMessage book = (BookTickerMessage)
                stamped.parse(StreamFrames.futuresBookTicker("BTCUSDT", 11952974066913L, "79996.90", "79997.00", at))
                        .orElseThrow();

        assertThat(book.symbol()).isEqualTo("BTCUSDT");
        assertThat(book.updateId()).isEqualTo(11952974066913L);
        assertThat(book.bidPrice()).isEqualTo(new BigDecimal("79996.90"));
        assertThat(book.bidQuantity()).isEqualTo(new BigDecimal("3.661"));
        assertThat(book.askPrice()).isEqualTo(new BigDecimal("79997.00"));
        assertThat(book.askQuantity()).isEqualTo(new BigDecimal("2.768"));
        assertThat(book.eventTime()).isEqualTo(now);
    }

    /** A Futures depth is a depthUpdate: levels under b and a, its last update id under u. */
    @Test
    void TR38_aFuturesDepth_readsItsLevelsAndLastUpdateId_bestLevelsFirst() {
        Instant at = Instant.parse("2026-10-29T08:00:02Z");
        Instant now = at.plusMillis(40);
        BinanceStreamParser stamped =
                new BinanceStreamParser(JsonMapper.builder().build(), Clock.fixed(now, ZoneOffset.UTC));

        DepthMessage depth = (DepthMessage)
                stamped.parse(StreamFrames.futuresDepth("BTCUSDT", 500, at)).orElseThrow();

        assertThat(depth.symbol()).isEqualTo("BTCUSDT");
        assertThat(depth.lastUpdateId()).isEqualTo(500);
        assertThat(depth.bids())
                .containsExactly(
                        new Level(new BigDecimal("79996.90"), new BigDecimal("3.662")),
                        new Level(new BigDecimal("79996.80"), new BigDecimal("0.002")));
        assertThat(depth.asks())
                .containsExactly(
                        new Level(new BigDecimal("79997.00"), new BigDecimal("2.768")),
                        new Level(new BigDecimal("79997.10"), new BigDecimal("0.010")));
        assertThat(depth.eventTime()).isEqualTo(now);
    }

    @Test
    void TR38_aMalformedFuturesDepth_isRefused() {
        String depth = StreamFrames.futuresDepth("BTCUSDT", 1, Instant.EPOCH).replace("\"u\":1,", "");

        assertThatThrownBy(() -> parser.parse(depth)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void TR03_aPayloadWithoutAnEventTypeOnAStreamNotRead_yieldsNothing() {
        assertThat(parser.parse("{\"stream\":\"btcusdt@trade\",\"data\":{\"t\":1}}"))
                .isEmpty();
        assertThat(parser.parse("{\"stream\":\"\",\"data\":{\"t\":1}}")).isEmpty();
    }

    @Test
    void TR03_aMalformedTradingPayload_isRefused() {
        String trade = StreamFrames.aggTrade("BTCUSDT", 1, "1", "1", Instant.EPOCH, false)
                .replace("\"m\":false", "\"m\":\"false\"");
        String depth = StreamFrames.depth("BTCUSDT", 1)
                .replace("\"bids\":[[", "\"bids\":{\"x\":[[")
                .replace("]],\"asks", "]]},\"asks");

        assertThatThrownBy(() -> parser.parse(trade)).hasMessageContaining("buyer-maker");
        assertThatThrownBy(() -> parser.parse(depth)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void NSF03_aMissingField_isRefused() {
        String frame = StreamFrames.MARK_PRICE_CAPTURED.replace("\"i\":\"84316.26956522\",", "");

        assertThatThrownBy(() -> parser.parse(frame)).isInstanceOf(IllegalArgumentException.class);
    }
}
