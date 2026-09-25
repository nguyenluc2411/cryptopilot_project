package com.cryptopilot.market.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.market.MarketType;
import com.cryptopilot.market.client.Kline;
import com.cryptopilot.market.client.MarketInterval;
import com.cryptopilot.market.client.StreamMessage.KlineMessage;
import com.cryptopilot.market.client.StreamMessage.MarkPriceMessage;
import com.cryptopilot.market.client.StreamMessage.TickerMessage;
import com.cryptopilot.market.dto.response.KlineUpdateResponse;
import com.cryptopilot.market.dto.response.MarketOverviewResponse;
import com.cryptopilot.market.dto.response.TickerUpdateResponse;
import com.cryptopilot.market.service.impl.MarketBroadcastServiceImpl;
import com.cryptopilot.support.MutableTestClock;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.support.GenericMessage;

/**
 * The market topics without a broker: which destination each stream message goes to, how updates coalesce between two
 * pushes — newest by the exchange's instant, a closed candle never replaced — and that a push that fails for one
 * destination still delivers the others. Pushes are called directly; nothing waits.
 *
 * <p>Rule: NSF-03; BR-08; SRS 4.2.3; TECHNICAL_DESIGN 9; D-51.
 */
class MarketBroadcastServiceTest {

    private static final Instant AT = Instant.parse("2026-09-25T10:00:00Z");

    private final List<Map.Entry<String, Object>> sent = new ArrayList<>();
    private final List<String> refused = new ArrayList<>();
    private final MessageChannel recorder = (message, timeout) -> record(message);
    private final MarketBroadcastService broadcast =
            new MarketBroadcastServiceImpl(new SimpMessagingTemplate(recorder), new MutableTestClock(AT));

    /** Each market has its own ticker destination; the payload carries the fields of its market and the source instant. */
    @Test
    void TD9_tickers_goToTheDestinationOfTheirPairAndMarket() {
        broadcast.publish(MarketType.SPOT, ticker("BTCUSDT", "63050.25", AT));
        broadcast.publish(MarketType.FUTURES, markPrice("BTCUSDT", "63055.5", AT));

        assertThat(broadcast.pushUpdates()).isEqualTo(2);

        TickerUpdateResponse spot = payload("/topic/ticker.SPOT.BTCUSDT", TickerUpdateResponse.class);
        assertThat(spot.lastPrice()).isEqualByComparingTo("63050.25");
        assertThat(spot.markPrice()).isNull();
        assertThat(spot.sourceTime()).isEqualTo(AT);
        TickerUpdateResponse futures = payload("/topic/ticker.FUTURES.BTCUSDT", TickerUpdateResponse.class);
        assertThat(futures.markPrice()).isEqualByComparingTo("63055.5");
        assertThat(futures.nextFundingTime()).isEqualTo(Instant.parse("2026-09-25T16:00:00Z"));
        assertThat(futures.lastPrice()).isNull();
    }

    /** TD 9: many messages between two pushes are one message per destination, the newest by source instant. */
    @Test
    void TD9_manyUpdatesBetweenPushes_areOneMessage_theNewest() {
        for (int i = 0; i < 10; i++) {
            broadcast.publish(MarketType.SPOT, ticker("BTCUSDT", String.valueOf(63000 + i), AT.plusMillis(100L * i)));
        }
        broadcast.publish(MarketType.SPOT, ticker("BTCUSDT", "1", AT.minusSeconds(5)));

        assertThat(broadcast.pushUpdates()).isOne();
        assertThat(payload("/topic/ticker.SPOT.BTCUSDT", TickerUpdateResponse.class)
                        .lastPrice())
                .as("the late, older message did not replace the newest")
                .isEqualByComparingTo("63009");
        assertThat(broadcast.pushUpdates()).as("nothing new, nothing sent").isZero();
    }

    /**
     * BR-08: a candle's close and the first report of the next candle, in one interval, are both sent, in candle order;
     * a forming report of a candle already closed does not replace its close.
     */
    @Test
    void BR08_aClosedCandle_isNeverCoalescedAway() {
        Instant next = AT.plus(Duration.ofHours(1));
        broadcast.publish(MarketType.SPOT, kline("BTCUSDT", MarketInterval.ONE_HOUR, AT, "63100", true, next));
        broadcast.publish(
                MarketType.SPOT, kline("BTCUSDT", MarketInterval.ONE_HOUR, AT, "63090", false, next.plusMillis(1)));
        broadcast.publish(
                MarketType.SPOT, kline("BTCUSDT", MarketInterval.ONE_HOUR, next, "63101", false, next.plusSeconds(1)));

        assertThat(broadcast.pushUpdates()).isEqualTo(2);

        List<KlineUpdateResponse> candles = sent.stream()
                .filter(entry -> entry.getKey().equals("/topic/kline.SPOT.BTCUSDT.1h"))
                .map(entry -> (KlineUpdateResponse) entry.getValue())
                .toList();
        assertThat(candles).extracting(KlineUpdateResponse::openTime).containsExactly(AT, next);
        assertThat(candles.get(0).closed()).isTrue();
        assertThat(candles.get(0).close()).isEqualByComparingTo("63100");
        assertThat(candles.get(1).closed()).isFalse();
    }

