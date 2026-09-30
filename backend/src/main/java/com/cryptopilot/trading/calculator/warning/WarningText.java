package com.cryptopilot.trading.calculator.warning;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Numbers as a warning message shows them; the calculation keeps full precision (CR-03). */
final class WarningText {

    private static final int SHOWN_DECIMALS = 4;

    private WarningText() {}

    /** Truncated to four decimals for display; the rules compare at full precision. */
    static String show(BigDecimal value) {
        return value.setScale(SHOWN_DECIMALS, RoundingMode.DOWN)
                .stripTrailingZeros()
                .toPlainString();
    }
}
