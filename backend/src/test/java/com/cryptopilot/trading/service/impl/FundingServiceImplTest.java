package com.cryptopilot.trading.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cryptopilot.market.FundingHistory;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.SettledFunding;
import com.cryptopilot.market.SnapshotGap;
import com.cryptopilot.trading.model.FundingFee;
import com.cryptopilot.trading.model.enums.Direction;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * The funding of a position read on close and on read: computed from the market's history every time, a settlement
 * not stored yet logged and its known sum kept as provisional, and the full figure as soon as the late row is stored.
 *
 * <p>Rule: BR-37; A-05; TECHNICAL_DESIGN 7.8.
 */
class FundingServiceImplTest {

    private static final UUID PAIR = UUID.randomUUID();
    private static final Instant ENTRY = Instant.parse("2026-10-20T01:00:00Z");
    private static final Instant T8 = Instant.parse("2026-10-20T08:00:00Z");
    private static final Instant T16 = Instant.parse("2026-10-20T16:00:00Z");
    private static final Instant EXIT = Instant.parse("2026-10-20T17:00:00Z");
    private static final BigDecimal QTY = new BigDecimal("2");
    private static final SettledFunding AT_8 = new SettledFunding(T8, new BigDecimal("0.0001"), new BigDecimal("101"));
    private static final SettledFunding AT_16 =
            new SettledFunding(T16, new BigDecimal("-0.0002"), new BigDecimal("99"));

    private final MarketApi market = mock(MarketApi.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(FundingServiceImpl.class);

    @BeforeEach
    void captureLogs() {
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(logs);
    }

    @Test
    void BR37_onClose_theFundingIsSummedFromTheStoredSettlements() {
        when(market.fundingHistory(PAIR, ENTRY, EXIT)).thenReturn(history(List.of(AT_8, AT_16), List.of(T8, T16)));

        FundingFee fee = service(EXIT).fundingFee(PAIR, Direction.LONG, QTY, ENTRY, EXIT);

        // LONG: −0.0202 + 0.0396
        assertThat(fee.amount()).isEqualByComparingTo("0.0194");
        assertThat(logs.list).isEmpty();
    }

    /** A settlement announced but not stored: the known sum, provisional, and a WARN naming the pair and the instant. */
    @Test
    void A05_aMissingSettlement_isLoggedWithThePairAndItsTime_andKeepsTheKnownSum() {
        when(market.fundingHistory(PAIR, ENTRY, EXIT)).thenReturn(history(List.of(AT_8), List.of(T8, T16)));

        FundingFee fee = service(EXIT).fundingFee(PAIR, Direction.LONG, QTY, ENTRY, EXIT);

        assertThat(fee.isComplete()).isFalse();
        assertThat(fee.amount()).isEqualByComparingTo("-0.0202");
        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains(PAIR.toString()).contains(T16.toString());
        });
    }

    /** The next read after the late row arrives recomputes from the history and is complete. */
    @Test
    void A05_aLateSettlementRow_isPickedUpByTheNextRead() {
        when(market.fundingHistory(PAIR, ENTRY, EXIT))
                .thenReturn(history(List.of(AT_8), List.of(T8, T16)))
                .thenReturn(history(List.of(AT_8, AT_16), List.of(T8, T16)));
        FundingServiceImpl service = service(EXIT);

        FundingFee first = service.fundingFee(PAIR, Direction.SHORT, QTY, ENTRY, EXIT);
        FundingFee second = service.fundingFee(PAIR, Direction.SHORT, QTY, ENTRY, EXIT);

        assertThat(first.isComplete()).isFalse();
        assertThat(second.isComplete()).isTrue();
        assertThat(second.amount()).isEqualByComparingTo("-0.0194");
        verify(market, times(2)).fundingHistory(PAIR, ENTRY, EXIT);
    }

    @Test
    void BR37_anOpenPosition_isReadUpToThePresent() {
        Instant now = T8.plusSeconds(300);
        when(market.fundingHistory(PAIR, ENTRY, now)).thenReturn(history(List.of(AT_8), List.of(T8)));

        FundingFee fee = service(now).fundingSoFar(PAIR, Direction.LONG, QTY, ENTRY);

        assertThat(fee.amount()).isEqualByComparingTo("-0.0202");
    }

    /** A clock a moment behind the fill time reads an empty period, not one that ends before it starts. */
    @Test
    void BR37_anOpenPositionReadBeforeItsEntryTime_hasNoFundingYet() {
        when(market.fundingHistory(PAIR, ENTRY, ENTRY)).thenReturn(history(List.of(), List.of()));

        FundingFee fee = service(ENTRY.minusSeconds(1)).fundingSoFar(PAIR, Direction.LONG, QTY, ENTRY);

        assertThat(fee.amount()).isEqualByComparingTo("0");
    }

    /** A gap in the snapshots with no interval to check it against: provisional, and one WARN naming the pair. */
    @Test
    void A05_aGapThatCannotBeChecked_isLoggedOnce() {
        when(market.fundingHistory(PAIR, ENTRY, EXIT))
                .thenReturn(new FundingHistory(
                        List.of(AT_8), List.of(T8), List.of(), List.of(new SnapshotGap(T8, T16)), List.of()));

        FundingFee fee = service(EXIT).fundingFee(PAIR, Direction.LONG, QTY, ENTRY, EXIT);

        assertThat(fee.isComplete()).isFalse();
        assertThat(fee.missingFundingTimes()).isEmpty();
        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains(PAIR.toString()).contains("no known funding interval");
        });
    }

    /** Stored settlements and announced instants with snapshots that have no gap. */
    private static FundingHistory history(List<SettledFunding> settlements, List<Instant> scheduled) {
        return new FundingHistory(settlements, scheduled, List.of(), List.of(), List.of());
    }

    private FundingServiceImpl service(Instant now) {
        return new FundingServiceImpl(market, Clock.fixed(now, ZoneOffset.UTC));
    }
}
