package com.cryptopilot.trading.repository;

import com.cryptopilot.trading.entity.TradingPlan;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The trading plans. {@link TradingPlan} is the aggregate root, so its warnings are saved and deleted through it and
 * have no repository of their own. Only what T-038 needs is here; the listing queries and the compare-and-set status
 * update of TECHNICAL_DESIGN 5.5 come with the tasks that call them (T-039, T-042).
 *
 * <p>Rule: BR-31, BR-32; TECHNICAL_DESIGN 5.5; D-23.
 */
public interface TradingPlanRepository extends Repository<TradingPlan, UUID> {

    /** Inserts a new plan or updates a loaded one, with its warnings. */
    TradingPlan save(TradingPlan plan);

    @Transactional(readOnly = true)
    Optional<TradingPlan> findById(UUID id);
}
