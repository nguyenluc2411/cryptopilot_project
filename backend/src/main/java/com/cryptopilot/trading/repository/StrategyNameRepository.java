package com.cryptopilot.trading.repository;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads the names of {@code trading_strategy}, the catalogue seeded by V3.
 *
 * <p>Rule: UC-25; TECHNICAL_DESIGN section 2.
 */
@Repository
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class StrategyNameRepository {

    private final JdbcClient sql;

    /** The name of each of these strategies that exists, by id. */
    public Map<UUID, String> names(Collection<UUID> strategyIds) {
        Map<UUID, String> names = new HashMap<>();
        if (strategyIds.isEmpty()) {
            return names;
        }
        sql.sql("select strategy_id, strategy_name from trading_strategy where strategy_id in (:ids)")
                .param("ids", strategyIds)
                .query(row -> {
                    names.put(row.getObject("strategy_id", UUID.class), row.getString("strategy_name"));
                });
        return names;
    }
}
