package com.cryptopilot.trading;

import com.cryptopilot.trading.model.enums.PositionStatus;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Where the positions of filled plans are kept. The {@code trading} module declares it and the module that owns the
 * journal implements it, so the dependency points from the journal to here, never back. Unlike the optional ports of
 * other modules, a bean must exist: a fill never commits without its journal record.
 *
 * <p>Rule: BR-34, BR-35, BR-62, BR-29; UC-20; D-63, D-97, D-119.
 */
public interface TradeJournal {

    /** The position of the plan, or empty when the plan never filled. */
    Optional<RecordedPosition> positionOf(UUID planId);

    /** The position status of each plan that has a record; plans without one are absent from the map. */
    Map<UUID, PositionStatus> positionStatuses(Collection<UUID> planIds);

    /**
     * Changes the stop loss and take profit of an OPEN position with a compare-and-set on its status and version,
     * inside the caller's transaction, and records the change in the position's history: of a change and an exit that
     * read the same version, exactly one changes the row (D-119 (5)).
     *
     * <p>Rule: BR-35; UC-21; D-119 (5), D-124.
     *
     * @return the position at its new version; empty when it was no longer OPEN at that version
     */
    Optional<OpenTrade> changeSimulatedLevels(SimulatedLevelChange change);

    /** The OPEN simulated positions of one user, with their current levels: the open risk of BR-29 (D-124). */
    List<OpenTrade> openSimulatedTrades(UUID userId);

    /** The current and original levels of the plan's position and the history of their changes (D-124). */
    Optional<PositionLevels> levelsOf(UUID planId);

    /**
     * Adds the learning data of the plan's entry fill to the record the paper fill has just opened, in the fill's
     * transaction (BR-70): the deviation from the planned entry, the risk, the setup scores and the indicators.
     *
     * @return the record's id; empty when the plan has no record
     */
    Optional<UUID> recordFillLearning(UUID planId, SimulatedFillLearning learning);
}
