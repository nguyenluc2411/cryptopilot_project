package com.cryptopilot.market.repository;

import com.cryptopilot.market.LeverageTier;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads {@code leverage_bracket}, a table keyed by pair and bracket number and therefore not an entity
 * (TECHNICAL_DESIGN 5.5). Writing it is the administration of UC-46 (T-085).
 *
 * <p>Rule: BR-26, BR-27.
 */
@Repository
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class LeverageBracketRepository {

    private final JdbcClient sql;

    /** The pair's brackets, smallest notional first. */
    public List<LeverageTier> findByPair(UUID pairId) {
        return sql.sql("""
                        select bracket_no, notional_floor, notional_cap, max_leverage, maintenance_margin_rate,
                               maintenance_amount
                          from leverage_bracket
                         where pair_id = ?
                         order by bracket_no""")
                .param(pairId)
                .query((row, index) -> new LeverageTier(
                        row.getInt("bracket_no"),
                        row.getBigDecimal("notional_floor"),
                        row.getBigDecimal("notional_cap"),
                        row.getInt("max_leverage"),
                        row.getBigDecimal("maintenance_margin_rate"),
                        row.getBigDecimal("maintenance_amount")))
                .list();
    }
}
