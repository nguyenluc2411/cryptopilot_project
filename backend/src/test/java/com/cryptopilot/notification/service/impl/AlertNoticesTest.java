package com.cryptopilot.notification.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.watchlist.event.AlertTriggered;
import com.cryptopilot.watchlist.model.enums.AlertIndicator;
import com.cryptopilot.watchlist.model.enums.AlertType;
import com.cryptopilot.watchlist.model.enums.ConditionOperator;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The words of MSG28, "{pair}: {condition} ({value}).", for each alert type: the price and the RSI with the value that
 * met the condition, the funding rate and the open interest change in percent, the MACD and EMA crosses without one.
 *
 * <p>Rule: NSF-06, BR-19, BR-20; SRS 3.4.4; Q-30, Q-45.
 */
class AlertNoticesTest {

    @Test
    void MSG28_aPriceCrossAbove_namesThePairTheMarketTheLevelAndTheLastPrice() {
        AlertTriggered event = price("BTCUSDT", MarketType.SPOT, ConditionOperator.CROSS_ABOVE, "65000.00", "65012.50");

        assertThat(AlertNotices.content(event)).isEqualTo("BTCUSDT Spot: price crossed above 65000 (65012.5).");
        assertThat(AlertNotices.title(event)).isEqualTo("Price alert");
    }

    @Test
    void MSG28_aPriceBelowALevelOnFutures_isNamedAsSuch() {
        AlertTriggered event = price("ETHUSDT", MarketType.FUTURES, ConditionOperator.LESS_THAN, "3000", "2999.90");

        assertThat(AlertNotices.content(event)).isEqualTo("ETHUSDT Futures: price is below 3000 (2999.9).");
    }

    @Test
    void MSG28_anRsiAlert_showsTheRsiToTwoPlaces() {
        AlertTriggered event =
                indicator(AlertIndicator.RSI_14, MarketType.SPOT, ConditionOperator.GREATER_THAN, "70", "71.23456");

        assertThat(AlertNotices.content(event)).isEqualTo("BTCUSDT Spot: RSI (14) is above 70 (71.23).");
        assertThat(AlertNotices.title(event)).isEqualTo("Indicator alert");
    }

    @Test
    void MSG28_aMacdCross_hasNoValue_becauseWhatCrossedIsTheGapBetweenTwoLines() {
        AlertTriggered event =
                indicator(AlertIndicator.MACD_CROSS, MarketType.SPOT, ConditionOperator.CROSS_BELOW, null, "-0.42");

        assertThat(AlertNotices.content(event)).isEqualTo("BTCUSDT Spot: MACD crossed below its signal line.");
    }

    @Test
    void MSG28_anEmaCross_namesBothAverages() {
        AlertTriggered event =
                indicator(AlertIndicator.EMA_CROSS, MarketType.SPOT, ConditionOperator.CROSS_ABOVE, null, "12.5");

        assertThat(AlertNotices.content(event)).isEqualTo("BTCUSDT Spot: EMA 20 crossed above EMA 50.");
    }

    @Test
    void MSG28_aFundingRate_isShownInPercent_asTheTraderEnteredIt() {
        AlertTriggered event = indicator(
                AlertIndicator.FUNDING_RATE, MarketType.FUTURES, ConditionOperator.CROSS_ABOVE, "0.01", "0.012300");

        assertThat(AlertNotices.content(event))
                .isEqualTo("BTCUSDT Futures: funding rate crossed above 0.01% (0.0123%).");
    }

    @Test
    void MSG28_anOpenInterestChange_isShownInPercentOverTheHour() {
        AlertTriggered event = indicator(
                AlertIndicator.OPEN_INTEREST_CHANGE,
                MarketType.FUTURES,
                ConditionOperator.GREATER_THAN,
                "5.00",
                "6.20");

        assertThat(AlertNotices.content(event))
                .isEqualTo("BTCUSDT Futures: open interest change (1h) is above 5% (6.2%).");
    }

    @Test
    void MSG28_aPairWhoseSymbolIsUnknown_isNamedByItsMarket() {
        AlertTriggered event = price(null, MarketType.SPOT, ConditionOperator.CROSS_ABOVE, "100", "101");

        assertThat(AlertNotices.content(event)).isEqualTo("Spot pair: price crossed above 100 (101).");
    }

    @Test
    void MSG28_withoutAnObservedValue_leavesTheBracketsOut() {
        AlertTriggered event = price("BTCUSDT", MarketType.SPOT, ConditionOperator.CROSS_BELOW, "60000", null);

        assertThat(AlertNotices.content(event)).isEqualTo("BTCUSDT Spot: price crossed below 60000.");
    }

    static AlertTriggered price(
            String symbol, MarketType market, ConditionOperator condition, String threshold, String observed) {
        return event(symbol, market, AlertType.PRICE, null, condition, threshold, observed);
    }

    static AlertTriggered indicator(
            AlertIndicator indicator,
            MarketType market,
            ConditionOperator condition,
            String threshold,
            String observed) {
        return event("BTCUSDT", market, AlertType.INDICATOR, indicator, condition, threshold, observed);
    }

    private static AlertTriggered event(
            String symbol,
            MarketType market,
            AlertType type,
            AlertIndicator indicator,
            ConditionOperator condition,
            String threshold,
            String observed) {
        return new AlertTriggered(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                symbol,
                market,
                type,
                indicator,
                condition,
                threshold == null ? null : new BigDecimal(threshold),
                observed == null ? null : new BigDecimal(observed),
                Instant.parse("2026-10-29T03:15:00Z"),
                1,
                true,
                false,
                false);
    }
}
