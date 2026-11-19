package com.cryptopilot.trading.service.impl;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.repository.PlanIncidentQueries;
import com.cryptopilot.trading.service.PlanIncidentService;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads who a market incident's notices reach among the plans' Traders (D-158), through {@link PlanIncidentQueries}.
 *
 * <p>Rule: NSF-03; D-158.
 */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class PlanIncidentServiceImpl implements PlanIncidentService {

    private final PlanIncidentQueries queries;

    @Override
    @Transactional(readOnly = true)
    public List<UUID> tradersOn(MarketType market) {
        return queries.tradersOn(market);
    }
}
