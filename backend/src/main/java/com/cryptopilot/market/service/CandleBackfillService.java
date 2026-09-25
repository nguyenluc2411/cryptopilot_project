package com.cryptopilot.market.service;

import com.cryptopilot.market.MarketType;
import com.cryptopilot.market.client.BinanceClientException;
import com.cryptopilot.market.client.MarketInterval;
import com.cryptopilot.market.event.GapDetected;
import com.cryptopilot.market.model.BackfillRun;
import com.cryptopilot.market.model.GapFill;
import java.time.Instant;
import java.util.List;

/**
 * The use cases of {@link com.cryptopilot.market.service.impl.CandleBackfillServiceImpl}: the methods called from outside it (D-48).
 *
 * <p>Rule: NSF-02; BR-07, BR-08, BR-09; D-41, D-42; TECHNICAL_DESIGN 7.1 and 7.1.2.
 */
public interface CandleBackfillService {

    /**
     * Backfills one market until every series is current, the weight share is spent, or the exchange refuses.
     *
     * @throws BinanceClientException for a refusal other than {@code REJECTED} and {@code MALFORMED}; what was
     *     written before it stays written
     */
    BackfillRun backfill(MarketType market);

    /**
     * Fills one gap NSF-03 detected: the candles opened from {@code gap.from()} to {@code gap.to()}, and no later
     * one — the stream is storing those. Paged, paced and written exactly as a series is, so a gap costs what its
     * candles cost and is safe to repeat.
     *
     * <p>A gap the exchange rejects, or answers with a body that cannot be read, is a defect: logged and dropped,
     * because asking again would get the same answer. Every other refusal propagates to the job, which keeps the
     * gap and continues it at {@code retryAt} or with the next run.
     *
     * <p>Rule: NSF-02, NSF-03 (any detected gap triggers NSF-02); BR-08; TECHNICAL_DESIGN 7.1 steps 4 and 5; A-33.
     *
     * @throws BinanceClientException for a refusal other than {@code REJECTED} and {@code MALFORMED}
     */
    GapFill fillGap(GapDetected gap);

    /**
     * The holes inside the stored series of the last {@code gapScanWindow}, as gaps for {@link #fillGap}, for the
     * pairs this backfill targets (D-42). Run at start-up, it finds again whatever a gap reported before a restart
     * left unfilled — the queue of reported gaps lives in memory — and anything else missing inside a series.
     *
     * <p>A hole the exchange itself has (a maintenance window) is found at every start-up and costs one request
     * that returns nothing.
     *
     * <p>Rule: NSF-02, NSF-03 (gap detection); A-33.
     */
    List<GapDetected> storedGaps();

    /**
     * Where a series with no stored candle starts: the configured depth of its timeframe back from now (D-41).
     * NSF-03 uses it when the stream reaches a series before the backfill has.
     */
    Instant seriesStart(MarketInterval timeframe, Instant now);
}
