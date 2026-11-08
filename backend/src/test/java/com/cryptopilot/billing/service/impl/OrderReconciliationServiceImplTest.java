package com.cryptopilot.billing.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cryptopilot.billing.client.GatewayUnavailableException;
import com.cryptopilot.billing.client.PaymentGateway;
import com.cryptopilot.billing.config.ReconcileProperties;
import com.cryptopilot.billing.entity.SubscriptionOrder;
import com.cryptopilot.billing.model.GatewayLookup;
import com.cryptopilot.billing.model.PaymentResult;
import com.cryptopilot.billing.model.enums.GatewayCode;
import com.cryptopilot.billing.model.enums.OrderStatus;
import com.cryptopilot.billing.repository.SubscriptionOrderRepository;
import com.cryptopilot.billing.service.PaymentSettlementService;
import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.common.exception.ResourceNotFoundException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;

/** Reconciliation decisions of NSF-13: only a confirmed payment settles, everything else expires when due. */
class OrderReconciliationServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-11-08T10:00:00Z");

    private static final ReconcileProperties PROPERTIES =
            new ReconcileProperties(true, Duration.ofMinutes(5), Duration.ofMinutes(5), 50);

    private final SubscriptionOrderRepository orders = mock(SubscriptionOrderRepository.class);
    private final PaymentSettlementService settlement = mock(PaymentSettlementService.class);
    private final PaymentGateway sepay = mock(PaymentGateway.class);
    private final List<PaymentGateway> installed = new ArrayList<>();

    /** The status each order code has, changed by the settlement stand-in as the real one would. */
    private final Map<String, OrderStatus> statuses = new HashMap<>();

    private OrderReconciliationServiceImpl service;

    @BeforeEach
    void setUp() {
        @SuppressWarnings("unchecked")
        ObjectProvider<PaymentGateway> gateways = mock(ObjectProvider.class);
        when(gateways.orderedStream()).thenAnswer(call -> installed.stream());
        when(sepay.code()).thenReturn(GatewayCode.SEPAY);
        when(sepay.configured()).thenReturn(true);
        when(sepay.cancel(anyString())).thenReturn(true);
        installed.add(sepay);
        when(orders.findByOrderCode(anyString())).thenAnswer(call -> {
            String code = call.getArgument(0);
            OrderStatus status = statuses.get(code);
            return status == null ? Optional.empty() : Optional.of(order(code, status));
        });
        when(settlement.expireIfDue(anyString())).thenAnswer(call -> {
            String code = call.getArgument(0);
            boolean due = statuses.get(code) == OrderStatus.PENDING && code.startsWith("DUE");
            if (due) {
                statuses.put(code, OrderStatus.EXPIRED);
            }
            return due;
        });
        when(settlement.withdraw(anyString())).thenAnswer(call -> {
            String code = call.getArgument(0);
            boolean pending = statuses.get(code) == OrderStatus.PENDING;
            if (pending) {
                statuses.put(code, OrderStatus.EXPIRED);
            }
            return pending;
        });
        doAnswer(call -> {
                    statuses.put(call.<PaymentResult>getArgument(0).orderCode(), OrderStatus.PAID);
                    return null;
                })
                .when(settlement)
                .settle(any());
        service = new OrderReconciliationServiceImpl(
                orders, gateways, settlement, PROPERTIES, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void NSF13_aConfirmedPayment_isSettledAsItsIpnWouldBe() {
        statuses.put("CP1", OrderStatus.PENDING);
        PaymentResult payment =
                new PaymentResult(GatewayCode.SEPAY, "CP1", true, new BigDecimal("99000"), "TX", "CAPTURED", "{}");
        when(sepay.lookup("CP1")).thenReturn(GatewayLookup.paid(payment));

        assertThat(service.reconcile("CP1")).isEqualTo(OrderStatus.PAID);

        verify(settlement).settle(payment);
        verify(settlement, never()).expireIfDue(anyString());
    }

    @Test
    void BR54_anUnpaidOrder_onlyExpires_whateverTheGatewaySays() {
        for (GatewayLookup.State state :
                List.of(GatewayLookup.State.AWAITING, GatewayLookup.State.CANCELLED, GatewayLookup.State.UNKNOWN)) {
            String code = "DUE-" + state;
            statuses.put(code, OrderStatus.PENDING);
            when(sepay.lookup(code)).thenReturn(GatewayLookup.of(state));

            assertThat(service.reconcile(code)).isEqualTo(OrderStatus.EXPIRED);
        }
        statuses.put("CP-OPEN", OrderStatus.PENDING);
        when(sepay.lookup("CP-OPEN")).thenReturn(GatewayLookup.of(GatewayLookup.State.CANCELLED));

        assertThat(service.reconcile("CP-OPEN")).isEqualTo(OrderStatus.PENDING);
        verify(settlement, never()).settle(any());
    }

    @Test
    void A03_anExpiredOrder_isAskedAboutAgain_andAPaymentStillSettlesIt() {
        statuses.put("CP-LATE", OrderStatus.EXPIRED);
        PaymentResult payment =
                new PaymentResult(GatewayCode.SEPAY, "CP-LATE", true, new BigDecimal("99000"), null, "CAPTURED", "{}");
        when(sepay.lookup("CP-LATE")).thenReturn(GatewayLookup.paid(payment));

        assertThat(service.reconcile("CP-LATE")).isEqualTo(OrderStatus.PAID);
    }

    @Test
    void BR53_aSettledOrder_isNotAskedAbout() {
        statuses.put("CP-PAID", OrderStatus.PAID);
        statuses.put("CP-FAILED", OrderStatus.FAILED);

        assertThat(service.reconcile("CP-PAID")).isEqualTo(OrderStatus.PAID);
        assertThat(service.reconcile("CP-FAILED")).isEqualTo(OrderStatus.FAILED);

        verify(sepay, never()).lookup(anyString());
    }

    @Test
    void UC48_anUnknownOrder_isNotFound_andAnUnreachableGateway_isUnavailable() {
        assertThatExceptionOfType(ResourceNotFoundException.class).isThrownBy(() -> service.reconcile("CP-NONE"));

        statuses.put("CP-DOWN", OrderStatus.PENDING);
        when(sepay.lookup("CP-DOWN")).thenThrow(new GatewayUnavailableException("timeout"));

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> service.reconcile("CP-DOWN"))
                .satisfies(e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.PAYMENT_GATEWAY_UNAVAILABLE));
        assertThat(statuses.get("CP-DOWN")).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void NSF13_aGatewayThisDeploymentCannotAsk_letsItsOrdersExpireWhenDue() {
        when(sepay.configured()).thenReturn(false);
        statuses.put("DUE-1", OrderStatus.PENDING);

        assertThat(service.reconcile("DUE-1")).isEqualTo(OrderStatus.EXPIRED);
        verify(sepay, never()).lookup(anyString());
    }

    @Test
    void NSF13_aRun_asksAboutTheDueOrders_countsTheChanged_andSurvivesFailures() {
        statuses.put("CP-PAID-NOW", OrderStatus.PENDING);
        statuses.put("DUE-EXPIRES", OrderStatus.PENDING);
        statuses.put("CP-STILL-OPEN", OrderStatus.PENDING);
        statuses.put("CP-DOWN", OrderStatus.PENDING);
        statuses.put("CP-BROKEN", OrderStatus.PENDING);
        statuses.put("CP-SETTLED-MEANWHILE", OrderStatus.PAID);
        when(orders.findCodesCreatedBefore(
                        eq(OrderStatus.PENDING), eq(NOW.minus(Duration.ofMinutes(5))), eq(PageRequest.of(0, 50))))
                .thenReturn(List.of(
                        "CP-PAID-NOW",
                        "DUE-EXPIRES",
                        "CP-STILL-OPEN",
                        "CP-DOWN",
                        "CP-BROKEN",
                        "CP-SETTLED-MEANWHILE",
                        "CP-GONE"));
        when(sepay.lookup("CP-PAID-NOW"))
                .thenReturn(GatewayLookup.paid(new PaymentResult(
                        GatewayCode.SEPAY, "CP-PAID-NOW", true, new BigDecimal("99000"), null, "CAPTURED", "{}")));
        when(sepay.lookup("DUE-EXPIRES")).thenReturn(GatewayLookup.of(GatewayLookup.State.AWAITING));
        when(sepay.lookup("CP-STILL-OPEN")).thenReturn(GatewayLookup.of(GatewayLookup.State.AWAITING));
        when(sepay.lookup("CP-DOWN")).thenThrow(new GatewayUnavailableException("timeout"));
        when(sepay.lookup("CP-BROKEN")).thenThrow(new IllegalStateException("bug"));

        assertThat(service.reconcileDue()).isEqualTo(2);

        assertThat(statuses)
                .containsEntry("CP-PAID-NOW", OrderStatus.PAID)
                .containsEntry("DUE-EXPIRES", OrderStatus.EXPIRED)
                .containsEntry("CP-STILL-OPEN", OrderStatus.PENDING)
                .containsEntry("CP-DOWN", OrderStatus.PENDING)
                .containsEntry("CP-BROKEN", OrderStatus.PENDING);
        verify(sepay, never()).lookup("CP-SETTLED-MEANWHILE");
    }

    @Test
    void D146_anUnpaidOrderPastItsWindow_isCancelledAtTheGatewayBeforeItExpires() {
        statuses.put("DUE-1", OrderStatus.PENDING);
        when(sepay.lookup("DUE-1")).thenReturn(GatewayLookup.of(GatewayLookup.State.AWAITING));

        assertThat(service.reconcile("DUE-1")).isEqualTo(OrderStatus.EXPIRED);

        verify(sepay).cancel("DUE-1");
    }

    @Test
    void D146_aRefusedCancellation_isLookedUpAgain_andAPaymentFoundThenIsSettled() {
        statuses.put("DUE-PAID", OrderStatus.PENDING);
        PaymentResult payment =
                new PaymentResult(GatewayCode.SEPAY, "DUE-PAID", true, new BigDecimal("99000"), null, "CAPTURED", "{}");
        when(sepay.lookup("DUE-PAID"))
                .thenReturn(GatewayLookup.of(GatewayLookup.State.AWAITING))
                .thenReturn(GatewayLookup.paid(payment));
        when(sepay.cancel("DUE-PAID")).thenReturn(false);

        assertThat(service.reconcile("DUE-PAID")).isEqualTo(OrderStatus.PAID);
        verify(settlement).settle(payment);
    }

    @Test
    void D146_anOrderTheGatewayWillNotCancel_staysPendingADay_thenExpires() {
        statuses.put("DUE-CARD", OrderStatus.PENDING);
        when(sepay.lookup("DUE-CARD")).thenReturn(GatewayLookup.of(GatewayLookup.State.AWAITING));
        when(sepay.cancel("DUE-CARD")).thenReturn(false);

        assertThat(service.reconcile("DUE-CARD")).isEqualTo(OrderStatus.PENDING);

        statuses.put("DUE-OLD", OrderStatus.PENDING);
        when(sepay.lookup("DUE-OLD")).thenReturn(GatewayLookup.of(GatewayLookup.State.AWAITING));
        when(sepay.cancel("DUE-OLD")).thenReturn(false);
        when(orders.findByOrderCode("DUE-OLD"))
                .thenAnswer(
                        call -> Optional.of(order("DUE-OLD", statuses.get("DUE-OLD"), NOW.minus(Duration.ofDays(2)))));

        assertThat(service.reconcile("DUE-OLD")).isEqualTo(OrderStatus.EXPIRED);
    }

    @Test
    void D146_releasingATradersPendingOrder_cancelsAndWithdrawsIt_beforeItsWindowCloses() {
        SubscriptionOrder open = order("CP-OPEN", OrderStatus.PENDING, NOW.plus(Duration.ofMinutes(10)));
        statuses.put("CP-OPEN", OrderStatus.PENDING);
        UUID trader = UUID.randomUUID();
        when(orders.findByUserIdAndOrderStatus(trader, OrderStatus.PENDING)).thenReturn(List.of(open));
        when(sepay.lookup("CP-OPEN")).thenReturn(GatewayLookup.of(GatewayLookup.State.AWAITING));

        service.releasePending(trader);

        verify(sepay).cancel("CP-OPEN");
        assertThat(statuses.get("CP-OPEN")).isEqualTo(OrderStatus.EXPIRED);
    }

    @Test
    void D146_aPendingOrderTheGatewayWillNotCancel_isNotReleased() {
        SubscriptionOrder open = order("CP-CARD", OrderStatus.PENDING, NOW.plus(Duration.ofMinutes(10)));
        statuses.put("CP-CARD", OrderStatus.PENDING);
        UUID trader = UUID.randomUUID();
        when(orders.findByUserIdAndOrderStatus(trader, OrderStatus.PENDING)).thenReturn(List.of(open));
        when(sepay.lookup("CP-CARD")).thenReturn(GatewayLookup.of(GatewayLookup.State.AWAITING));
        when(sepay.cancel("CP-CARD")).thenReturn(false);

        service.releasePending(trader);

        assertThat(statuses.get("CP-CARD")).isEqualTo(OrderStatus.PENDING);
        verify(settlement, never()).withdraw(anyString());
    }

    @Test
    void D146_releasingAnOrderOfAGatewayThisDeploymentCannotAsk_withdrawsIt() {
        when(sepay.configured()).thenReturn(false);
        SubscriptionOrder open = order("CP-LOCAL", OrderStatus.PENDING, NOW.plus(Duration.ofMinutes(10)));
        statuses.put("CP-LOCAL", OrderStatus.PENDING);
        UUID trader = UUID.randomUUID();
        when(orders.findByUserIdAndOrderStatus(trader, OrderStatus.PENDING)).thenReturn(List.of(open));

        service.releasePending(trader);

        assertThat(statuses.get("CP-LOCAL")).isEqualTo(OrderStatus.EXPIRED);
    }

    /** An order whose window closed a minute ago when its code starts with DUE, still open for 10 minutes otherwise. */
    private static SubscriptionOrder order(String code, OrderStatus status) {
        return order(
                code,
                status,
                code.startsWith("DUE") ? NOW.minus(Duration.ofMinutes(1)) : NOW.plus(Duration.ofMinutes(10)));
    }

    private static SubscriptionOrder order(String code, OrderStatus status, Instant expiresAt) {
        SubscriptionOrder order = mock(SubscriptionOrder.class);
        when(order.getOrderCode()).thenReturn(code);
        when(order.getOrderStatus()).thenReturn(status);
        when(order.getPaymentGateway()).thenReturn(GatewayCode.SEPAY);
        when(order.getExpiresAt()).thenReturn(expiresAt);
        return order;
    }
}
