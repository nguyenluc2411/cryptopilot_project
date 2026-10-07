package com.cryptopilot.watchlist.service.impl;

import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.PairListing;
import com.cryptopilot.watchlist.event.AlertTriggered;
import com.cryptopilot.watchlist.model.PriceAlert;
import com.cryptopilot.watchlist.model.PriceAlertHit;
import com.cryptopilot.watchlist.model.PriceAlertRow;
import com.cryptopilot.watchlist.model.enums.AlertStatus;
import com.cryptopilot.watchlist.model.enums.AlertType;
import com.cryptopilot.watchlist.repository.AlertRepository;
import com.cryptopilot.watchlist.service.AlertTriggerService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads the ACTIVE PRICE alerts for the engine and records their triggers.
 *
 * <p>The database is the arbiter of a trigger, not the engine's memory and not a Redis lease: {@link #tryTrigger}
 * runs one conditional update ({@link AlertRepository#recordTrigger}), and only the call that changed the row
 * publishes {@link AlertTriggered}, inside the same transaction. A rollback therefore publishes nothing a
 * transactional listener would see.
 *
 * <p>Rule: NSF-06, BR-18, BR-19, BR-20; SRS 3.4.4; TECHNICAL_DESIGN 7.9; D-89.
 *
 * <p>Reference: Kleppmann, M. (2017). <i>Designing Data-Intensive Applications</i>. O'Reilly, ch. 7 (compare-and-set
 * against lost updates). Richardson, C. (2018). <i>Microservices Patterns</i>. Manning, ch. 5 (publish a domain event
 * in the transaction that changes the aggregate).
 */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class AlertTriggerServiceImpl implements AlertTriggerService {

    private static final Logger log = LoggerFactory.getLogger(AlertTriggerServiceImpl.class);

    private final AlertRepository alerts;
    private final MarketApi market;
    private final ApplicationEventPublisher events;

    @Override
    @Transactional(readOnly = true)
    public List<PriceAlert> activePriceAlerts() {
        List<PriceAlertRow> rows = alerts.findActivePriceAlerts();
        Map<UUID, String> symbols =
                symbols(rows.stream().map(PriceAlertRow::pairId).collect(Collectors.toSet()));
        return rows.stream()
                .filter(row -> symbols.containsKey(row.pairId()))
                .map(row -> row.withSymbol(symbols.get(row.pairId())))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PriceAlert> activePriceAlert(UUID alertId) {
        return alerts.findActivePriceAlert(alertId).flatMap(row -> Optional.ofNullable(
                        symbols(Set.of(row.pairId())).get(row.pairId()))
                .map(row::withSymbol));
    }

    @Override
    @Transactional(readOnly = true)
    public long activeAlertsNotEvaluated() {
        return alerts.countByStatusAndType(AlertStatus.ACTIVE, AlertType.INDICATOR);
    }

    @Override
    @Transactional
    public boolean tryTrigger(PriceAlertHit hit) {
        PriceAlert alert = hit.alert();
        int changed = alerts.recordTrigger(
                alert.alertId(), alert.version(), hit.observedValue(), hit.barOpenTime(), hit.at());
        if (changed == 0) {
            log.debug("NSF-06 alert {} not triggered: its row no longer allows it", alert.alertId());
            return false;
        }
        events.publishEvent(new AlertTriggered(
                alert.alertId(),
                alert.userId(),
                alert.pairId(),
                alert.symbol(),
                alert.market(),
                AlertType.PRICE,
                null,
                alert.condition(),
                alert.threshold(),
                hit.observedValue(),
                hit.at(),
                // The update ran on the version the engine read, so the stored count was this one.
                alert.triggerCount() + 1,
                alert.notifyInApp(),
                alert.notifyEmail(),
                alert.notifyPush()));
        return true;
    }

    private Map<UUID, String> symbols(Set<UUID> pairIds) {
        return market.pairListings(pairIds).stream()
                .collect(Collectors.toMap(PairListing::pairId, PairListing::symbol));
    }
}
