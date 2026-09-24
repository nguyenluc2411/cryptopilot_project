package com.cryptopilot.market.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cryptopilot.market.MarketType;
import com.cryptopilot.market.client.BinanceClientException;
import com.cryptopilot.market.client.BinanceClientProperties;
import com.cryptopilot.market.client.BinanceRestClient;
import com.cryptopilot.market.client.InMemoryBinanceBans;
import com.cryptopilot.market.client.StubExchange;
import com.cryptopilot.market.client.StubExchange.Answer;
import com.cryptopilot.market.config.SymbolSyncProperties;
import com.cryptopilot.market.entity.CryptoPair;
import com.cryptopilot.market.entity.ExchangeStatus;
import com.cryptopilot.market.entity.PairStatus;
import com.cryptopilot.market.repository.CoinRepository;
import com.cryptopilot.market.repository.CryptoPairRepository;
import com.cryptopilot.support.MutableTestClock;
import com.cryptopilot.support.TestcontainersConfig;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * NSF-01 against real exchange information snapshots and the migrated PostgreSQL schema: what one
 * synchronisation writes, that a second one with the same input writes nothing new, and each rule for a pair
 * that changed, stopped trading, disappeared, or is spelled differently on the other market.
 *
 * <p>Not {@code @Transactional}: each market commits its own transaction, and the case where one market fails
 * while the other has committed can only be seen from outside both. The pair and coin tables are emptied
 * after each test; the seed holds none (Q-05).
 *
 * <p>The exchange is a local stand-in serving the snapshots; nothing here reaches Binance.
 *
 * <p>Rule: NSF-01; BR-07; Q-14, Q-15; TECHNICAL_DESIGN 5.4 and 7.1.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class SymbolSyncServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-24T00:05:00Z");

    @Autowired
    private SymbolSyncWriter writer;

    @Autowired
    private CryptoPairRepository pairs;

    @Autowired
    private CoinRepository coins;

    @Autowired
    private JdbcClient sql;

    private final MutableTestClock clock = new MutableTestClock(NOW);

    private StubExchange exchange;

    private BinanceRestClient client;

    @BeforeEach
    void startTheStandIn() throws Exception {
        exchange = new StubExchange();
        client = clientFor(exchange.baseUrl());
        serve(ExchangeInfoFixtures.spot(), ExchangeInfoFixtures.futures());
    }

    @AfterEach
    void emptyTheTables() {
        client.close();
        exchange.close();
        sql.sql("delete from crypto_pair").update();
        sql.sql("delete from coin").update();
    }

    /**
     * The first synchronisation of both markets: filters and statuses on the registered pairs, with the
     * exchange's precision, the perpetual's filters rather than the quarterly's, and nothing created from the
     * exchange's list.
     */
    @Test
    void NSF01_theFirstSync_writesFiltersAndStatusesOfRegisteredPairs() {
        register("BTCUSDT", "BTC", "USDT");
        register("SHIBUSDT", "SHIB", "USDT");

        service(List.of()).sync(MarketType.SPOT);
        service(List.of()).sync(MarketType.FUTURES);

        CryptoPair btc = pair("BTCUSDT");
        assertThat(btc.filters(MarketType.SPOT).orElseThrow().tickSize()).isEqualByComparingTo("0.01");
        assertThat(btc.filters(MarketType.SPOT).orElseThrow().stepSize()).isEqualByComparingTo("0.00001");
        assertThat(btc.filters(MarketType.FUTURES).orElseThrow().minNotional())
                .as("the perpetual's 50, not the quarterly's 5")
                .isEqualByComparingTo("50");
        assertThat(btc.exchangeStatus(MarketType.SPOT)).isEqualTo(ExchangeStatus.TRADING);
        assertThat(btc.exchangeStatusRaw(MarketType.FUTURES)).isEqualTo("TRADING");
        assertThat(btc.getLastSyncedAt()).isEqualTo(NOW);
        assertThat(btc.getPairStatus()).as("never activated by the sync").isEqualTo(PairStatus.INACTIVE);

        CryptoPair shib = pair("SHIBUSDT");
        assertThat(shib.filters(MarketType.SPOT).orElseThrow().tickSize())
                .as("TD 5.4: the smallest real tick is stored exactly")
                .isEqualByComparingTo("0.00000001");
        assertThat(shib.exchangeStatus(MarketType.FUTURES))
                .as("Q-14: futures spells it 1000SHIBUSDT, so this pair has no futures status")
                .isNull();

        assertThat(symbolsInTable()).containsExactly("BTCUSDT", "SHIBUSDT");
    }

    /**
     * Idempotent: the same input again changes no filter, status or raw text, reports nothing, flags
     * nothing. Only {@code last_synced_at} moves.
     */
    @Test
    void NSF01_aSecondSyncOfTheSameInput_changesNothingButTheSyncInstant() {
        register("BTCUSDT", "BTC", "USDT");
        service(List.of()).sync(MarketType.SPOT);
        String before = stateOf("BTCUSDT");

        clock.advance(Duration.ofDays(1));
        SyncReport again = service(List.of()).sync(MarketType.SPOT);

        assertThat(stateOf("BTCUSDT")).isEqualTo(before);
        assertThat(again.filterChanges()).isEmpty();
        assertThat(again.flagged()).isEmpty();
        assertThat(again.created()).isEmpty();
        assertThat(pair("BTCUSDT").getLastSyncedAt()).isEqualTo(NOW.plus(Duration.ofDays(1)));
    }

    /** A filter the exchange changed is replaced, reported, and logged at INFO with its old and new value. */
    @Test
    void NSF01_aChangedFilter_isReplacedAndReported() {
        register("BTCUSDT", "BTC", "USDT");
        service(List.of()).sync(MarketType.SPOT);
        serve(
                ExchangeInfoFixtures.spot().filter("BTCUSDT", "PRICE_FILTER", "tickSize", "0.10000000"),
                ExchangeInfoFixtures.futures());

        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        Logger writerLog = (Logger) LoggerFactory.getLogger(SymbolSyncWriter.class);
        writerLog.addAppender(logs);
        SyncReport report;
        try {
            report = service(List.of()).sync(MarketType.SPOT);
        } finally {
            writerLog.detachAppender(logs);
        }

        assertThat(logs.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getFormattedMessage())
                    .contains("BTCUSDT filters changed")
                    .containsPattern("tick 0\\.010+ -> 0\\.10000000");
        });
        assertThat(report.filterChanges()).containsExactly("BTCUSDT");
        assertThat(pair("BTCUSDT").filters(MarketType.SPOT).orElseThrow().tickSize())
                .isEqualByComparingTo("0.1");
    }

    /**
     * NSF-01: a pair the exchange no longer trades is flagged once and recorded NOT_TRADING with Binance's
     * own word beside it; the next identical run does not flag it again.
     */
    @Test
    void NSF01_aPairThatStopsTrading_isFlaggedOnceAndRecordedWithItsRawStatus() {
        register("ETHUSDT", "ETH", "USDT");
        service(List.of()).sync(MarketType.SPOT);
        serve(
                ExchangeInfoFixtures.spot().with("ETHUSDT", s -> s.put("status", "BREAK")),
                ExchangeInfoFixtures.futures());

        SyncReport stopped = service(List.of()).sync(MarketType.SPOT);
        SyncReport again = service(List.of()).sync(MarketType.SPOT);

        assertThat(stopped.flagged()).containsExactly("ETHUSDT");
        assertThat(again.flagged()).isEmpty();
        assertThat(pair("ETHUSDT").exchangeStatus(MarketType.SPOT)).isEqualTo(ExchangeStatus.NOT_TRADING);
        assertThat(pair("ETHUSDT").exchangeStatusRaw(MarketType.SPOT)).isEqualTo("BREAK");
    }

    /** A pair that disappears from a market is DELISTED, flagged once, and never deleted. */
    @Test
    void NSF01_aPairThatDisappears_isDelistedAndKept() {
        register("ETHUSDT", "ETH", "USDT");
        service(List.of()).sync(MarketType.SPOT);
        serve(ExchangeInfoFixtures.spot().without("ETHUSDT"), ExchangeInfoFixtures.futures());

        SyncReport report = service(List.of()).sync(MarketType.SPOT);
        SyncReport again = service(List.of()).sync(MarketType.SPOT);

        assertThat(report.flagged()).containsExactly("ETHUSDT");
        assertThat(again.flagged())
                .as("already DELISTED: not flagged a second time")
                .isEmpty();
        CryptoPair eth = pair("ETHUSDT");
        assertThat(eth.exchangeStatus(MarketType.SPOT)).isEqualTo(ExchangeStatus.DELISTED);
        assertThat(eth.exchangeStatusRaw(MarketType.SPOT)).isNull();
        assertThat(eth.filters(MarketType.SPOT))
                .as("the last known filters are kept")
                .isPresent();
    }

    /** A market switched on for a pair the exchange never listed there is flagged DELISTED too. */
    @Test
    void NSF01_aSwitchedOnMarketTheExchangeDoesNotList_isDelisted() {
        UUID shib = register("SHIBUSDT", "SHIB", "USDT");
        sql.sql("update crypto_pair set is_futures_enabled = true, max_leverage = 20 where pair_id = ?")
                .param(shib)
                .update();

        SyncReport report = service(List.of()).sync(MarketType.FUTURES);

        assertThat(report.flagged()).containsExactly("SHIBUSDT");
        assertThat(pair("SHIBUSDT").exchangeStatus(MarketType.FUTURES)).isEqualTo(ExchangeStatus.DELISTED);
    }

    /**
     * The universe: a seed symbol missing from the table is created INACTIVE with both markets off and its
     * coins; a symbol that is neither registered nor seeded is ignored however many the exchange lists.
     */
    @Test
    void BR07_aSeedSymbolIsCreatedInactive_andAnythingElseIsIgnored() {
        SyncReport report = service(List.of("XRPUSDT")).sync(MarketType.SPOT);

        assertThat(report.created()).containsExactly("XRPUSDT");
        CryptoPair xrp = pair("XRPUSDT");
        assertThat(xrp.getPairStatus()).isEqualTo(PairStatus.INACTIVE);
        assertThat(xrp.isSpotEnabled()).isFalse();
        assertThat(xrp.isFuturesEnabled()).isFalse();
        assertThat(xrp.filters(MarketType.SPOT).orElseThrow().stepSize()).isEqualByComparingTo("0.1");
        assertThat(coins.findBySymbol("XRP")).isPresent();
        assertThat(symbolsInTable())
                .as("BTCUSDT, ETHUSDT and SHIBUSDT are listed but not in the universe")
                .containsExactly("XRPUSDT");

        service(List.of("XRPUSDT")).sync(MarketType.FUTURES);
        assertThat(pair("XRPUSDT").exchangeStatus(MarketType.FUTURES))
                .as("not listed on futures in the snapshot, and never was")
                .isNull();
    }

    /**
     * A seed symbol is created only when it is listed and trading here, and only once: one that is not
     * trading, one the market does not list, and one already in the table are all left alone.
     */
    @Test
    void BR07_aSeedSymbol_isCreatedOnlyWhenListedTradingAndMissing() {
        register("BTCUSDT", "BTC", "USDT");
        serve(
                ExchangeInfoFixtures.spot().with("ETHUSDT", s -> s.put("status", "BREAK")),
                ExchangeInfoFixtures.futures());

        SyncReport report = service(List.of("BTCUSDT", "ETHUSDT", "DOGEUSDT")).sync(MarketType.SPOT);

        assertThat(report.created()).isEmpty();
        assertThat(symbolsInTable()).containsExactly("BTCUSDT");
    }

    /**
     * A registered pair whose exchange entry has a filter the system cannot use keeps its last good filters;
     * its status is still recorded from the exchange.
     */
    @Test
    void NSF01_aListedPairWithUnusableFilters_keepsItsFiltersAndStillGetsItsStatus() {
        register("BTCUSDT", "BTC", "USDT");
        service(List.of()).sync(MarketType.SPOT);
        serve(
                ExchangeInfoFixtures.spot()
                        .filter("BTCUSDT", "PRICE_FILTER", "tickSize", "0.00000000")
                        .with("BTCUSDT", s -> s.put("status", "HALT")),
                ExchangeInfoFixtures.futures());

        SyncReport report = service(List.of()).sync(MarketType.SPOT);

        assertThat(report.filterChanges()).isEmpty();
        CryptoPair btc = pair("BTCUSDT");
        assertThat(btc.filters(MarketType.SPOT).orElseThrow().tickSize()).isEqualByComparingTo("0.01");
        assertThat(btc.exchangeStatus(MarketType.SPOT)).isEqualTo(ExchangeStatus.NOT_TRADING);
        assertThat(btc.exchangeStatusRaw(MarketType.SPOT)).isEqualTo("HALT");
    }

    /** A non-perpetual contract is never created, even when seeded. */
    @Test
    void NSF01_aQuarterlyContract_isNeverCreated() {
        SyncReport report = service(List.of("BTCUSDT_260925")).sync(MarketType.FUTURES);

        assertThat(report.created()).isEmpty();
        assertThat(symbolsInTable()).isEmpty();
    }

    /**
     * Q-14: the Spot and futures spellings are two pairs, each enabled on one market only — SHIBUSDT has no
     * futures status, 1000SHIBUSDT has no Spot status and its own base coin.
     */
    @Test
    void Q14_differentSpellingsAcrossMarkets_areTwoPairsOfOneMarketEach() {
        SymbolSyncService seeded = service(List.of("SHIBUSDT", "1000SHIBUSDT"));

        seeded.sync(MarketType.SPOT);
        seeded.sync(MarketType.FUTURES);

        assertThat(pair("SHIBUSDT").exchangeStatus(MarketType.SPOT)).isEqualTo(ExchangeStatus.TRADING);
        assertThat(pair("SHIBUSDT").exchangeStatus(MarketType.FUTURES)).isNull();
        assertThat(pair("1000SHIBUSDT").exchangeStatus(MarketType.FUTURES)).isEqualTo(ExchangeStatus.TRADING);
        assertThat(pair("1000SHIBUSDT").exchangeStatus(MarketType.SPOT)).isNull();
        assertThat(pair("1000SHIBUSDT")
                        .filters(MarketType.FUTURES)
                        .orElseThrow()
                        .tickSize())
                .isEqualByComparingTo("0.000001");
        assertThat(coins.findBySymbol("1000SHIB")).isPresent();
    }

    /**
     * One market failing leaves the other's committed synchronisation as it is, and writes nothing of its
     * own: the futures columns stay untouched.
     */
    @Test
    void NSF01_oneMarketFailing_leavesTheOthersCommittedWorkAlone() {
        register("BTCUSDT", "BTC", "USDT");
        exchange.on(ExchangeInfoFixtures.FUTURES_PATH, Answer.status(503));

        service(List.of()).sync(MarketType.SPOT);
        assertThatExceptionOfType(BinanceClientException.class)
                .isThrownBy(() -> service(List.of()).sync(MarketType.FUTURES))
                .satisfies(e -> assertThat(e.kind()).isEqualTo(BinanceClientException.Kind.UNAVAILABLE));

        CryptoPair btc = pair("BTCUSDT");
        assertThat(btc.exchangeStatus(MarketType.SPOT)).isEqualTo(ExchangeStatus.TRADING);
        assertThat(btc.exchangeStatus(MarketType.FUTURES)).isNull();
        assertThat(btc.filters(MarketType.FUTURES)).isEmpty();
    }

    private SymbolSyncService service(List<String> seeds) {
        return new SymbolSyncService(
                client, writer, new SymbolSyncProperties(false, "0 5 0 * * *", ZoneOffset.UTC, seeds), clock);
    }

    private void serve(ExchangeInfoFixtures spot, ExchangeInfoFixtures futures) {
        exchange.on(ExchangeInfoFixtures.SPOT_PATH, Answer.ok(spot.json()));
        exchange.on(ExchangeInfoFixtures.FUTURES_PATH, Answer.ok(futures.json()));
    }

    private UUID register(String symbol, String base, String quote) {
        OffsetDateTime at = NOW.atOffset(ZoneOffset.UTC);
        UUID baseId = coinId(base, at);
        UUID quoteId = coinId(quote, at);
        UUID pairId = UUID.randomUUID();
        sql.sql("""
                        insert into crypto_pair (pair_id, base_coin_id, quote_coin_id, symbol, pair_status,
                                                 created_at, updated_at)
                        values (?, ?, ?, ?, 'INACTIVE', ?, ?)""").params(pairId, baseId, quoteId, symbol, at, at).update();
        return pairId;
    }

    private UUID coinId(String symbol, OffsetDateTime at) {
        Optional<UUID> existing = sql.sql("select coin_id from coin where symbol = ?")
                .param(symbol)
                .query(UUID.class)
                .optional();
        if (existing.isPresent()) {
            return existing.get();
        }
        UUID id = UUID.randomUUID();
        sql.sql("insert into coin (coin_id, symbol, coin_name, created_at, updated_at) values (?, ?, ?, ?, ?)")
                .params(id, symbol, symbol, at, at)
                .update();
        return id;
    }

    private CryptoPair pair(String symbol) {
        return pairs.findBySymbol(symbol).orElseThrow();
    }

    private List<String> symbolsInTable() {
        return sql.sql("select symbol from crypto_pair order by symbol")
                .query(String.class)
                .list();
    }

    /** Everything the sync writes for a pair except the sync instant, as one comparable line. */
    private String stateOf(String symbol) {
        return sql.sql("""
                        select concat_ws('|', spot_tick_size, spot_step_size, spot_min_notional, futures_tick_size,
                                         spot_exchange_status, spot_exchange_status_raw, futures_exchange_status,
                                         futures_exchange_status_raw)
                          from crypto_pair where symbol = ?""").param(symbol).query(String.class).single();
    }

    private BinanceRestClient clientFor(URI base) {
        return new BinanceRestClient(
                new BinanceClientProperties(
                        new BinanceClientProperties.Venue(base, 6000),
                        new BinanceClientProperties.Venue(base, 2400),
                        Duration.ofMillis(500),
                        Duration.ofSeconds(2),
                        80,
                        Duration.ofMinutes(2),
                        new BinanceClientProperties.Retry(
                                0, Duration.ofMillis(1), 1.0, Duration.ofMillis(1), Duration.ZERO),
                        new BinanceClientProperties.CircuitBreaker(5, Duration.ofSeconds(30))),
                clock,
                JsonMapper.builder().build(),
                new InMemoryBinanceBans());
    }
}
