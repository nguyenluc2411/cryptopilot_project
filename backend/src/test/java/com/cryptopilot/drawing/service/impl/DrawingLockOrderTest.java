package com.cryptopilot.drawing.service.impl;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cryptopilot.common.exception.ResourceNotFoundException;
import com.cryptopilot.common.lock.UserLock;
import com.cryptopilot.drawing.dto.request.ChartPointRequest;
import com.cryptopilot.drawing.dto.request.CreateChartDrawingRequest;
import com.cryptopilot.drawing.dto.request.CreateChartLayoutRequest;
import com.cryptopilot.drawing.dto.request.DrawingStyleRequest;
import com.cryptopilot.drawing.entity.ChartLayout;
import com.cryptopilot.drawing.model.enums.DrawingType;
import com.cryptopilot.drawing.model.enums.LineStyle;
import com.cryptopilot.drawing.repository.ChartDrawingRepository;
import com.cryptopilot.drawing.repository.ChartLayoutRepository;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.PairListing;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import tools.jackson.databind.json.JsonMapper;

/**
 * A layout is looked up under the module lock its delete takes, so a delete cannot slip between the lookup and the
 * rows written for the layout: a drawing added to it, or the drawings copied from it.
 *
 * <p>Rule: D-149; T-119.
 */
class DrawingLockOrderTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID PAIR = UUID.randomUUID();
    private static final UUID LAYOUT = UUID.randomUUID();

    private final UserLock lock = mock(UserLock.class);
    private final ChartLayoutRepository layouts = mock(ChartLayoutRepository.class);
    private final ChartDrawingRepository drawings = mock(ChartDrawingRepository.class);
    private final MarketApi market = mock(MarketApi.class);

    @Test
    void D149_aDrawing_findsItsLayoutUnderTheLockALayoutDeleteTakes() {
        ChartDrawingServiceImpl service = new ChartDrawingServiceImpl(
                drawings, layouts, lock, JsonMapper.builder().build());
        when(layouts.findByIdAndUserId(LAYOUT, USER)).thenReturn(Optional.empty());
        CreateChartDrawingRequest request = new CreateChartDrawingRequest(
                LAYOUT,
                DrawingType.HORIZONTAL_LINE,
                List.of(new ChartPointRequest(Instant.parse("2026-11-10T00:00:00Z"), BigDecimal.TEN)),
                new DrawingStyleRequest("#2962FF", 2, LineStyle.SOLID, null, false, false, null, null),
                null,
                null,
                null);

        assertThatThrownBy(() -> service.create(USER, request)).isInstanceOf(ResourceNotFoundException.class);

        InOrder order = inOrder(lock, layouts);
        order.verify(lock).lock(ChartDrawingServiceImpl.LOCK_SCOPE, USER);
        order.verify(layouts).findByIdAndUserId(LAYOUT, USER);
    }

    @Test
    void D149_aCopy_findsItsSourceUnderTheLockALayoutDeleteTakes() {
        ChartLayoutServiceImpl service = new ChartLayoutServiceImpl(layouts, drawings, market, lock);
        when(market.pairListings(anyCollection())).thenReturn(List.of(new PairListing(PAIR, "BTCUSDT", true, false)));
        when(layouts.findByIdAndUserId(LAYOUT, USER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(USER, new CreateChartLayoutRequest(PAIR, null, LAYOUT)))
                .isInstanceOf(ResourceNotFoundException.class);

        InOrder order = inOrder(lock, layouts);
        order.verify(lock).lock(ChartDrawingServiceImpl.LOCK_SCOPE, USER);
        order.verify(layouts).findByIdAndUserId(LAYOUT, USER);
    }

    @Test
    void D149_aLayoutDelete_takesTheLockBeforeItFindsTheLayout() {
        ChartLayoutServiceImpl service = new ChartLayoutServiceImpl(layouts, drawings, market, lock);
        ChartLayout layout = ChartLayout.open(USER, PAIR, 1, null);
        when(layouts.findByIdAndUserId(LAYOUT, USER)).thenReturn(Optional.of(layout));

        service.delete(USER, LAYOUT);

        InOrder order = inOrder(lock, layouts);
        order.verify(lock).lock(ChartDrawingServiceImpl.LOCK_SCOPE, USER);
        order.verify(layouts).findByIdAndUserId(LAYOUT, USER);
        order.verify(layouts).delete(layout);
    }
}
