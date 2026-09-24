package com.cryptopilot.schema;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.cryptopilot.support.TestcontainersConfig;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * The status columns V8 adds to {@code crypto_pair} (Q-15): the normalized status accepts the system's three
 * words and nothing from the exchange's vocabulary, while the raw column takes Binance's text as it comes.
 * {@link SchemaColumnContractTest} and {@link EnumCheckConstraintTest} pin the shapes; this class breaks the
 * constraints to prove them.
 *
 * <p>Every test rolls back.
 *
 * <p>Rule: NSF-01; Q-15.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
@Transactional
class PairExchangeStatusMigrationTest {

    @Autowired
    private JdbcClient jdbc;

    private UUID pair;

    @BeforeEach
    void aPair() {
        OffsetDateTime now = OffsetDateTime.parse("2026-09-24T00:05:00Z");
        UUID base = UUID.randomUUID();
        UUID quote = UUID.randomUUID();
        for (Object[] coin : new Object[][] {{base, "V8BASE"}, {quote, "V8QUOTE"}}) {
            jdbc.sql("insert into coin (coin_id, symbol, coin_name, created_at, updated_at) values (?, ?, ?, ?, ?)")
                    .params(coin[0], coin[1], coin[1], now, now)
                    .update();
        }
        pair = UUID.randomUUID();
        jdbc.sql("""
                        insert into crypto_pair (pair_id, base_coin_id, quote_coin_id, symbol, pair_status,
                                                 created_at, updated_at)
                        values (?, ?, ?, 'V8BASEV8QUOTE', 'INACTIVE', ?, ?)""").params(pair, base, quote, now, now).update();
    }

    /** The three normalized words, with any raw text beside them, are accepted on both markets. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"TRADING", "NOT_TRADING", "DELISTED"})
    void Q15_theNormalizedStatuses_areAccepted(String status) {
        assertThatCode(() -> jdbc.sql("""
                                update crypto_pair set spot_exchange_status = ?, spot_exchange_status_raw = 'END_OF_DAY',
                                       futures_exchange_status = ?, futures_exchange_status_raw = 'SETTLING'
                                 where pair_id = ?""").params(status, status, pair).update())
                .doesNotThrowAnyException();
    }

    /** Binance's own words are not statuses of this system: the Spot constraint refuses them. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"BREAK", "HALT", "END_OF_DAY", "trading"})
    void Q15_theExchangesVocabulary_isRefusedOnSpot(String vendorWord) {
        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> jdbc.sql("update crypto_pair set spot_exchange_status = ? where pair_id = ?")
                        .params(vendorWord, pair)
                        .update())
                .withStackTraceContaining("ck_crypto_pair_spot_exchange_status");
    }

    /** And the futures constraint refuses them too. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"BREAK", "PENDING_TRADING", "SETTLING"})
    void Q15_theExchangesVocabulary_isRefusedOnFutures(String vendorWord) {
        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> jdbc.sql("update crypto_pair set futures_exchange_status = ? where pair_id = ?")
                        .params(vendorWord, pair)
                        .update())
                .withStackTraceContaining("ck_crypto_pair_futures_exchange_status");
    }

    /** A pair never synchronised has no status and no sync instant; null is allowed everywhere. */
    @Test
    void Q15_aPairNeverSynchronised_holdsNulls() {
        assertThatCode(() -> jdbc.sql("""
                                update crypto_pair set spot_exchange_status = null, futures_exchange_status = null,
                                       spot_exchange_status_raw = null, last_synced_at = null
                                 where pair_id = ?""").param(pair).update()).doesNotThrowAnyException();
    }
}
