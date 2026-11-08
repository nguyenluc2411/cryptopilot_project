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

    private static SubscriptionOrder order(String code, OrderStatus status) {
        SubscriptionOrder order = mock(SubscriptionOrder.class);
        when(order.getOrderCode()).thenReturn(code);
        when(order.getOrderStatus()).thenReturn(status);
        when(order.getPaymentGateway()).thenReturn(GatewayCode.SEPAY);
        return order;
    }
}
