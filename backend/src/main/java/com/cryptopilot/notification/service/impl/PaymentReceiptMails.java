package com.cryptopilot.notification.service.impl;

import com.cryptopilot.billing.event.SubscriptionEndingSoon;
import com.cryptopilot.billing.event.SubscriptionOrderPaid;
import com.cryptopilot.notification.client.EmailMessage;
import com.cryptopilot.notification.config.MailDeliveryProperties;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Writes the receipt of a paid order (T-069), in Vietnamese since the Traders pay in VND through Vietnamese gateways:
 * the order code, the package and order type, the amount, the gateway and its transaction number, when it was paid
 * and the period of the subscription it bought, with the value carried over and the bonus days of an upgrade. The one
 * link opens the payment history (SCR-32). Times are shown in Vietnam time, amounts as whole dong grouped by dots.
 *
 * <p>Rule: BR-53, BR-55, BR-63 as amended by D-146; NSF-15.
 */
@Component
public class PaymentReceiptMails {

    static final String HISTORY_ROUTE = "/subscription/payments";

    /** The plans page, where a plan is renewed (SCR-30). */
    static final String PLANS_ROUTE = "/subscription";

    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(VIETNAM);
    private static final Map<String, String> TYPES =
            Map.of("NEW", "Đăng ký mới", "RENEW", "Gia hạn", "UPGRADE", "Nâng cấp");
    private static final Map<String, String> GATEWAYS = Map.of("SEPAY", "SePay", "MOMO", "MoMo", "VNPAY", "VNPAY");

    private final MailDeliveryProperties properties;

    public PaymentReceiptMails(MailDeliveryProperties properties) {
        this.properties = properties;
    }

    /** The receipt of {@code paid}, to {@code email}. */
    public EmailMessage receipt(SubscriptionOrderPaid paid, String email) {
        String link = UriComponentsBuilder.fromUri(properties.publicBaseUrl())
                .path(HISTORY_ROUTE)
                .build()
                .toUriString();
        List<String[]> rows = rows(paid);
        String intro = "Cảm ơn bạn đã thanh toán. CryptoPilot đã nhận được tiền và gói " + paid.packageName()
                + " của bạn đã được kích hoạt.";
        String outro = "Đây là email tự động, vui lòng không trả lời. Nếu có sai sót, hãy liên hệ bộ phận hỗ trợ và"
                + " gửi kèm mã đơn " + paid.orderCode() + ".";

        StringBuilder text = new StringBuilder(intro).append("\n\nHOÁ ĐƠN THANH TOÁN\n");
        for (String[] row : rows) {
            text.append(row[0]).append(": ").append(row[1]).append('\n');
        }
        text.append("\nXem lịch sử thanh toán: ")
                .append(link)
                .append("\n\n")
                .append(outro)
                .append('\n');

        StringBuilder table = new StringBuilder();
        for (String[] row : rows) {
            table.append("<tr><td style=\"padding:6px 12px 6px 0;color:#64748b\">")
                    .append(HtmlUtils.htmlEscape(row[0]))
                    .append("</td><td style=\"padding:6px 0;text-align:right;font-weight:600\">")
                    .append(HtmlUtils.htmlEscape(row[1]))
                    .append("</td></tr>");
        }
        String html = """
                <!DOCTYPE html>
                <html><body style="font-family: Arial, sans-serif; line-height: 1.5; color: #0f172a">
                <p>%s</p>
                <h2 style="font-size: 16px; margin: 20px 0 8px">Hoá đơn thanh toán</h2>
                <table style="border-collapse: collapse; min-width: 320px">%s</table>
                <p style="margin-top: 20px"><a href="%s">Xem lịch sử thanh toán</a></p>
                <p style="color: #64748b; font-size: 12px">%s</p>
                </body></html>
                """.formatted(
                        HtmlUtils.htmlEscape(intro), table, HtmlUtils.htmlEscape(link), HtmlUtils.htmlEscape(outro));

        return new EmailMessage(
                email, "Hoá đơn thanh toán CryptoPilot - đơn " + paid.orderCode(), text.toString(), html, link);
    }

    /** The reminder that {@code soon} ends within 3 days (NSF-14), to {@code email}, with a link to renew. */
    public EmailMessage endingSoon(SubscriptionEndingSoon soon, String email) {
        String link = UriComponentsBuilder.fromUri(properties.publicBaseUrl())
                .path(PLANS_ROUTE)
                .build()
                .toUriString();
        String body = "Gói " + soon.packageName() + " của bạn sẽ hết hạn vào " + TIME.format(soon.endAt())
                + ". Gia hạn trước thời điểm này để tiếp tục dùng các tính năng của gói; thời gian còn lại được cộng"
                + " dồn vào gói mới.";
        String after = "Nếu không gia hạn, tài khoản sẽ về gói Free. Dữ liệu của bạn được giữ nguyên, các cảnh báo"
                + " vượt giới hạn của gói Free sẽ được tạm dừng.";
        String outro = "Đây là email tự động, vui lòng không trả lời.";
        String text = body + "\n\n" + after + "\n\nGia hạn: " + link + "\n\n" + outro + "\n";
        String html = """
                <!DOCTYPE html>
                <html><body style="font-family: Arial, sans-serif; line-height: 1.5; color: #0f172a">
                <p>%s</p>
                <p>%s</p>
                <p style="margin-top: 20px"><a href="%s">Gia hạn gói</a></p>
                <p style="color: #64748b; font-size: 12px">%s</p>
                </body></html>
                """.formatted(
                        HtmlUtils.htmlEscape(body),
                        HtmlUtils.htmlEscape(after),
                        HtmlUtils.htmlEscape(link),
                        HtmlUtils.htmlEscape(outro));
        return new EmailMessage(email, "Gói " + soon.packageName() + " của bạn sắp hết hạn", text, html, link);
    }

    private static List<String[]> rows(SubscriptionOrderPaid paid) {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] {"Mã đơn", paid.orderCode()});
        rows.add(new String[] {"Gói", paid.packageName()});
        rows.add(new String[] {"Loại đơn", TYPES.getOrDefault(paid.orderType(), paid.orderType())});
        rows.add(new String[] {"Số tiền", money(paid.amount(), paid.currency())});
        rows.add(new String[] {"Cổng thanh toán", GATEWAYS.getOrDefault(paid.gateway(), paid.gateway())});
        if (paid.transactionNo() != null && !paid.transactionNo().isBlank()) {
            rows.add(new String[] {"Mã giao dịch", paid.transactionNo()});
        }
        rows.add(new String[] {"Thời gian thanh toán", TIME.format(paid.paidAt())});
        rows.add(new String[] {"Hiệu lực", TIME.format(paid.startAt()) + " - " + TIME.format(paid.endAt())});
        if ("UPGRADE".equals(paid.orderType()) && paid.carriedValueVnd().signum() > 0) {
            rows.add(new String[] {"Giá trị chuyển từ gói cũ", money(paid.carriedValueVnd(), "VND")});
        }
        if (paid.bonusDays() > 0) {
            rows.add(new String[] {"Ngày tặng thêm", "+" + paid.bonusDays() + " ngày"});
        }
        return rows;
    }

    /** Whole units grouped by dots, "99.000 ₫" for VND. */
    static String money(BigDecimal amount, String currency) {
        DecimalFormat format = new DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(Locale.of("vi", "VN")));
        String grouped = format.format(amount);
        return "VND".equals(currency) ? grouped + " ₫" : grouped + " " + currency;
    }
}