    /** Within one candle: an older report arriving late is ignored, and the close replaces a forming report. */
    @Test
    void BR08_withinOneCandle_theLatestReportWins_andTheCloseReplacesAFormingOne() {
        broadcast.publish(
                MarketType.SPOT, kline("BTCUSDT", MarketInterval.ONE_HOUR, AT, "63050", false, AT.plusSeconds(2)));
        broadcast.publish(
                MarketType.SPOT, kline("BTCUSDT", MarketInterval.ONE_HOUR, AT, "63040", false, AT.plusSeconds(1)));
        broadcast.pushUpdates();
        broadcast.publish(
                MarketType.SPOT, kline("BTCUSDT", MarketInterval.ONE_HOUR, AT, "63060", false, AT.plusSeconds(3)));
        broadcast.publish(
                MarketType.SPOT, kline("BTCUSDT", MarketInterval.ONE_HOUR, AT, "63070", true, AT.plusSeconds(4)));
        broadcast.pushUpdates();

        List<KlineUpdateResponse> candles = sent.stream()
                .map(entry -> (KlineUpdateResponse) entry.getValue())
                .toList();
        assertThat(candles)
                .extracting(KlineUpdateResponse::close)
                .map(BigDecimal::toPlainString)
                .containsExactly("63050", "63070");
        assertThat(candles.get(1).closed()).isTrue();
    }

    /** No market has streamed a ticker: there is no overview to push. */
    @Test
    void TD9_withNoTicker_noOverviewIsPushed() {
        assertThat(broadcast.pushOverviews()).isZero();
        assertThat(sent).isEmpty();
    }

    /** Only the timeframes BR-08 stores have a kline topic. */
    @Test
    void BR08_aOneMinuteKline_hasNoTopic() {
        broadcast.publish(MarketType.SPOT, kline("BTCUSDT", MarketInterval.ONE_MINUTE, AT, "63000", false, AT));

        assertThat(broadcast.pushUpdates()).isZero();
    }

    /** TD 9: each market's overview lists the latest ticker of each of its pairs, by symbol. */
    @Test
    void TD9_theOverview_listsTheLatestTickerOfEachPairOfItsMarket() {
        broadcast.publish(MarketType.SPOT, ticker("ETHUSDT", "4000", AT));
        broadcast.publish(MarketType.SPOT, ticker("BTCUSDT", "63000", AT));
        broadcast.publish(MarketType.SPOT, ticker("BTCUSDT", "63001", AT.plusSeconds(1)));
        broadcast.pushUpdates();

        assertThat(broadcast.pushOverviews())
                .as("Spot only: futures has streamed nothing")
                .isOne();

        MarketOverviewResponse overview = payload("/topic/overview.SPOT", MarketOverviewResponse.class);
        assertThat(overview.serverTime()).isEqualTo(AT);
        assertThat(overview.tickers()).extracting(TickerUpdateResponse::symbol).containsExactly("BTCUSDT", "ETHUSDT");
        assertThat(overview.tickers().get(0).lastPrice()).isEqualByComparingTo("63001");
    }

    /** A destination the broker refuses is logged; the other destinations of the same push are delivered. */
    @Test
    void TD9_aRefusedDestination_doesNotStopThePush() {
        refused.add("/topic/ticker.SPOT.BTCUSDT");
        broadcast.publish(MarketType.SPOT, ticker("BTCUSDT", "63000", AT));
        broadcast.publish(MarketType.SPOT, ticker("ETHUSDT", "4000", AT));

        assertThat(broadcast.pushUpdates()).isOne();
        assertThat(sent).extracting(Map.Entry::getKey).containsExactly("/topic/ticker.SPOT.ETHUSDT");
    }

    private boolean record(Message<?> message) {
        String destination = SimpMessageHeaderAccessor.getDestination(message.getHeaders());
        if (refused.contains(destination)) {
            throw new IllegalStateException("broker refused " + destination);
        }
        Object payload = message instanceof GenericMessage<?> generic ? generic.getPayload() : message.getPayload();
        sent.add(Map.entry(destination, payload));
        return true;
    }

    private <T> T payload(String destination, Class<T> type) {
        return sent.stream()
                .filter(entry -> entry.getKey().equals(destination))
                .map(entry -> type.cast(entry.getValue()))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("nothing sent to " + destination));
    }

    private static TickerMessage ticker(String symbol, String last, Instant at) {
        BigDecimal price = new BigDecimal(last);
        return new TickerMessage(
                symbol, price, price, price, price, price, BigDecimal.ONE, BigDecimal.TEN, BigDecimal.TEN, at);
    }

    private static MarkPriceMessage markPrice(String symbol, String mark, Instant at) {
        return new MarkPriceMessage(
                symbol,
                new BigDecimal(mark),
                new BigDecimal("63050"),
                new BigDecimal("0.0001"),
                Instant.parse("2026-09-25T16:00:00Z"),
                at);
    }

    private static KlineMessage kline(
            String symbol, MarketInterval interval, Instant open, String close, boolean closed, Instant at) {
        BigDecimal price = new BigDecimal(close);
        Kline kline = new Kline(
                open,
                open.plus(interval.duration()).minusMillis(1),
                price,
                price,
                price,
                price,
                BigDecimal.ONE,
                BigDecimal.TEN,
                3);
        return new KlineMessage(symbol, interval, kline, closed, at);
    }
}
