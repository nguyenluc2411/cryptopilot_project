package com.cryptopilot.trading.repository;

import com.cryptopilot.user.AccountActivityCounter;
import java.util.Map;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * A user's trading plans, in every status, and journal trades, simulated or manual, for SCR-35.
 *
 * <p>Rule: SRS 3.11.2; UC-42.
 */
@Repository
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class TradingActivityCounter implements AccountActivityCounter {

    private final JdbcClient sql;

    @Override
    public Map<String, Long> count(UUID userId) {
        return sql.sql("""
                        select (select count(*) from trading_plan where user_id = :user) as plans,
                               (select count(*) from trading_journal where user_id = :user) as trades""")
                .param("user", userId)
                .query((row, index) -> Map.of("plans", row.getLong("plans"), "trades", row.getLong("trades")))
                .single();
    }
}
