package com.cryptopilot.paper.service.impl;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.paper.repository.PaperIncidentQueries;
import com.cryptopilot.paper.service.PaperIncidentService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads what a market incident's notices need from the paper tables (D-159), through {@link PaperIncidentQueries}.
 *
 * <p>Rule: NSF-03; D-159.
 */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class PaperIncidentServiceImpl implements PaperIncidentService {

    private final PaperIncidentQueries queries;

    @Override
    @Transactional(readOnly = true)
    public List<UUID> tradersOn(MarketType market) {
        return queries.tradersOn(market);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Long> replayedFills(MarketType market, Instant from, Instant to) {
        return queries.replayedFills(market, from, to);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Long> liquidations(Instant from, Instant to) {
        return queries.liquidations(from, to);
    }
}
