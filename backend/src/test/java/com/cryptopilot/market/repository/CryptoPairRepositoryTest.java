package com.cryptopilot.market.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.cryptopilot.market.MarketType;
import com.cryptopilot.market.PairFilters;
import com.cryptopilot.market.entity.Coin;
import com.cryptopilot.market.entity.CryptoPair;
import com.cryptopilot.market.entity.PairStatus;
import com.cryptopilot.support.TestcontainersConfig;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * Coins and pairs against the migrated PostgreSQL schema: the mapping round-trips with its precision, the
 * natural keys and foreign keys hold, and the one list query returns what BR-07 allows in display order.
 *
 * <p>Every test rolls back; the suite shares one database and the seed holds no pair (Q-05).
 *
 * <p>The identity rule under test is the logical model's: one row per symbol, its two markets side by
 * side, so the same symbol on Spot and on futures is one pair and a second row for a symbol is refused.
 *
 * <p>Rule: BR-07; SRS 3.1.5, UC-45; NSF-01; TECHNICAL_DESIGN 5.4 and 6.
 *
 * <p>Reference: PostgreSQL Global Development Group. <i>PostgreSQL 16 Documentation</i>, ch. 5.5
 * "Constraints" (unique, foreign-key and check constraints are enforced by the database on every write).
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
@Transactional
class CryptoPairRepositoryTest {

    @Autowired
    private CryptoPairRepository pairs;

    @Autowired
    private CoinRepository coins;

    @Autowired
    private EntityManager em;

    @Autowired
    private JdbcClient sql;

    /**
     * The same symbol on Spot and futures is one pair: both markets' filters are stored on the one row and
     * read back with the scale the exchange sent, down to a tick of 0.00000001.
     */
    @Test
    void NSF01_aPairWithBothMarkets_roundTripsWithItsPrecision() {
        CryptoPair shib = pairOf("SHIB", "USDT", "SHIBUSDT");
        shib.applyFilters(
                MarketType.SPOT,
                new PairFilters(new BigDecimal("0.00000001"), new BigDecimal("1.00"), new BigDecimal("1.00000000")));
        shib.applyFilters(
                MarketType.FUTURES, new PairFilters(new BigDecimal("0.000001"), BigDecimal.ONE, new BigDecimal("5")));
        pairs.save(shib);
        em.flush();
        em.clear();

        CryptoPair read = pairs.findBySymbol("SHIBUSDT").orElseThrow();

        assertThat(read.filters(MarketType.SPOT).orElseThrow().tickSize()).isEqualByComparingTo("0.00000001");
        assertThat(read.filters(MarketType.FUTURES).orElseThrow().tickSize()).isEqualByComparingTo("0.000001");
        assertThat(read.filters(MarketType.SPOT).orElseThrow().minNotional()).isEqualByComparingTo("1");
        assertThat(sql.sql("select count(*) from crypto_pair where symbol = 'SHIBUSDT'")
                        .query(Integer.class)
                        .single())
                .as("one row for both markets")
                .isOne();
        assertThat(pairs.findById(read.getId())).isPresent();
    }

