package com.cryptopilot.watchlist.service.impl;

import com.cryptopilot.billing.EntitlementApi;
import com.cryptopilot.billing.model.enums.Feature;
import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.common.exception.ResourceNotFoundException;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.PairListing;
import com.cryptopilot.watchlist.dto.request.AddWatchlistItemRequest;
import com.cryptopilot.watchlist.dto.request.UpdateWatchlistItemRequest;
import com.cryptopilot.watchlist.dto.response.WatchlistItemResponse;
import com.cryptopilot.watchlist.dto.response.WatchlistResponse;
import com.cryptopilot.watchlist.entity.Watchlist;
import com.cryptopilot.watchlist.repository.WatchlistRepository;
import com.cryptopilot.watchlist.service.WatchlistService;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The watchlist use cases of UC-12.
 *
 * <p>Ownership is part of every query: a row is looked up by its key <em>and</em> the caller, so another Trader's row
 * is "not found" (MSG41) rather than "forbidden", which would confirm that the key exists.
 *
 * <p>An add counts the caller's rows and inserts the new one in one transaction under {@link WatchlistLock}, so two
 * concurrent adds at {@code max − 1} cannot both pass {@code WATCHLIST_MAX} (D-63). A pair is watched once:
 * {@code uq_watchlist_user_pair} enforces it, and a violation that slips past the check is answered MSG45 like the
 * check itself. Rows above a lowered limit are kept; only new adds are refused (BR-64).
 *
 * <p>Rule: BR-07, BR-15, BR-16, BR-62, BR-64; UC-12; SRS 3.4.1; D-63, D-75.
 *
 * <p>Reference: OWASP Foundation (2023). <i>API Security Top 10</i>, API1:2023 "Broken Object Level Authorization".
 * Kleppmann, M. (2017). <i>Designing Data-Intensive Applications</i>. O'Reilly, ch. 7 (write skew). Fowler, M. (2002).
 * <i>Patterns of Enterprise Application Architecture</i>. Addison-Wesley, "Service Layer", "Repository".
 */
@Service
@RequiredArgsConstructor
public class WatchlistServiceImpl implements WatchlistService {

    private final WatchlistRepository watchlist;
    private final WatchlistLock lock;
    private final MarketApi market;
    private final EntitlementApi entitlements;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public WatchlistResponse list(UUID userId) {
        List<Watchlist> rows = watchlist.findByUserIdOrderBySortOrderAscAddedAtAsc(userId);
        Map<UUID, PairListing> pairs =
                listings(rows.stream().map(Watchlist::getPairId).collect(Collectors.toSet()));
        Map<UUID, Long> activeAlerts = new HashMap<>();
        for (Object[] row : watchlist.countActiveAlertsByWatchlist(userId)) {
            activeAlerts.put((UUID) row[0], ((Number) row[1]).longValue());
        }
        List<WatchlistItemResponse> items = rows.stream()
                .map(row -> toResponse(row, listingOf(row, pairs), activeAlerts.getOrDefault(row.getId(), 0L)))
                .toList();
        return new WatchlistResponse(items, entitlements.getLimit(userId, Feature.WATCHLIST_MAX));
    }

    @Override
    @Transactional
    public WatchlistItemResponse add(UUID userId, AddWatchlistItemRequest request) {
        PairListing pair = listings(Set.of(request.pairId())).get(request.pairId());
        if (pair == null || !pair.listed()) {
            throw new ResourceNotFoundException("CryptoPair", request.pairId());
        }
        lock.lock(userId);
        if (watchlist.existsByUserIdAndPairId(userId, pair.pairId())) {
            throw alreadyWatched(pair);
        }
        entitlements.requireWithinLimit(userId, Feature.WATCHLIST_MAX, watchlist.countByUserId(userId));
        Watchlist row = Watchlist.add(
                userId,
                pair.pairId(),
                request.label(),
                request.note(),
                watchlist.nextSortOrder(userId),
                clock.instant());
        try {
            watchlist.save(row);
            watchlist.flush();
        } catch (DataIntegrityViolationException raced) {
            // Only uq_watchlist_user_pair can refuse this insert; the pair and the owner exist.
            throw alreadyWatched(pair);
        }
        return toResponse(row, pair, 0L);
    }

    @Override
    @Transactional
    public WatchlistItemResponse update(UUID userId, UUID watchlistId, UpdateWatchlistItemRequest request) {
        Watchlist row = owned(userId, watchlistId);
        if (request.label() != null) {
            row.relabel(request.label());
        }
        if (request.note() != null) {
            row.annotate(request.note());
        }
        if (request.sortOrder() != null) {
            row.moveTo(request.sortOrder());
        }
        return toResponse(row, listingOf(row), watchlist.countActiveAlerts(row.getId()));
    }

    @Override
    @Transactional
    public void remove(UUID userId, UUID watchlistId, boolean confirmed, Long expectedAlertCount) {
        // Alerts are created under this lock, so none can join the row between the count and the delete (BR-16).
        lock.lock(userId);
        Watchlist row = owned(userId, watchlistId);
        long alerts = watchlist.countAlerts(row.getId());
        boolean unconfirmed = alerts > 0 && !confirmed;
        // Compare-and-set: the Trader confirmed a number of alerts; if it changed since, ask again with the new one.
        boolean stale = confirmed && expectedAlertCount != null && expectedAlertCount != alerts;
        if (unconfirmed || stale) {
            throw new BusinessException(
                    ErrorCode.WATCHLIST_REMOVAL_CONFIRMATION_REQUIRED,
                    "removing watchlist row " + row.getId() + " deletes " + alerts + " alerts and was not confirmed"
                            + (stale ? " for that number (expected " + expectedAlertCount + ")" : ""),
                    listingOf(row).symbol(),
                    alerts);
        }
        watchlist.delete(row);
    }

    private Watchlist owned(UUID userId, UUID watchlistId) {
        return watchlist
                .findByIdAndUserId(watchlistId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Watchlist", watchlistId));
    }

    private Map<UUID, PairListing> listings(Set<UUID> pairIds) {
        return market.pairListings(pairIds).stream()
                .collect(Collectors.toMap(PairListing::pairId, Function.identity()));
    }

    private PairListing listingOf(Watchlist row) {
        return listingOf(row, listings(Set.of(row.getPairId())));
    }

    // fk_watchlist_pair keeps every watched pair stored, so a missing listing is a defect, not a request error.
    private static PairListing listingOf(Watchlist row, Map<UUID, PairListing> pairs) {
        PairListing pair = pairs.get(row.getPairId());
        if (pair == null) {
            throw new IllegalStateException("watched pair " + row.getPairId() + " is not stored");
        }
        return pair;
    }

    private static BusinessException alreadyWatched(PairListing pair) {
        return new BusinessException(
                ErrorCode.WATCHLIST_PAIR_ALREADY_WATCHED,
                pair.symbol() + " is already in the watchlist",
                pair.symbol());
    }

    private static WatchlistItemResponse toResponse(Watchlist row, PairListing pair, long activeAlerts) {
        return new WatchlistItemResponse(
                row.getId(),
                row.getPairId(),
                pair.symbol(),
                pair.spotListed(),
                pair.futuresListed(),
                row.getLabel(),
                row.getNote(),
                row.getSortOrder(),
                activeAlerts,
                row.getAddedAt());
    }
}
