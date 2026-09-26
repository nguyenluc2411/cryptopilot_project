package com.cryptopilot.market.repository;

import com.cryptopilot.market.MarketType;
import com.cryptopilot.market.model.ComponentScores;
import com.cryptopilot.market.model.StoredIndicators;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The {@code technical_indicator} hypertable, one row per closed candle keyed like {@code ohlcv}. Plain SQL for the
 * same reason as {@link OhlcvRepository}. A row computed again for the same candle replaces the stored one, so the
 * write is idempotent.
 *
 * <p>Rule: NSF-05; BR-08; TECHNICAL_DESIGN 5.4 and 7.4; D-53 (rule 3).
 */
@Repository
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class TechnicalIndicatorRepository {

    private static final String COLUMNS = """
            pair_id, market_type, timeframe, open_time, sma_20, ema_20, ema_50, ema_200, rsi_14, macd_line,
            macd_signal, macd_histogram, bb_upper, bb_middle, bb_lower, volume_sma_20, nearest_support,
            nearest_resistance, trend_score, momentum_score, volume_score, level_score, derivatives_score,
            score_version, calculated_at""";

    private final JdbcClient sql;

    /** Stores the row, replacing the one of the same candle. */
    public void upsert(StoredIndicators row) {
        ComponentScores c = row.components();
        sql.sql("insert into technical_indicator (" + COLUMNS + """
                        )
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        on conflict (pair_id, market_type, timeframe, open_time) do update set
                            sma_20 = excluded.sma_20, ema_20 = excluded.ema_20, ema_50 = excluded.ema_50,
                            ema_200 = excluded.ema_200, rsi_14 = excluded.rsi_14, macd_line = excluded.macd_line,
                            macd_signal = excluded.macd_signal, macd_histogram = excluded.macd_histogram,
                            bb_upper = excluded.bb_upper, bb_middle = excluded.bb_middle, bb_lower = excluded.bb_lower,
                            volume_sma_20 = excluded.volume_sma_20, nearest_support = excluded.nearest_support,
                            nearest_resistance = excluded.nearest_resistance, trend_score = excluded.trend_score,
                            momentum_score = excluded.momentum_score, volume_score = excluded.volume_score,
                            level_score = excluded.level_score, derivatives_score = excluded.derivatives_score,
                            score_version = excluded.score_version, calculated_at = excluded.calculated_at""")
                .params(
                        row.pairId(),
                        c.market().name(),
                        c.timeframe(),
                        Timestamp.from(row.openTime()),
                        row.sma20(),
                        row.ema20(),
                        row.ema50(),
                        row.ema200(),
                        row.rsi14(),
                        row.macdLine(),
                        row.macdSignal(),
                        row.macdHistogram(),
                        row.bbUpper(),
                        row.bbMiddle(),
                        row.bbLower(),
                        row.volumeSma20(),
                        row.nearestSupport(),
                        row.nearestResistance(),
                        c.trend(),
                        c.momentum(),
                        c.volume(),
                        c.level(),
                        c.derivatives(),
                        c.formulaVersion(),
                        Timestamp.from(row.calculatedAt()))
                .update();
    }

    /** The row of the latest candle of a series, or empty when none is stored (read by T-029). */
    public Optional<StoredIndicators> latest(UUID pairId, MarketType market, String timeframe) {
        return sql.sql("select " + COLUMNS + """

                          from technical_indicator
                         where pair_id = ? and market_type = ? and timeframe = ?
                         order by open_time desc
                         limit 1""")
                .params(pairId, market.name(), timeframe)
                .query((row, index) -> read(row))
                .optional();
    }

    /** The stored MACD histogram of one candle, or empty when the row is missing or had none. */
    public Optional<BigDecimal> macdHistogram(UUID pairId, MarketType market, String timeframe, Instant openTime) {
        return sql.sql("""
                        select macd_histogram
                          from technical_indicator
                         where pair_id = ? and market_type = ? and timeframe = ? and open_time = ?""")
                .params(pairId, market.name(), timeframe, Timestamp.from(openTime))
                .query((row, index) -> Optional.ofNullable(row.getBigDecimal("macd_histogram")))
                .optional()
                .flatMap(value -> value);
    }

    private static StoredIndicators read(ResultSet row) throws SQLException {
        MarketType market = MarketType.valueOf(row.getString("market_type"));
        ComponentScores components = new ComponentScores(
                market,
                row.getString("timeframe"),
                row.getString("score_version"),
                row.getBigDecimal("trend_score"),
                row.getBigDecimal("momentum_score"),
                row.getBigDecimal("volume_score"),
                row.getBigDecimal("level_score"),
                row.getBigDecimal("derivatives_score"));
        return new StoredIndicators(
                row.getObject("pair_id", UUID.class),
                row.getTimestamp("open_time").toInstant(),
                row.getBigDecimal("sma_20"),
                row.getBigDecimal("ema_20"),
                row.getBigDecimal("ema_50"),
                row.getBigDecimal("ema_200"),
                row.getBigDecimal("rsi_14"),
                row.getBigDecimal("macd_line"),
                row.getBigDecimal("macd_signal"),
                row.getBigDecimal("macd_histogram"),
                row.getBigDecimal("bb_upper"),
                row.getBigDecimal("bb_middle"),
                row.getBigDecimal("bb_lower"),
                row.getBigDecimal("volume_sma_20"),
                row.getBigDecimal("nearest_support"),
                row.getBigDecimal("nearest_resistance"),
                components,
                row.getTimestamp("calculated_at").toInstant());
    }
}
