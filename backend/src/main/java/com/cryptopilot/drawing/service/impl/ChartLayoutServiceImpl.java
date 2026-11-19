package com.cryptopilot.drawing.service.impl;

import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.common.exception.FieldValidationException;
import com.cryptopilot.common.exception.ResourceNotFoundException;
import com.cryptopilot.common.lock.UserLock;
import com.cryptopilot.drawing.dto.request.CreateChartLayoutRequest;
import com.cryptopilot.drawing.dto.request.RenameChartLayoutRequest;
import com.cryptopilot.drawing.dto.response.ChartLayoutResponse;
import com.cryptopilot.drawing.entity.ChartDrawing;
import com.cryptopilot.drawing.entity.ChartLayout;
import com.cryptopilot.drawing.repository.ChartDrawingRepository;
import com.cryptopilot.drawing.repository.ChartLayoutRepository;
import com.cryptopilot.drawing.service.ChartLayoutService;
import com.cryptopilot.market.MarketApi;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The chart layout use cases.
 *
 * <p>Ownership is part of every query, as for the drawings: another Trader's layout is "not found" (MSG41). A create
 * reads the numbers in use and inserts the new layout under the caller's lock of this module, so two concurrent creates
 * neither pass {@link #MAX_PER_PAIR} nor take the same number (D-63); the unique key on the number backs that up.
 *
 * <p>Rule: D-149; D-63; T-119.
 *
 * <p>Reference: OWASP Foundation (2023). <i>API Security Top 10</i>, API1:2023 "Broken Object Level Authorization".
 */
@Service
@RequiredArgsConstructor
public class ChartLayoutServiceImpl implements ChartLayoutService {

    /** The most layouts one Trader keeps on one pair (D-149). */
    static final int MAX_PER_PAIR = 10;

    private final ChartLayoutRepository layouts;
    private final ChartDrawingRepository drawings;
    private final MarketApi market;
    private final UserLock lock;

    @Override
    @Transactional(readOnly = true)
    public List<ChartLayoutResponse> list(UUID userId, UUID pairId) {
        List<ChartLayout> found = layouts.findByUserIdAndPairIdOrderBySeqNoAsc(userId, pairId);
        if (found.isEmpty()) {
            return List.of();
        }
        Map<UUID, Long> counts = drawings
                .countByLayout(found.stream().map(ChartLayout::getId).toList())
                .stream()
                .collect(Collectors.toMap(row -> (UUID) row[0], row -> (Long) row[1]));
        return found.stream()
                .map(layout -> toResponse(layout, counts.getOrDefault(layout.getId(), 0L)))
                .toList();
    }

    @Override
    @Transactional
    public ChartLayoutResponse create(UUID userId, CreateChartLayoutRequest request) {
        boolean listed = market.pairListings(List.of(request.pairId())).stream()
                .anyMatch(pair -> pair.pairId().equals(request.pairId()) && pair.listed());
        if (!listed) {
            throw new ResourceNotFoundException("CryptoPair", request.pairId());
        }
        ChartLayout source = request.copyFrom() == null ? null : owned(userId, request.copyFrom());
        if (source != null && !source.getPairId().equals(request.pairId())) {
            throw new FieldValidationException(
                    "layout " + source.getId() + " is on another pair", Map.of("copyFrom", "MSG01"));
        }
        lock.lock(ChartDrawingServiceImpl.LOCK_SCOPE, userId);
        List<Integer> taken = layouts.seqNos(userId, request.pairId());
        if (taken.size() >= MAX_PER_PAIR) {
            throw new BusinessException(
                    ErrorCode.CHART_LAYOUT_LIMIT_REACHED,
                    "the Trader already has " + taken.size() + " layouts on pair " + request.pairId(),
                    MAX_PER_PAIR);
        }
        ChartLayout layout = ChartLayout.open(userId, request.pairId(), smallestFree(taken), request.name());
        layouts.save(layout);
        long copied = 0;
        if (source != null) {
            List<ChartDrawing> copies =
                    drawings.findByUserIdAndLayoutIdOrderByCreatedAtAscIdAsc(userId, source.getId()).stream()
                            .map(drawing -> drawing.copyInto(layout.getId()))
                            .toList();
            drawings.saveAll(copies);
            copied = copies.size();
        }
        layouts.flush();
        return toResponse(layout, copied);
    }

    @Override
    @Transactional
    public ChartLayoutResponse rename(UUID userId, UUID layoutId, RenameChartLayoutRequest request) {
        ChartLayout layout = owned(userId, layoutId);
        layout.rename(request.name());
        layouts.flush();
        return toResponse(layout, drawings.countByLayoutId(layoutId));
    }

    @Override
    @Transactional
    public void delete(UUID userId, UUID layoutId) {
        layouts.delete(owned(userId, layoutId));
    }

    private ChartLayout owned(UUID userId, UUID layoutId) {
        return layouts.findByIdAndUserId(layoutId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("ChartLayout", layoutId));
    }

    /** The smallest number from 1 not in {@code taken}, which is ascending. */
    static int smallestFree(List<Integer> taken) {
        int next = 1;
        for (int seqNo : taken) {
            if (seqNo > next) {
                break;
            }
            next = seqNo + 1;
        }
        return next;
    }

    private static ChartLayoutResponse toResponse(ChartLayout layout, long drawingCount) {
        return new ChartLayoutResponse(
                layout.getId(),
                layout.getPairId(),
                layout.getSeqNo(),
                layout.getName(),
                drawingCount,
                layout.getCreatedAt(),
                layout.getUpdatedAt());
    }
}
