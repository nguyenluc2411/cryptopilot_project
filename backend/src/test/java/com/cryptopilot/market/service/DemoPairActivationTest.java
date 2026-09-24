package com.cryptopilot.market.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.market.MarketTestData;
import com.cryptopilot.market.MarketType;
import com.cryptopilot.market.repository.CryptoPairRepository;
import com.cryptopilot.support.TestcontainersConfig;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The dev-only activation of the demo pairs against the migrated schema: a seed symbol is enabled on a market
 * exactly when the exchange trades it there, and a second run changes nothing.
 *
 * <p>Rule: BR-07; Q-16.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class DemoPairActivationTest {

    private static final List<String> SEEDS = List.of("BTCUSDT", "ETHUSDT", "XRPUSDT", "MISSINGUSDT");

    @Autowired
    private DemoPairActivation activation;

    @Autowired
    private CryptoPairRepository pairs;

    @Autowired
    private JdbcClient sql;

    private MarketTestData data;
    private UUID btc;
    private UUID eth;
    private UUID xrp;
    private UUID sol;

    @BeforeEach
    void setUp() {
        data = new MarketTestData(sql, Instant.parse("2026-09-24T10:00:00Z"));
        btc = data.pair("BTCUSDT", false, false, "TRADING", "TRADING", 0);
        eth = data.pair("ETHUSDT", false, false, "TRADING", null, 0);
        xrp = data.pair("XRPUSDT", false, false, "NOT_TRADING", "DELISTED", 0);
        sol = data.pair("SOLUSDT", false, false, "TRADING", "TRADING", 0);
    }

    @AfterEach
    void clear() {
        data.clear();
    }

    /** Q-16: only seed symbols, only where the exchange trades them; a missing seed is skipped. */
    @Test
    void Q16_seedSymbols_areEnabledWhereTheExchangeTradesThem() {
        assertThat(activation.activate(MarketType.SPOT, SEEDS)).containsExactly("BTCUSDT", "ETHUSDT");
        assertThat(activation.activate(MarketType.FUTURES, SEEDS)).containsExactly("BTCUSDT");

        assertThat(enabled(btc, MarketType.SPOT)).isTrue();
        assertThat(enabled(btc, MarketType.FUTURES)).isTrue();
        assertThat(enabled(eth, MarketType.SPOT)).isTrue();
        assertThat(enabled(eth, MarketType.FUTURES)).as("not listed on futures").isFalse();
        assertThat(enabled(xrp, MarketType.SPOT)).as("not trading").isFalse();
        assertThat(enabled(xrp, MarketType.FUTURES)).as("delisted").isFalse();
        assertThat(enabled(sol, MarketType.SPOT)).as("not a seed").isFalse();
    }

    /** Q-16: idempotent — a second run enables nothing and writes nothing. */
    @Test
    void Q16_aSecondRun_changesNothing() {
        activation.activate(MarketType.SPOT, SEEDS);
        long version = version(btc);

        assertThat(activation.activate(MarketType.SPOT, SEEDS)).isEmpty();

        assertThat(version(btc)).isEqualTo(version);
    }

    private boolean enabled(UUID pair, MarketType market) {
        return pairs.findById(pair).orElseThrow().isEnabledOn(market);
    }

    private long version(UUID pair) {
        return sql.sql("select version from crypto_pair where pair_id = ?")
                .param(pair)
                .query(Long.class)
                .single();
    }
}