    /** {@code uq_crypto_pair_symbol}: a second pair with the same symbol is refused. */
    @Test
    void BR07_aSecondPairWithTheSameSymbol_isRefused() {
        CryptoPair first = pairOf("BTC", "USDT", "BTCUSDT");
        pairs.save(first);
        em.flush();

        CryptoPair second = CryptoPair.register(first.getBaseCoinId(), first.getQuoteCoinId(), "BTCUSDT");

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> {
                    pairs.save(second);
                    pairs.flush();
                })
                .withStackTraceContaining("uq_crypto_pair_symbol");
    }

    /** {@code uq_coin_symbol}: a coin is one row per symbol. */
    @Test
    void NSF01_aSecondCoinWithTheSameSymbol_isRefused() {
        coins.save(Coin.fromExchange("ETH"));
        coins.flush();
        assertThat(coins.findBySymbol("ETH")).isPresent();

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> {
                    coins.save(Coin.fromExchange("ETH"));
                    coins.flush();
                })
                .withStackTraceContaining("uq_coin_symbol");
    }

    /** {@code fk_crypto_pair_base_coin}: a pair cannot name a coin that does not exist. */
    @Test
    void BR07_aPairOfAnUnknownCoin_isRefused() {
        Coin usdt = coins.save(Coin.fromExchange("USDT"));
        CryptoPair orphan = CryptoPair.register(UUID.randomUUID(), usdt.getId(), "GHOSTUSDT");

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> {
                    pairs.save(orphan);
                    pairs.flush();
                })
                .withStackTraceContaining("fk_crypto_pair_base_coin");
    }

    /** {@code ck_crypto_pair_status}: a status the model does not name is refused by the database. */
    @Test
    void BR07_aStatusTheModelDoesNotName_isRefused() {
        CryptoPair pair = pairs.save(pairOf("SOL", "USDT", "SOLUSDT"));
        em.flush();

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> sql.sql("update crypto_pair set pair_status = 'DELISTED' where pair_id = ?")
                        .param(pair.getId())
                        .update())
                .withStackTraceContaining("ck_crypto_pair_status");
    }

    /**
     * BR-07: a market lists only ACTIVE pairs with that market switched on, lowest display order first.
     * A pair enabled on Spot only is absent from futures, and an INACTIVE pair from both.
     */
    @Test
    void BR07_theEnabledList_holdsActivePairsOnThatMarketInDisplayOrder() {
        CryptoPair btc = pairs.save(pairOf("BTC", "USDT", "BTCUSDT"));
        CryptoPair eth = pairs.save(pairOf("ETH", "USDT", "ETHUSDT"));
        CryptoPair sol = pairs.save(pairOf("SOL", "USDT", "SOLUSDT"));
        em.flush();
        enable(btc, true, true, 2);
        enable(eth, true, false, 1);
        enable(sol, true, true, 0);
        sql.sql("update crypto_pair set pair_status = 'INACTIVE' where pair_id = ?")
                .param(sol.getId())
                .update();
        em.clear();

        assertThat(pairs.findEnabledOn(MarketType.SPOT))
                .extracting(CryptoPair::getSymbol)
                .containsExactly("ETHUSDT", "BTCUSDT");
        assertThat(pairs.findEnabledOn(MarketType.FUTURES))
                .extracting(CryptoPair::getSymbol)
                .containsExactly("BTCUSDT");
        CryptoPair readBack = pairs.findBySymbol("BTCUSDT").orElseThrow();
        assertThat(readBack.isEnabledOn(MarketType.FUTURES)).isTrue();
        assertThat(readBack.getPairStatus()).isEqualTo(PairStatus.ACTIVE);
        assertThat(pairs.findBySymbol("SOLUSDT").orElseThrow().isEnabledOn(MarketType.SPOT))
                .as("an INACTIVE pair is enabled nowhere, whatever its switches")
                .isFalse();
    }

    private CryptoPair pairOf(String base, String quote, String symbol) {
        Coin baseCoin = coins.findBySymbol(base).orElseGet(() -> coins.save(Coin.fromExchange(base)));
        Coin quoteCoin = coins.findBySymbol(quote).orElseGet(() -> coins.save(Coin.fromExchange(quote)));
        coins.flush();
        return CryptoPair.register(baseCoin.getId(), quoteCoin.getId(), symbol);
    }

    /** The administrator's switches (T-085 builds their screen); written directly here. */
    private void enable(CryptoPair pair, boolean spot, boolean futures, int displayOrder) {
        sql.sql("""
                        update crypto_pair set pair_status = 'ACTIVE', is_spot_enabled = ?, is_futures_enabled = ?,
                               max_leverage = ?, display_order = ?
                         where pair_id = ?""")
                .params(spot, futures, futures ? 125 : null, displayOrder, pair.getId())
                .update();
    }
}
