package com.cryptopilot.market.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.market.LeverageTier;
import com.cryptopilot.market.MarketTestData;
import com.cryptopilot.support.TestcontainersConfig;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/** The leverage brackets of a pair, as {@code leverage_bracket} stores them. */
@SpringBootTest
@Import(TestcontainersConfig.class)
@Transactional
class LeverageBracketRepositoryTest {

    @Autowired
    private LeverageBracketRepository brackets;

    @Autowired
    private JdbcClient sql;

    @Test
    void BR27_aPairsBrackets_areReadSmallestNotionalFirstWithEveryColumn() {
        UUID pair = new MarketTestData(sql, Instant.now()).pair("LBRUSDT", false, true, null, "TRADING", 0);
        bracket(pair, 2, "50000", "250000", 100, "0.005", "50");
        bracket(pair, 1, "0", "50000", 125, "0.004", "0");

        List<LeverageTier> read = brackets.findByPair(pair);

        assertThat(read).extracting(LeverageTier::bracketNo).containsExactly(1, 2);
        LeverageTier second = read.get(1);
        assertThat(second.notionalFloor()).isEqualByComparingTo("50000");
        assertThat(second.notionalCap()).isEqualByComparingTo("250000");
        assertThat(second.maxLeverage()).isEqualTo(100);
        assertThat(second.maintenanceMarginRate()).isEqualByComparingTo("0.005");
        assertThat(second.maintenanceAmount()).isEqualByComparingTo("50");
    }

    @Test
    void BR26_aPairWithoutBrackets_hasNone() {
        assertThat(brackets.findByPair(UUID.randomUUID())).isEmpty();
    }

    private void bracket(UUID pair, int no, String floor, String cap, int maxLeverage, String rate, String amount) {
        sql.sql("""
                        insert into leverage_bracket (pair_id, bracket_no, notional_floor, notional_cap, max_leverage,
                                                      maintenance_margin_rate, maintenance_amount, updated_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(
                        pair,
                        no,
                        new BigDecimal(floor),
                        new BigDecimal(cap),
                        maxLeverage,
                        new BigDecimal(rate),
                        new BigDecimal(amount),
                        Instant.now().atOffset(ZoneOffset.UTC))
                .update();
    }
}
