package com.cryptopilot.admin.model;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

/**
 * The range a setting's value must fall in when an administrator changes it (SRS 3.11.8: "the value is validated
 * against its type and range"). The bounds are inclusive; a decimal has at most {@code scale} fraction digits, the
 * scale its readers round with. A setting without a rule is checked against its type only.
 *
 * <p>The bounds are the widest values that still make sense, not policy: a fee above 10 % or an upload limit above
 * what the media storage accepts would break the feature rather than tune it.
 *
 * <p>A rule may name a partner it must not cross ({@link #atMost}): BR-69's two entry deviation bounds.
 *
 * <p>Rule: SRS 3.11.8; BR-29, BR-36, BR-44, BR-65, BR-69, BR-70; D-97.
 *
 * @param min the lowest value allowed
 * @param max the highest value allowed
 * @param scale the most fraction digits; 0 for an INT
 */
public record SettingRule(BigDecimal min, BigDecimal max, int scale) {

    private static final SettingRule FEE_RATE = decimal("0", "0.1", 8);

    private static final Map<String, SettingRule> RULES = Map.ofEntries(
            Map.entry("SPOT_MAKER_FEE", FEE_RATE),
            Map.entry("SPOT_TAKER_FEE", FEE_RATE),
            Map.entry("FUTURES_MAKER_FEE", FEE_RATE),
            Map.entry("FUTURES_TAKER_FEE", FEE_RATE),
            Map.entry("LIQUIDATION_CLEARANCE_FEE", FEE_RATE),
            Map.entry("WARN_LOW_RR_RATIO", decimal("0.00000001", "100", 8)),
            Map.entry("WARN_WIDE_STOP_LOSS_PERCENT", decimal("0.001", "100", 3)),
            Map.entry("WARN_HIGH_FUNDING_RATE", decimal("0.00000001", "1", 8)),
            Map.entry("MAX_IMAGES_PER_POST", integer(1, 10)),
            Map.entry("MAX_IMAGE_SIZE_MB", integer(1, 20)),
            // Cloudinary's free plan stores videos up to 100 MB.
            Map.entry("MAX_VIDEO_SIZE_MB", integer(1, 100)),
            Map.entry("MAX_VIDEO_DURATION_SECONDS", integer(1, 600)),
            Map.entry("ORDER_EXPIRY_MINUTES", integer(1, 1440)),
            // BR-36: 100 basis points is a 1 % move against the Trader, far beyond a liquid pair's spread.
            Map.entry("SLIPPAGE_BPS", integer(0, 100)),
            Map.entry("ENTRY_DEVIATION_CONFIRM_R", decimal("0", "10", 8)),
            Map.entry("ENTRY_DEVIATION_BLOCK_R", decimal("0", "10", 8)),
            Map.entry("SETUP_SCORE_DROP_WARN", integer(1, 100)),
            Map.entry("RESEND_MIN_INTERVAL_SECONDS", integer(1, 3600)),
            Map.entry("RESEND_MAX_PER_24_HOURS", integer(1, 100)));

    /** BR-69: the deviation that needs a confirmation starts at or below the one that is refused. */
    private static final Map<String, String> AT_MOST = Map.of("ENTRY_DEVIATION_CONFIRM_R", "ENTRY_DEVIATION_BLOCK_R");

    /** The rule of a setting, or empty when only its type is checked. */
    public static Optional<SettingRule> of(String key) {
        return Optional.ofNullable(RULES.get(key));
    }

    /** The setting whose value this one's may not exceed, or empty. */
    public static Optional<String> atMost(String key) {
        return Optional.ofNullable(AT_MOST.get(key));
    }

    /** The setting whose value this one's may not fall below, or empty. */
    public static Optional<String> atLeast(String key) {
        return AT_MOST.entrySet().stream()
                .filter(entry -> entry.getValue().equals(key))
                .map(Map.Entry::getKey)
                .findFirst();
    }

    private static SettingRule decimal(String min, String max, int scale) {
        return new SettingRule(new BigDecimal(min), new BigDecimal(max), scale);
    }

    private static SettingRule integer(int min, int max) {
        return new SettingRule(BigDecimal.valueOf(min), BigDecimal.valueOf(max), 0);
    }
}
