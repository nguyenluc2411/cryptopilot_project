package com.cryptopilot.market.repository;

import com.cryptopilot.market.LeverageTier;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads {@code leverage_bracket}, a table keyed by pair and bracket number and therefore not an entity
 * (TECHNICAL_DESIGN 5.5), and writes it for the administration of UC-46 (T-085): a pair's brackets are replaced as a
 * whole, so the stored set is always one an administrator checked together.
 *
 * <p>Rule: BR-26, BR-27; UC-46.
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

    /** How many brackets each of these pairs has; a pair without any is left out. */
    public Map<UUID, Integer> countByPairs(Collection<UUID> pairIds) {
        Map<UUID, Integer> counts = new HashMap<>();
        if (pairIds.isEmpty()) {
            return counts;
        }
        sql.sql("select pair_id, count(*) as brackets from leverage_bracket where pair_id in (:pairs) group by pair_id")
                .param("pairs", pairIds)
                .query((row, index) -> counts.put(row.getObject("pair_id", UUID.class), row.getInt("brackets")))
                .list();
        return counts;
    }

    /**
     * Replaces the pair's brackets with these, numbered as given, in the caller's transaction (BR-31: a stored plan
     * keeps its own snapshot, so nothing else changes).
     *
     * @param updatedBy the administrator, recorded on every row
     */
    public void replace(UUID pairId, List<LeverageTier> brackets, UUID updatedBy, Instant now) {
        sql.sql("delete from leverage_bracket where pair_id = ?").param(pairId).update();
        Timestamp at = Timestamp.from(now);
        for (LeverageTier tier : brackets) {
            sql.sql("""
                            insert into leverage_bracket (pair_id, bracket_no, notional_floor, notional_cap, max_leverage,
                                                          maintenance_margin_rate, maintenance_amount, updated_by,
                                                          updated_at)
                            values (?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                    .params(
                            pairId,
                            tier.bracketNo(),
                            tier.notionalFloor(),
                            tier.notionalCap(),
                            tier.maxLeverage(),
                            tier.maintenanceMarginRate(),
                            tier.maintenanceAmount(),
                            updatedBy,
                            at)
                    .update();
        }
    }
}
