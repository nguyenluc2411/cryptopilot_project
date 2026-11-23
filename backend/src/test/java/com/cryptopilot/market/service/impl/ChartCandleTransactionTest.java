package com.cryptopilot.market.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.cryptopilot.market.MarketTestData;
import com.cryptopilot.market.client.BinanceRestClient;
import com.cryptopilot.market.client.Kline;
import com.cryptopilot.market.job.CandleBackfillJob;
import com.cryptopilot.market.job.MarketStreamSupervisor;
import com.cryptopilot.market.model.enums.BinanceVenue;
import com.cryptopilot.market.service.ChartCandleService;
import com.cryptopilot.support.TestcontainersConfig;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * TR-35 through the application context: no transaction, and so no pooled database connection, is held while the
 * exchange answers a chart page. The context is the one of {@code PairAdminApiTest} (same overrides), so it is reused.
 *
 * <p>Rule: TR-35.
 *
 * <p>Reference: Nygard, M. T. (2018). <i>Release It!</i> (2nd ed.). Pragmatic Bookshelf, ch. 5 "Timeouts".
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@RecordApplicationEvents
class ChartCandleTransactionTest {

    @Autowired
    private ChartCandleService charts;

    @Autowired
    private JdbcClient sql;

    @MockitoBean
    private BinanceRestClient exchange;

    @MockitoSpyBean
    private MarketStreamSupervisor streams;

    @MockitoSpyBean
    private CandleBackfillJob backfill;

    private final List<Boolean> transactionDuringCall = new CopyOnWriteArrayList<>();
    private MarketTestData market;
    private UUID pair;
    private String symbol;

    @BeforeEach
    void setUp() {
        market = new MarketTestData(sql, Instant.now());
        symbol = "TX" + Long.toString(System.nanoTime() % 1_000_000_000L, 36).toUpperCase() + "USDT";
        pair = market.pair(symbol, true, true, "TRADING", "TRADING", 900);
        Instant open = Instant.now().truncatedTo(ChronoUnit.HOURS);
        when(exchange.klines(any(BinanceVenue.class), anyString(), anyString(), any(), any(), anyInt()))
                .thenAnswer(call -> {
                    transactionDuringCall.add(TransactionSynchronizationManager.isActualTransactionActive());
                    return List.of(new Kline(
                            open,
                            open.plus(Duration.ofHours(1)).minusMillis(1),
                            BigDecimal.ONE,
                            BigDecimal.TEN,
                            BigDecimal.ONE,
                            BigDecimal.TWO,
                            BigDecimal.ONE,
                            BigDecimal.TEN,
                            3));
                });
    }

    @AfterEach
    void removePair() {
        market.remove(pair);
    }

    @Test
    void TR35_noTransactionIsActive_whileTheExchangeAnswers() {
        charts.candles("spot", symbol, "5m", 3, null, true);
        charts.candles("futures", symbol, "1h", 3, null, true);

        assertThat(transactionDuringCall).containsExactly(false, false);
    }
}
