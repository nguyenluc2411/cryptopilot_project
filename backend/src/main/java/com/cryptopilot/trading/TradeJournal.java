package com.cryptopilot.trading;

import com.cryptopilot.trading.model.enums.PositionStatus;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Where the positions of filled plans are kept. The {@code trading} module declares it and the module that owns the
 * journal implements it, so the dependency points from the journal to here, never back. Unlike the optional ports of
 * other modules, a bean must exist: a fill never commits without its journal record.
 *
 * <p>Rule: BR-34, BR-35, BR-62, BR-29; UC-20; D-63, D-97, D-119.
 */
public interface TradeJournal {

    /**
     * Records the OPEN position of a plan that has just filled, inside the transaction of that fill, so the fill and
     * its record commit or roll back together. A plan opens at most one position.
     *
     * @return the position as recorded, with its journal id and version
     * @throws org.springframework.dao.DataIntegrityViolationException when the plan already has a record
     */
    OpenTrade openSimulatedTrade(SimulatedTradeOpening opening);

    /** Every OPEN simulated position, which the matching engine is rebuilt from at start-up. */
    List<OpenTrade> openSimulatedTrades();

    /** The position if it is still OPEN; empty once closed or when there is none. */
    Optional<OpenTrade> openTrade(UUID positionId);

    /** The OPEN position of the plan; empty when the plan never filled or its position is closed (UC-21). */
    Optional<OpenTrade> openTradeOfPlan(UUID planId);

    /**
     * Closes an OPEN position with a compare-and-set on its status and version, inside the caller's transaction: of
     * two racing closes, or a close and a replay of the same exit, exactly one changes the row.
     *
     * @return whether this call closed it; {@code false} when it was no longer OPEN at that version
     */
    boolean closeSimulatedTrade(SimulatedTradeClosing closing);

    /** The plans whose simulated position is OPEN, of one user: the open half of {@code ACTIVE_PLAN_MAX} (BR-62). */
    Set<UUID> openPositionPlans(UUID userId);

    /** The position of the plan, or empty when the plan never filled. */
    Optional<RecordedPosition> positionOf(UUID planId);

    /** The position status of each plan that has a record; plans without one are absent from the map. */
    Map<UUID, PositionStatus> positionStatuses(Collection<UUID> planIds);
}
