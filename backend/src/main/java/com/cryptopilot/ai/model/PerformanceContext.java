package com.cryptopilot.ai.model;

import com.cryptopilot.journal.FillLearning;
import com.cryptopilot.journal.PerformanceDigest;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.model.enums.Direction;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;

/**
 * The Trader's performance summary in the context (PREMIUM, BR-51): the aggregates of the closed simulated trades the
 * SCR-23 dashboard shows, with the D-111 learning metrics, and the learning fields of the latest closed trades so the
 * model sees the spread the averages hide. A Trader without a closed trade gets no figures and a message, so the model
 * says there is nothing to review yet instead of reading zeros as a result.
 *
 * <p>Rule: BR-51, BR-62, BR-69, BR-70; D-111 (M4); T-074b.
 *
 * <p>Reference: Tharp, V. K. (2006). <i>Trade Your Way to Financial Freedom</i> (2nd ed.). McGraw-Hill (R-multiple);
 * Sweeney, J. (1996). <i>Maximum Adverse Excursion</i>. Wiley (MAE, MFE).
 *
 * @param available whether a summary is included
 * @param message what the model should tell the Trader when it is not, else {@code null}
 * @param summary the aggregates, or {@code null} when not available
 * @param recentTrades the latest closed simulated trades, the latest exit first; empty when not available
 */
public record PerformanceContext(
        boolean available, String message, PerformanceDigest summary, List<TradeLearning> recentTrades) {

    public static final PerformanceContext NO_CLOSED_TRADES = new PerformanceContext(
            false,
            "The Trader has no closed simulated trade yet, so there is no performance to review.",
            null,
            List.of());

    public PerformanceContext {
        recentTrades = recentTrades == null ? List.of() : List.copyOf(recentTrades);
    }

    /**
     * The digest and the recent trades as the context carries them: none when the digest counts no trade.
     *
     * <p>Rule: BR-51, BR-69, BR-70; D-111 (M4); T-074b.
     */
    public static PerformanceContext of(PerformanceDigest digest, List<TradeLearning> recentTrades) {
        Objects.requireNonNull(digest, "digest");
        return digest.trades() == 0 ? NO_CLOSED_TRADES : new PerformanceContext(true, null, digest, recentTrades);
    }

    /**
     * The learning fields of one closed simulated trade, and nothing else of it: no id, no price, no amount and no
     * time, so it can go with a question to the LLM Provider (BR-51). A field the journal did not know, e.g. on a trade
     * filled before the learning data was stored, is {@code null}.
     *
     * <p>Rule: BR-51, BR-69, BR-70; D-111 (M4); T-074b.
     *
     * <p>Reference: Tharp, V. K. (2006). <i>Trade Your Way to Financial Freedom</i> (2nd ed.). McGraw-Hill
     * (R-multiple); Sweeney, J. (1996). <i>Maximum Adverse Excursion</i>. Wiley (MAE, MFE).
     *
     * @param symbol the pair's exchange symbol, e.g. {@code BTCUSDT}; {@code null} when the pair is not known
     * @param market SPOT or FUTURES
     * @param direction LONG or SHORT
     * @param entryDeviationR the deviation of the fill from the plan in R, + a worse entry
     * @param setupScoreAtPlan the setup score at activation
     * @param setupScoreAtFill the setup score at the fill
     * @param rMultiple net P/L / planned risk
     * @param maeR the maximum adverse excursion in R
     * @param mfeR the maximum favourable excursion in R
     */
    public record TradeLearning(
            String symbol,
            MarketType market,
            Direction direction,
            BigDecimal entryDeviationR,
            BigDecimal setupScoreAtPlan,
            BigDecimal setupScoreAtFill,
            BigDecimal rMultiple,
            BigDecimal maeR,
            BigDecimal mfeR) {

        /** The places the values in R are sent with. */
        public static final int R_SCALE = 2;

        /**
         * The trade's learning fields, with the four values in R rounded half-up to {@link #R_SCALE} places for the
         * context only (the journal keeps its own scale); the setup scores as stored.
         *
         * <p>Rule: BR-51, BR-69, BR-70; D-111 (M4); T-074b.
         *
         * <p>Reference: Liu, N. F. et al. (2024). Lost in the Middle: How Language Models Use Long Contexts.
         * <i>Transactions of the Association for Computational Linguistics</i> 12, 157–173 (a bounded context).
         */
        public static TradeLearning of(FillLearning trade, String symbol) {
            Objects.requireNonNull(trade, "trade");
            return new TradeLearning(
                    symbol,
                    trade.market(),
                    trade.direction(),
                    inR(trade.entryDeviationR()),
                    trade.setupScoreAtPlan(),
                    trade.setupScoreAtFill(),
                    inR(trade.rMultiple()),
                    inR(trade.maeR()),
                    inR(trade.mfeR()));
        }

        private static BigDecimal inR(BigDecimal value) {
            return value == null ? null : value.setScale(R_SCALE, RoundingMode.HALF_UP);
        }
    }
}
