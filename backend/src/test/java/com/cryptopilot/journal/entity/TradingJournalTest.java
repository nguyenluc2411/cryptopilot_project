package com.cryptopilot.journal.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.journal.model.enums.TradeSource;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.RecordedPosition;
import com.cryptopilot.trading.SimulatedTradeOpening;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.model.enums.LiquidityRole;
import com.cryptopilot.trading.model.enums.PositionStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The OPEN record a fill writes, and the position it answers. */
class TradingJournalTest {

    private static final UUID PLAN = UUID.fromString("019b76da-a800-7000-8000-0000000000d1");
    private static final UUID USER = UUID.fromString("019b76da-a800-7000-8000-0000000000d2");
    private static final UUID PAIR = UUID.fromString("019b76da-a800-7000-8000-0000000000d3");

    /** BR-34: every value of the opening is kept, the record is SIMULATED and OPEN, and the time is cut to microseconds. */
    @Test
    void BR34_theOpenRecord_keepsTheFillAndThePlanValues() {
        TradingJournal record = TradingJournal.openSimulated(new SimulatedTradeOpening(
                PLAN,
                USER,
                PAIR,
                MarketType.FUTURES,
                Direction.SHORT,
                10,
                new BigDecimal("100.5"),
                Instant.parse("2026-10-25T08:01:00.123456789Z"),
                new BigDecimal("2"),
                LiquidityRole.TAKER,
                new BigDecimal("0.1005"),
                new BigDecimal("105"),
                new BigDecimal("90")));

        assertThat(record.getSource()).isEqualTo(TradeSource.SIMULATED);
        assertThat(record.getStatus()).isEqualTo(PositionStatus.OPEN);
        assertThat(record.getPlanId()).isEqualTo(PLAN);
        assertThat(record.getUserId()).isEqualTo(USER);
        assertThat(record.getPairId()).isEqualTo(PAIR);
        assertThat(record.getMarket()).isEqualTo(MarketType.FUTURES);
        assertThat(record.getDirection()).isEqualTo(Direction.SHORT);
        assertThat(record.getLeverage()).isEqualTo(10);
        assertThat(record.getEntryPrice()).isEqualByComparingTo("100.5");
        assertThat(record.getEntryTime()).isEqualTo(Instant.parse("2026-10-25T08:01:00.123456Z"));
        assertThat(record.getQuantity()).isEqualByComparingTo("2");
        assertThat(record.getEntryLiquidity()).isEqualTo(LiquidityRole.TAKER);
        assertThat(record.getEntryFee()).isEqualByComparingTo("0.1005");
        assertThat(record.getStopLoss()).isEqualByComparingTo("105");
        assertThat(record.getTakeProfit()).isEqualByComparingTo("90");
        assertThat(record.isNew()).isTrue();

        assertThat(record.toPosition())
                .isEqualTo(new RecordedPosition(
                        PLAN,
                        PositionStatus.OPEN,
                        new BigDecimal("100.5"),
                        new BigDecimal("2"),
                        Instant.parse("2026-10-25T08:01:00.123456Z"),
                        new BigDecimal("0.1005")));
    }
}
