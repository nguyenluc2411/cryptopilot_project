package com.cryptopilot.market;

import com.cryptopilot.market.model.SetupScoreTuning;
import java.math.BigDecimal;

/** The v2 values as {@code application.yml} declares them, for tests that build the calculators by hand. */
public final class SetupScoreTunings {

    public static final SetupScoreTuning DEFAULT = new SetupScoreTuning(
            new BigDecimal("0.0001"),
            new BigDecimal("0.001"),
            new BigDecimal("0.1"),
            new BigDecimal("2.0"),
            new BigDecimal("0.5"),
            new BigDecimal("0.5"),
            new BigDecimal("54"),
            new BigDecimal("10"),
            new BigDecimal("30"));

    private SetupScoreTunings() {}
}
