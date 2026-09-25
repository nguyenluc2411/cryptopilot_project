package com.cryptopilot.user.dto.request;

import com.cryptopilot.user.entity.TradingStyle;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * What the Profile tab of SCR-07 saves (SRS UC-06, section 3.2.5).
 *
 * <p>A replacement, not a patch: the tab shows every field and sends every field, so a {@code null}
 * default means "the profile expresses no preference" and clears the stored one. The display name is
 * the exception — it is shown on posts and comments, so it is required.
 *
 * <p>The ranges are the ones SRS 3.2.5 gives the tab, which are BR-30's plan inputs because these
 * figures pre-fill a plan: capital greater than zero, risk from 0.1 to 10 percent. A default outside
 * them would put a plan form in front of the trader that refuses its own starting values. They are
 * reported with MSG15, the range message BR-30's other inputs use; the display name follows
 * registration and reports MSG01 (SRS 3.2.1).
 *
 * <p>The digit limits are the columns' — {@code numeric(28,8)} and {@code numeric(6,3)} — and refuse
 * a value the database would otherwise round without telling anybody.
 *
 * <p>The avatar is deliberately absent. SRS 3.2.5 stores it in Media Storage, and accepting a URL
 * from the client before that seam exists would let a profile point at any address on the internet.
 *
 * <p>Rule: BR-30; SRS UC-06, section 3.2.5; messages MSG01, MSG15; TECHNICAL_DESIGN 5.4.
 *
 * @param displayName 2 to 50 characters
 * @param defaultCapital greater than zero, or {@code null}
 * @param defaultRiskPercent from 0.1 to 10, or {@code null}
 * @param tradingStyle SCALPING, DAY, SWING or POSITION, or {@code null}
 */
public record UpdateProfileRequest(
        @NotBlank(message = "MSG01") @Size(min = 2, max = 50, message = "MSG01")
        String displayName,

        @DecimalMin(value = "0", inclusive = false, message = "MSG15")
        @Digits(integer = 20, fraction = 8, message = "MSG15")
        BigDecimal defaultCapital,

        @DecimalMin(value = "0.1", message = "MSG15")
        @DecimalMax(value = "10", message = "MSG15")
        @Digits(integer = 2, fraction = 3, message = "MSG15")
        BigDecimal defaultRiskPercent,

        TradingStyle tradingStyle) {}
