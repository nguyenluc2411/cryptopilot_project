package com.cryptopilot.notification.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.billing.event.SubscriptionEndingSoon;
import com.cryptopilot.billing.event.SubscriptionOrderPaid;
import com.cryptopilot.notification.client.EmailMessage;
import com.cryptopilot.notification.config.MailDeliveryProperties;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The receipt of a paid order (T-069): its address, subject and rows in Vietnam time and grouped dong, the link to
 * the payment history, the carried value and bonus days of an upgrade only, and an HTML body that escapes what it
 * shows.
 *
 * <p>Rule: BR-53, BR-63 as amended by D-146.
 */
class PaymentReceiptMailsTest {

    private final PaymentReceiptMails mails =
            new PaymentReceiptMails(new MailDeliveryProperties("no-reply@example.com", URI.create("https://app.test")));

    @Test
    void NSF14_theReminder_givesTheEndInVietnamTime_andLinksToThePlans() {
        EmailMessage mail = mails.endingSoon(
                new SubscriptionEndingSoon(
                        UUID.randomUUID(), UUID.randomUUID(), "Pro Monthly", Instant.parse("2026-12-08T03:00:00Z")),
                "jane@example.com");

        assertThat(mail.to()).isEqualTo("jane@example.com");
        assertThat(mail.subject()).isEqualTo("Gói Pro Monthly của bạn sắp hết hạn");
        assertThat(mail.text()).contains("08/12/2026 10:00").contains("https://app.test/subscription");
        assertThat(mail.html()).contains("href=\"https://app.test/subscription\"");
    }

    @Test
    void BR53_aNewOrder_isReceiptedWithItsAmountGatewayTransactionAndPeriod() {
        EmailMessage mail = mails.receipt(paid("NEW", "TX-1", "0", 0), "jane@example.com");

        assertThat(mail.to()).isEqualTo("jane@example.com");
        assertThat(mail.subject()).isEqualTo("Hoá đơn thanh toán CryptoPilot - đơn CP1");
        assertThat(mail.actionLink()).isEqualTo("https://app.test/subscription/payments");
        assertThat(mail.text())
                .contains("Mã đơn: CP1")
                .contains("Gói: Pro Monthly")
                .contains("Loại đơn: Đăng ký mới")
                .contains("Số tiền: 99.000 ₫")
                .contains("Cổng thanh toán: SePay")
                .contains("Mã giao dịch: TX-1")
                .contains("Thời gian thanh toán: 08/11/2026 14:59")
                .contains("Hiệu lực: 08/11/2026 14:59 - 08/12/2026 14:59")
                .doesNotContain("Giá trị chuyển")
                .doesNotContain("Ngày tặng");
        assertThat(mail.html()).contains("99.000 ₫").contains("href=\"https://app.test/subscription/payments\"");
    }

    @Test
    void D146_anUpgrade_showsTheCarriedValueAndTheBonusDays() {
        EmailMessage mail = mails.receipt(paid("UPGRADE", null, "98929", 15), "jane@example.com");

        assertThat(mail.text())
                .contains("Loại đơn: Nâng cấp")
                .contains("Giá trị chuyển từ gói cũ: 98.929 ₫")
                .contains("Ngày tặng thêm: +15 ngày")
                .doesNotContain("Mã giao dịch");
    }

    @Test
    void D146_aRenewal_doesNotShowTheValueItKeepsAsDays() {
        assertThat(mails.receipt(paid("RENEW", "TX-2", "50000", 0), "jane@example.com")
                        .text())
                .contains("Loại đơn: Gia hạn")
                .doesNotContain("Giá trị chuyển");
    }

    @Test
    void theHtmlBody_escapesThePackageName() {
        SubscriptionOrderPaid paid = paid("NEW", "TX-1", "0", 0);
        SubscriptionOrderPaid odd = new SubscriptionOrderPaid(
                paid.orderId(),
                paid.userId(),
                paid.orderCode(),
                "Pro <b>",
                paid.orderType(),
                paid.amount(),
                paid.currency(),
                paid.gateway(),
                paid.transactionNo(),
                paid.paidAt(),
                paid.startAt(),
                paid.endAt(),
                paid.carriedValueVnd(),
                paid.bonusDays());

        assertThat(mails.receipt(odd, "jane@example.com").html())
                .contains("Pro &lt;b&gt;")
                .doesNotContain("Pro <b>");
    }

    static SubscriptionOrderPaid paid(String type, String transactionNo, String carried, int bonusDays) {
        Instant paidAt = Instant.parse("2026-11-08T07:59:19Z");
        return new SubscriptionOrderPaid(
                UUID.fromString("01a1ba28-fec9-75eb-b974-0ad20d535c26"),
                UUID.fromString("01a14a40-703a-74e7-bb70-8c0b6189358f"),
                "CP1",
                "Pro Monthly",
                type,
                new BigDecimal("99000"),
                "VND",
                "SEPAY",
                transactionNo,
                paidAt,
                paidAt,
                Instant.parse("2026-12-08T07:59:19Z"),
                new BigDecimal(carried),
                bonusDays);
    }
}
