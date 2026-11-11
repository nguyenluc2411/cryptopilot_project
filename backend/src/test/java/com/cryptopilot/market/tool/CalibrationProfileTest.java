package com.cryptopilot.market.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cryptopilot.market.MarketTestData;
import com.cryptopilot.market.client.BinanceStreamClient;
import com.cryptopilot.market.config.SetupScoreProperties;
import com.cryptopilot.market.repository.CryptoPairRepository;
import com.cryptopilot.market.repository.MarketDataQueryRepository;
import com.cryptopilot.market.repository.OhlcvRepository;
import com.cryptopilot.support.OwnDatabaseConfig;
import com.cryptopilot.support.TestcontainersConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.WebApplicationContext;

/**
 * The {@code calibration} profile on top of {@code dev}, as a developer runs it: no web server, no market stream, no
 * scheduled job, the database read in a read-only transaction, and exit status 0 once the report is written. The
 * context runs on a database of its own, since the dev profile applies the demo set.
 *
 * <p>Rule: BR-13; TECHNICAL_DESIGN 7.4.
 */
// The profile turns Flyway off for a real run; the test's own empty database still needs the schema.
@SpringBootTest(
        properties = {
            OwnDatabaseConfig.PROPERTY + "=" + CalibrationProfileTest.DATABASE,
            "DB_PASSWORD=unused",
            "spring.flyway.enabled=true"
        })
@ActiveProfiles({"dev", "calibration"})
@Import({TestcontainersConfig.class, OwnDatabaseConfig.class})
class CalibrationProfileTest {

    static final String DATABASE = "cryptopilot_calibration";

    /** Replaced, so starting the context does not end the test JVM; the tests below run a real one. */
    @MockitoBean
    private CalibrationRunner startupRunner;

    /** The application's only scheduler (the STOMP broker's), which every job and {@code @Scheduled} method uses. */
    @MockitoSpyBean
    private TaskScheduler scheduler;

    @MockitoSpyBean
    private BinanceStreamClient streams;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private List<ScheduledTaskHolder> scheduledTasks;

    @Autowired
    private CryptoPairRepository pairs;

    @Autowired
    private OhlcvRepository candles;

    @Autowired
    private MarketDataQueryRepository marketData;

    @Autowired
    private SetupScoreProperties presets;

    @Autowired
    private Clock clock;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private JdbcClient sql;

    @TempDir
    Path out;

    @Test
    void calibrationProfile_onDev_startsNoServerNoStreamAndNoScheduler() {
        assertThat(context).isNotInstanceOf(WebApplicationContext.class);
        verify(streams, never()).shard(any(), anyString(), any(), any());
        verify(streams, never()).publicShard(anyString(), any(), any());
        verify(scheduler, never()).schedule(any(Runnable.class), any(Trigger.class));
        verify(scheduler, never()).schedule(any(Runnable.class), any(Instant.class));
        verify(scheduler, never()).scheduleAtFixedRate(any(Runnable.class), any(Duration.class));
        verify(scheduler, never()).scheduleAtFixedRate(any(Runnable.class), any(Instant.class), any(Duration.class));
        verify(scheduler, never()).scheduleWithFixedDelay(any(Runnable.class), any(Duration.class));
        verify(scheduler, never()).scheduleWithFixedDelay(any(Runnable.class), any(Instant.class), any(Duration.class));
        assertThat(scheduledTasks)
                .allSatisfy(holder -> assertThat(holder.getScheduledTasks()).isEmpty());
    }

    @Test
    void calibrationRunner_whenReportWritten_readsReadOnlyAndExitsZero() {
        AtomicBoolean readOnly = new AtomicBoolean();
        CryptoPairRepository watched = mock(CryptoPairRepository.class);
        when(watched.findBySymbol(anyString())).thenAnswer(call -> {
            readOnly.set(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            return pairs.findBySymbol(call.getArgument(0));
        });
        AtomicInteger status = new AtomicInteger(-1);

        new CalibrationRunner(watched, candles, marketData, presets, clock, transactions, status::set)
                .run(new DefaultApplicationArguments("--pairs=BTCUSDT", "--candles=10", "--out=" + out));

        assertThat(status).hasValue(0);
        assertThat(readOnly).isTrue();
        assertThat(Files.exists(out.resolve("report.csv"))).isTrue();
        assertThat(Files.exists(out.resolve("summary.txt"))).isTrue();
    }

    /**
     * 1h candles on both markets with one missing hour, and a symbol that is not listed: the series restarts after the
     * gap, the unknown symbol is skipped, Futures without derivatives data is weighed on trend, momentum and level, and
     * the report holds a row per scored candle. Nothing is written by the run itself.
     */
    @Test
    void calibrationRunner_overStoredCandles_writesASampleForEachScoredCandle() throws Exception {
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        UUID pair = new MarketTestData(sql, t0).pair("CALIBUSDT", true, true, "TRADING", "TRADING", 0);
        for (String market : List.of("SPOT", "FUTURES")) {
            for (int i = 0; i < 520; i++) {
                if (i != 100) {
                    candle(pair, market, t0.plus(Duration.ofHours(i)), i);
                }
            }
        }
        Integer before =
                sql.sql("select count(*) from ohlcv").query(Integer.class).single();
        AtomicInteger status = new AtomicInteger(-1);

        new CalibrationRunner(pairs, candles, marketData, presets, clock, transactions, status::set)
                .run(new DefaultApplicationArguments("--pairs=CALIBUSDT, NOPEUSDT", "--candles=150", "--out=" + out));

        assertThat(status).hasValue(0);
        List<String> report = Files.readAllLines(out.resolve("report.csv"));
        assertThat(report).hasSizeGreaterThan(1);
        assertThat(report.subList(1, report.size()))
                .allSatisfy(row -> assertThat(row).startsWith("CALIBUSDT,").contains(",1h,DAY_TRADING,"))
                .anySatisfy(row -> assertThat(row).contains(",SPOT,"))
                .anySatisfy(row -> assertThat(row).contains(",FUTURES,"));
        assertThat(sql.sql("select count(*) from ohlcv").query(Integer.class).single())
                .isEqualTo(before);
    }

    @Test
    void calibrationRunner_withoutPairs_exitsOne() {
        AtomicInteger status = new AtomicInteger(-1);

        new CalibrationRunner(pairs, candles, marketData, presets, clock, transactions, status::set)
                .run(new DefaultApplicationArguments("--out=" + out));

        assertThat(status).hasValue(1);
        assertThat(Files.exists(out.resolve("report.csv"))).isFalse();
    }

    /** A closed 1h candle on a slow wave with a drift, so the trend, momentum and level all move. */
    private void candle(UUID pair, String market, Instant open, int i) {
        double close = 100 + 10 * Math.sin(i / 7.0) + i * 0.05;
        sql.sql("""
                        insert into ohlcv (pair_id, market_type, timeframe, open_time, close_time, open_price,
                                           high_price, low_price, close_price, base_volume, quote_volume, trade_count)
                        values (?, ?, '1h', ?, ?, ?, ?, ?, ?, ?, 1000, 5)""")
                .params(
                        pair,
                        market,
                        Timestamp.from(open),
                        Timestamp.from(open.plus(Duration.ofHours(1)).minusMillis(1)),
                        close - 0.5,
                        close + 1,
                        close - 1,
                        close,
                        10 + i % 5)
                .update();
    }
}
