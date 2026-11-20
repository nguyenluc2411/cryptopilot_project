package com.cryptopilot.market.event;

import com.cryptopilot.market.model.enums.MarketType;
import java.util.UUID;
import org.springframework.modulith.NamedInterface;

/**
 * The display-only fallback prices of a market whose exchange data is lost could not be read either (D-159, E9): the
 * screens show no price. Published once per incident; nothing else changes.
 *
 * <p>Exposed in the named interface {@code events}.
 *
 * @param market the market
 * @param incidentId its open incident
 * @param failure what failed, for the administrators
 */
@NamedInterface("events")
public record MarketFallbackUnavailable(MarketType market, UUID incidentId, String failure) {}
