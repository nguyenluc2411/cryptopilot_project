package com.cryptopilot.trading.exception;

import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.trading.model.EntryDeviation;
import com.cryptopilot.trading.model.PlanSnapshot;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A MARKET plan was not activated because its expected fill moved from the plan: MSG51 until the Trader confirms the
 * recalculated plan, or MSG52, which no confirmation lifts. The values travel as decimal texts, in the message
 * arguments in the order of the message's placeholders and as named members of the problem detail, so the client shows
 * the dialog without a second call.
 *
 * <p>Rule: BR-69; MSG51, MSG52; D-111 (M2).
 *
 * <p>Reference: Nottingham, M., Wilde, E. &amp; Dalal, S. (2023). RFC 9457, <i>Problem Details for HTTP APIs</i>,
 * section 3.2 (extension members).
 */
public class EntryDeviationException extends BusinessException {

    private static final long serialVersionUID = 1L;

    /** The deviation of the expected fill, in R; positive is a worse entry. */
    public static final String DEVIATION_R = "deviationR";

    /** The largest deviation the plan accepts, in R. */
    public static final String MAX_DEVIATION_R = "maxDeviationR";

    /** Whether the expected fill is at or past the take profit (MSG52 only). */
    public static final String TAKE_PROFIT_REACHED = "takeProfitReached";

    /** The expected fill the plan was recalculated at. */
    public static final String EXPECTED_FILL_PRICE = "expectedFillPrice";

    /** The recalculated quantity (MSG51 only). */
    public static final String POSITION_QUANTITY = "positionQuantity";

    /** The recalculated reward/risk ratio (MSG51 only). */
    public static final String RISK_REWARD_RATIO = "riskRewardRatio";

    /** The recalculated reward/risk ratio after fees and slippage (MSG51 only). */
    public static final String NET_RISK_REWARD = "netRiskReward";

    /** Text rather than numbers, so a value serializes as the decimal text every other API amount is. */
    private final LinkedHashMap<String, String> decimals;

    private final boolean takeProfitReached;

    private EntryDeviationException(
            ErrorCode errorCode,
            String detail,
            LinkedHashMap<String, String> decimals,
            boolean takeProfitReached,
            String... messageArgs) {
        super(errorCode, detail, (Object[]) messageArgs);
        this.decimals = decimals;
        this.takeProfitReached = takeProfitReached;
    }

    /**
     * MSG51: the deviation needs the Trader's confirmation. Its arguments are the deviation, the quantity and the
     * reward/risk ratio.
     *
     * @param deviation the deviation, with the verdict CONFIRM
     * @param expectedFill the expected fill the plan was recalculated at
     * @param recalculated the plan's values at that fill
     */
    public static EntryDeviationException confirmationRequired(
            EntryDeviation deviation, BigDecimal expectedFill, PlanSnapshot recalculated) {
        Objects.requireNonNull(recalculated, "recalculated");
        if (deviation.verdict() != EntryDeviation.Verdict.CONFIRM) {
            throw new IllegalArgumentException("MSG51 is for a deviation to confirm, not " + deviation.verdict());
        }
        LinkedHashMap<String, String> decimals = new LinkedHashMap<>();
        decimals.put(DEVIATION_R, text(deviation.deviationR()));
        decimals.put(MAX_DEVIATION_R, text(deviation.limitR()));
        decimals.put(EXPECTED_FILL_PRICE, text(expectedFill));
        decimals.put(POSITION_QUANTITY, text(recalculated.positionQuantity()));
        decimals.put(RISK_REWARD_RATIO, text(recalculated.riskRewardRatio()));
        decimals.put(NET_RISK_REWARD, text(recalculated.costs().netRiskReward()));
        return new EntryDeviationException(
                ErrorCode.ENTRY_DEVIATION_CONFIRMATION_REQUIRED,
                "the MARKET entry moved " + decimals.get(DEVIATION_R) + "R from the plan and needs confirmation",
                decimals,
                false,
                decimals.get(DEVIATION_R),
                decimals.get(POSITION_QUANTITY),
                decimals.get(RISK_REWARD_RATIO));
    }

    /**
     * MSG52: the deviation is above the limit, or the expected fill is at or past the take profit. Its arguments are
     * the deviation and the limit.
     *
     * @param deviation the deviation, with the verdict REFUSE
     * @param expectedFill the expected fill
     */
    public static EntryDeviationException refused(EntryDeviation deviation, BigDecimal expectedFill) {
        if (deviation.verdict() != EntryDeviation.Verdict.REFUSE) {
            throw new IllegalArgumentException("MSG52 is for a refused deviation, not " + deviation.verdict());
        }
        LinkedHashMap<String, String> decimals = new LinkedHashMap<>();
        decimals.put(DEVIATION_R, text(deviation.deviationR()));
        decimals.put(MAX_DEVIATION_R, text(deviation.limitR()));
        decimals.put(EXPECTED_FILL_PRICE, text(expectedFill));
        return new EntryDeviationException(
                ErrorCode.ENTRY_DEVIATION_REFUSED,
                "the MARKET entry moved " + decimals.get(DEVIATION_R) + "R from the plan, "
                        + (deviation.takeProfitReached()
                                ? "at or past the take profit"
                                : "beyond the limit of " + decimals.get(MAX_DEVIATION_R) + "R"),
                decimals,
                deviation.takeProfitReached(),
                decimals.get(DEVIATION_R),
                decimals.get(MAX_DEVIATION_R));
    }

    @Override
    public Map<String, Object> problemProperties() {
        Map<String, Object> properties = new LinkedHashMap<>(decimals);
        if (errorCode() == ErrorCode.ENTRY_DEVIATION_REFUSED) {
            properties.put(TAKE_PROFIT_REACHED, takeProfitReached);
        }
        return properties;
    }

    private static String text(BigDecimal value) {
        return Objects.requireNonNull(value, "a value of the recalculated plan").toPlainString();
    }
}
