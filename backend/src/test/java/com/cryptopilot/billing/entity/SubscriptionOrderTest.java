package com.cryptopilot.billing.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.cryptopilot.billing.PlanFixtures;
import com.cryptopilot.billing.model.enums.GatewayCode;
import com.cryptopilot.billing.model.enums.OrderStatus;
import com.cryptopilot.billing.model.enums.OrderType;
import com.cryptopilot.billing.model.enums.PlanTier;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** BR-53, BR-54, BR-56 and A-03 on the order itself. */
class SubscriptionOrderTest {

    private static final Instant NOW = Instant.parse("2026-11-08T03:00:00Z");
    private static final Duration EXPIRY = Duration.ofMinutes(15);
    private static final UUID TRADER = UUID.randomUUID();
    private static final UUID ADMIN = UUID.randomUUID();

    private static final SubscriptionPackage PRO_MONTHLY = SubscriptionPackage.paid(
            "PRO_MONTHLY", "Pro monthly", PlanTier.PRO, new BigDecimal("99000"), 30, PlanFixtures.PRO);

    @Test
    void BR56_aNewOrder_isPendingAndChargesThePackagePriceUntilItExpires() {
        SubscriptionOrder order = order();

        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.getUserId()).isEqualTo(TRADER);
        assertThat(order.getPackageId()).isEqualTo(PRO_MONTHLY.getId());
        assertThat(order.getOrderType()).isEqualTo(OrderType.NEW);
        assertThat(order.getPaymentGateway()).isEqualTo(GatewayCode.SEPAY);
        assertThat(order.getOrderCode()).isEqualTo("CP261108030000ABCD");
        assertThat(order.getAmount()).isEqualByComparingTo("99000");
        assertThat(order.getCurrency()).isEqualTo("VND");
        assertThat(order.getExpiresAt()).isEqualTo(NOW.plus(EXPIRY));
        assertThat(order.isManualHandling()).isFalse();
    }

    @Test
    void D146_aLegacyVnpayOrder_cannotBeCreated() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> SubscriptionOrder.create(
                        TRADER, PRO_MONTHLY, OrderType.NEW, GatewayCode.VNPAY, "CP1", NOW, EXPIRY))
                .withMessageContaining("VNPAY takes no new orders");
    }

    @Test
    void SRS3115_thePackageNotOnSale_cannotBeOrdered() {
        SubscriptionPackage free = SubscriptionPackage.free("FREE", "Free", PlanFixtures.FREE);

        assertThatIllegalArgumentException()
                .isThrownBy(() ->
                        SubscriptionOrder.create(TRADER, free, OrderType.NEW, GatewayCode.MOMO, "CP1", NOW, EXPIRY))
                .withMessageContaining("not on sale");
    }

    @ParameterizedTest(name = "expiry of {0} minutes is refused")
    @ValueSource(longs = {0, -1})
    void BR54_anOrder_staysPayableForSomeTime(long minutes) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> SubscriptionOrder.create(
                        TRADER, PRO_MONTHLY, OrderType.NEW, GatewayCode.MOMO, "CP1", NOW, Duration.ofMinutes(minutes)));
    }

    @ParameterizedTest(name = "order code \"{0}\" is refused")
    @ValueSource(strings = {"", " "})
    void TD711_anOrder_hasACode(String code) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> SubscriptionOrder.create(
                        TRADER, PRO_MONTHLY, OrderType.NEW, GatewayCode.MOMO, code, NOW, EXPIRY));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> SubscriptionOrder.create(
                        TRADER, PRO_MONTHLY, OrderType.NEW, GatewayCode.MOMO, "C".repeat(65), NOW, EXPIRY));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> SubscriptionOrder.create(
                        TRADER, PRO_MONTHLY, OrderType.NEW, GatewayCode.MOMO, null, NOW, EXPIRY));
    }

    @Test
    void BR53_aConfirmedPayment_makesTheOrderPaidAndKeepsTheGatewaysAnswer() {
        SubscriptionOrder order = order();
        Instant paidAt = NOW.plusSeconds(60);

        order.markPaid("TX-1", "00", "{\"resultCode\":0}", paidAt);

        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(order.getPaidAt()).isEqualTo(paidAt);
        assertThat(order.getIpnReceivedAt()).isEqualTo(paidAt);
        assertThat(order.getGatewayTransactionNo()).isEqualTo("TX-1");
        assertThat(order.getGatewayResponseCode()).isEqualTo("00");
        assertThat(order.getGatewayPayload()).isEqualTo("{\"resultCode\":0}");
    }

    /** A-03: the Trader was charged, so a success after expiry is honoured. */
    @Test
    void A03_aSuccessAfterTheOrderExpired_isStillHonoured() {
        SubscriptionOrder order = order();
        order.expire(NOW.plus(EXPIRY));

        order.markPaid("TX-1", "00", null, NOW.plus(Duration.ofMinutes(20)));

        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PAID);
    }

    /** BR-53: an order is settled once, so a repeated IPN cannot pay it again or fail it. */
    @Test
    void BR53_aSettledOrder_cannotBeSettledAgain() {
        SubscriptionOrder paid = order();
        paid.markPaid("TX-1", "00", null, NOW);
        SubscriptionOrder failed = order();
        failed.markFailed("49", null, NOW);

        assertThatIllegalStateException().isThrownBy(() -> paid.markPaid("TX-2", "00", null, NOW));
        assertThatIllegalStateException().isThrownBy(() -> paid.markFailed("49", null, NOW));
        assertThatIllegalStateException().isThrownBy(() -> failed.markPaid("TX-1", "00", null, NOW));
        assertThatIllegalStateException().isThrownBy(() -> paid.expire(NOW.plus(EXPIRY)));
    }

    @Test
    void BR53_aReportedFailure_makesTheOrderFailed() {
        SubscriptionOrder order = order();

        order.markFailed("1006", "{}", NOW);

        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.FAILED);
        assertThat(order.getGatewayResponseCode()).isEqualTo("1006");
        assertThat(order.getPaidAt()).isNull();
    }

    @Test
    void BR54_anOrderStillPayable_doesNotExpire() {
        SubscriptionOrder order = order();

        assertThatIllegalStateException()
                .isThrownBy(() -> order.expire(NOW.plus(EXPIRY).minusSeconds(1)))
                .withMessageContaining("payable until");
        order.expire(NOW.plus(EXPIRY));
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.EXPIRED);
    }

    @Test
    void UC48_aFlaggedOrder_isHandledOnceByAnAdministrator() {
        SubscriptionOrder order = order();
        order.markPaid("TX-1", "00", null, NOW);

        order.flagForManualHandling();
        order.markHandled(ADMIN, "Refunded by bank transfer", NOW.plusSeconds(3600));

        assertThat(order.isManualHandling()).isTrue();
        assertThat(order.getHandledBy()).isEqualTo(ADMIN);
        assertThat(order.getHandledAt()).isEqualTo(NOW.plusSeconds(3600));
        assertThat(order.getHandlingNote()).isEqualTo("Refunded by bank transfer");
        assertThatIllegalStateException().isThrownBy(() -> order.markHandled(ADMIN, "again", NOW));
    }

    @Test
    void UC48_onlyAPaidOrder_isFlagged_andOnlyAFlaggedOneIsHandled() {
        SubscriptionOrder pending = order();
        SubscriptionOrder paid = order();
        paid.markPaid("TX-1", "00", null, NOW);

        assertThatIllegalStateException().isThrownBy(pending::flagForManualHandling);
        assertThatIllegalStateException().isThrownBy(() -> paid.markHandled(ADMIN, null, NOW));
    }

    @Test
    void UC48_aHandlingNote_fitsItsColumn() {
        SubscriptionOrder order = order();
        order.markPaid("TX-1", "00", null, NOW);
        order.flagForManualHandling();

        assertThatIllegalArgumentException().isThrownBy(() -> order.markHandled(ADMIN, "x".repeat(1001), NOW));
        order.markHandled(ADMIN, null, NOW);
        assertThat(order.getHandlingNote()).isNull();
    }

    private static SubscriptionOrder order() {
        return SubscriptionOrder.create(
                TRADER, PRO_MONTHLY, OrderType.NEW, GatewayCode.SEPAY, "CP261108030000ABCD", NOW, EXPIRY);
    }
}
