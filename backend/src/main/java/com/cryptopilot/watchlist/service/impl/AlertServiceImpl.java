package com.cryptopilot.watchlist.service.impl;

import com.cryptopilot.billing.EntitlementApi;
import com.cryptopilot.billing.model.enums.Feature;
import com.cryptopilot.common.exception.ResourceNotFoundException;
import com.cryptopilot.common.lock.UserLock;
import com.cryptopilot.common.web.PageResponse;
import com.cryptopilot.common.web.Paging;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.PairListing;
import com.cryptopilot.market.TradablePair;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.watchlist.dto.request.AddWatchlistItemRequest;
import com.cryptopilot.watchlist.dto.request.CreateAlertRequest;
import com.cryptopilot.watchlist.dto.request.UpdateAlertRequest;
import com.cryptopilot.watchlist.dto.response.AlertResponse;
import com.cryptopilot.watchlist.entity.Alert;
import com.cryptopilot.watchlist.entity.Watchlist;
import com.cryptopilot.watchlist.exception.IllegalAlertStateException;
import com.cryptopilot.watchlist.model.AlertDefinition;
import com.cryptopilot.watchlist.model.enums.AlertStatus;
import com.cryptopilot.watchlist.model.enums.AlertType;
import com.cryptopilot.watchlist.repository.AlertRepository;
import com.cryptopilot.watchlist.repository.WatchlistRepository;
import com.cryptopilot.watchlist.service.AlertService;
import com.cryptopilot.watchlist.service.WatchlistService;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The alert use cases of UC-13 and UC-14.
 *
 * <p>Ownership is part of every query: an alert is looked up by its key <em>and</em> the caller, so another Trader's
 * alert is "not found" (MSG41) rather than "forbidden", which would confirm that the key exists.
 *
 * <p>Each request is checked in the same order: the rule's fields (400), the plan's features (MSG29, 403), the pair on
 * the chosen market (MSG41, 404), then the limits under the lock (MSG27, 409). Every path that adds an ACTIVE alert —
 * create, resume, and an edit that brings a TRIGGERED or EXPIRED alert back — counts the ACTIVE alerts and writes
 * under {@link UserLock}, so two requests at {@code max − 1} cannot both pass {@code ACTIVE_ALERT_MAX} (D-63).
 * A create on a pair not yet watched adds the watchlist row first, in the same transaction and under the same lock,
 * which is the one the watchlist add takes: one key per Trader, so no lock order can deadlock. If either limit
 * refuses, the transaction rolls back and nothing is created (BR-16).
 *
 * <p>Pausing and deleting only lower the count and take no lock. TRIGGERED and EXPIRED are never set here: they are the
 * engine's (NSF-06).
 *
 * <p>Rule: BR-07, BR-16, BR-17, BR-19, BR-20, BR-62; UC-13, UC-14; SRS 3.4.2, 3.4.3; D-63; A-40.
 *
 * <p>Reference: OWASP Foundation (2023). <i>API Security Top 10</i>, API1:2023 "Broken Object Level Authorization".
 * Kleppmann, M. (2017). <i>Designing Data-Intensive Applications</i>. O'Reilly, ch. 7 (write skew: a count followed by
 * a write needs a lock). Evans, E. (2003). <i>Domain-Driven Design</i>. Addison-Wesley, ch. 6 "Aggregates".
 */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class AlertServiceImpl implements AlertService {

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final AlertRepository alerts;
    private final WatchlistRepository watchlist;
    private final WatchlistService watchlistItems;
    private final UserLock lock;
    private final MarketApi market;
    private final EntitlementApi entitlements;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<AlertResponse> list(
            UUID userId, AlertStatus status, AlertType type, Integer page, Integer pageSize) {
        Pageable request = Paging.of(page, pageSize, NEWEST_FIRST);
        Page<Alert> found;
        if (status == null && type == null) {
            found = alerts.findByUserId(userId, request);
        } else if (type == null) {
            found = alerts.findByUserIdAndStatus(userId, status, request);
        } else if (status == null) {
            found = alerts.findByUserIdAndType(userId, type, request);
        } else {
            found = alerts.findByUserIdAndStatusAndType(userId, status, type, request);
        }
        Map<UUID, UUID> pairOfRow = watchlist.findByUserIdOrderBySortOrderAscAddedAtAsc(userId).stream()
                .collect(Collectors.toMap(Watchlist::getId, Watchlist::getPairId));
        Map<UUID, PairListing> pairs = market.pairListings(Set.copyOf(pairOfRow.values())).stream()
                .collect(Collectors.toMap(PairListing::pairId, Function.identity()));
        return PageResponse.of(found, alert -> {
            UUID pairId = pairOfRow.get(alert.getWatchlistId());
            return toResponse(alert, pairId, pairs.get(pairId).symbol());
        });
    }

    @Override
    @Transactional
    public AlertResponse create(UUID userId, CreateAlertRequest request) {
        AlertDefinition rule = AlertRuleValidator.validate(request.rule(), clock.instant());
        requireRuleFeatures(userId, rule);
        TradablePair pair = tradable(request.pairId(), rule.market());
        AlertDefinition onTick = AlertRuleValidator.onTick(rule, pair.filters());

        lock.lock(userId);
        UUID watchlistId = watchlist
                .findByUserIdAndPairId(userId, pair.pairId())
                .map(Watchlist::getId)
                // BR-16: the pair is watched first; the watchlist checks its own limit under the same lock.
                .orElseGet(() -> watchlistItems
                        .add(userId, new AddWatchlistItemRequest(pair.pairId(), null, null))
                        .id());
        requireRoomForAnActiveAlert(userId);
        Alert alert = alerts.save(Alert.create(userId, watchlistId, onTick));
        return toResponse(alert, pair.pairId(), pair.symbol());
    }

    @Override
    @Transactional
    public AlertResponse update(UUID userId, UUID alertId, UpdateAlertRequest request) {
        // Taken before the alert is read, so the status that decides a re-activation cannot change under us.
        lock.lock(userId);
        Alert alert = owned(userId, alertId);
        AlertDefinition rule = AlertRuleValidator.validate(request.rule(), clock.instant());
        requireRuleFeatures(userId, rule);
        TradablePair pair = tradable(pairOf(alert), rule.market());
        AlertDefinition onTick = AlertRuleValidator.onTick(rule, pair.filters());
        if (alert.getStatus().reactivatesOnEdit()) {
            requireRoomForAnActiveAlert(userId);
        }
        alert.redefine(onTick);
        return toResponse(alerts.save(alert), pair.pairId(), pair.symbol());
    }

    @Override
    @Transactional
    public AlertResponse pause(UUID userId, UUID alertId) {
        Alert alert = owned(userId, alertId);
        alert.pause();
        return toResponse(alerts.save(alert));
    }

    @Override
    @Transactional
    public AlertResponse resume(UUID userId, UUID alertId) {
        lock.lock(userId);
        Alert alert = owned(userId, alertId);
        if (alert.getStatus() != AlertStatus.PAUSED) {
            // Before the count, so resuming twice is a state error and not a limit error.
            throw new IllegalAlertStateException(alert.getId(), alert.getStatus(), AlertStatus.ACTIVE);
        }
        AlertRuleValidator.requireNotExpired(alert.getExpiresAt(), clock.instant());
        // A plan change may have removed what the alert needs (BR-64); delivery channels are NSF-15's to drop.
        requireFeatures(userId, alert.getType(), alert.getMarket());
        TradablePair pair = tradable(pairOf(alert), alert.getMarket());
        requireRoomForAnActiveAlert(userId);
        alert.resume();
        return toResponse(alerts.save(alert), pair.pairId(), pair.symbol());
    }

    @Override
    @Transactional
    public void delete(UUID userId, UUID alertId) {
        alerts.delete(owned(userId, alertId));
    }

    private Alert owned(UUID userId, UUID alertId) {
        return alerts.findByIdAndUserId(alertId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Alert", alertId));
    }

    private void requireRuleFeatures(UUID userId, AlertDefinition rule) {
        requireFeatures(userId, rule.type(), rule.market());
        if (rule.notifyEmail() || rule.notifyPush()) {
            entitlements.requireFeature(userId, Feature.EXTERNAL_ALERT_CHANNELS);
        }
    }

    private void requireFeatures(UUID userId, AlertType type, MarketType marketType) {
        if (type == AlertType.INDICATOR) {
            entitlements.requireFeature(userId, Feature.INDICATOR_ALERT);
        }
        if (marketType == MarketType.FUTURES) {
            entitlements.requireFeature(userId, Feature.FUTURES_ANALYSIS);
        }
    }

    /** The pair, when it is still enabled on the market with known filters (BR-07); MSG41 otherwise. */
    private TradablePair tradable(UUID pairId, MarketType marketType) {
        return market.tradablePair(pairId, marketType)
                .orElseThrow(() -> new ResourceNotFoundException("CryptoPair", pairId));
    }

    private void requireRoomForAnActiveAlert(UUID userId) {
        entitlements.requireWithinLimit(
                userId, Feature.ACTIVE_ALERT_MAX, alerts.countByUserIdAndStatus(userId, AlertStatus.ACTIVE));
    }

    // fk_alert_watchlist keeps the row of every stored alert, so a missing row is a defect, not a request error.
    private UUID pairOf(Alert alert) {
        return watchlist
                .findByIdAndUserId(alert.getWatchlistId(), alert.getUserId())
                .map(Watchlist::getPairId)
                .orElseThrow(() -> new IllegalStateException("alert " + alert.getId() + " has no watchlist row"));
    }

    private AlertResponse toResponse(Alert alert) {
        UUID pairId = pairOf(alert);
        String symbol = market.pairListings(Set.of(pairId)).stream()
                .map(PairListing::symbol)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("watched pair " + pairId + " is not stored"));
        return toResponse(alert, pairId, symbol);
    }

    private static AlertResponse toResponse(Alert alert, UUID pairId, String symbol) {
        return new AlertResponse(
                alert.getId(),
                alert.getWatchlistId(),
                pairId,
                symbol,
                alert.getMarket(),
                alert.getType(),
                alert.getIndicator(),
                alert.getTimeframe(),
                alert.getCondition(),
                alert.getThreshold(),
                alert.getTriggerMode(),
                alert.getCooldownMinutes(),
                alert.isNotifyInApp(),
                alert.isNotifyEmail(),
                alert.isNotifyPush(),
                alert.getStatus(),
                alert.getTriggerCount(),
                alert.getLastTriggeredAt(),
                alert.getExpiresAt(),
                alert.getCreatedAt());
    }
}
