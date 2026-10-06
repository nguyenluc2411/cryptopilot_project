package com.cryptopilot.market.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.market.MarketApi;
import com.cryptopilot.support.TestcontainersConfig;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A coin is committed by itself, whatever becomes of the caller's transaction: that is what keeps the symbol
 * synchronisation and the opening of paper accounts from deadlocking on {@code uq_coin_symbol} (TR-04).
 *
 * <p>Rule: NSF-01; TR-04.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class CoinWriterTest {

    private static final List<String> SYMBOLS = List.of("ZZCWA", "ZZCWB");

    @Autowired
    private MarketApi market;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcClient sql;

    @AfterEach
    void removeCoins() {
        sql.sql("delete from coin where symbol in ('ZZCWA', 'ZZCWB')").update();
    }

    @Test
    void TR04_aStoredCoin_isCommittedBeforeTheCallerFinishes_andSurvivesItsRollback() {
        transactions.executeWithoutResult(status -> {
            assertThat(market.ensureCoins(SYMBOLS)).hasSize(2);
            status.setRollbackOnly();
        });

        assertThat(sql.sql("select count(*) from coin where symbol in ('ZZCWA', 'ZZCWB')")
                        .query(Long.class)
                        .single())
                .isEqualTo(2);
    }

    @Test
    void TR04_ensuringStoredCoinsAgain_storesNothingMore() {
        market.ensureCoins(SYMBOLS);

        market.ensureCoins(SYMBOLS);

        assertThat(sql.sql("select count(*) from coin where symbol in ('ZZCWA', 'ZZCWB')")
                        .query(Long.class)
                        .single())
                .isEqualTo(2);
    }
}
