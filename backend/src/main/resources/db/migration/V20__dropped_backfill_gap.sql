-- Where the backfill records a gap it has given up on (NSF-02, review finding R-13).
--
-- A queued gap whose fill fails for a reason other than the exchange is dropped after three failures in a
-- row, so that it stops holding up the market's backfill. Logged only, the drop could be found in no other
-- way, and the start-up scan or the stream would queue the same hole again. One row per dropped range keeps
-- it visible and keeps it from being retried automatically: a gap inside a recorded range is not queued.
--
-- A technical table like binance_ban and matching_watermark: operational state of the application, not a
-- business entity of the logical model, keyed by the range rather than by a UUID, written by one upsert.
-- No foreign key to crypto_pair: a row of a pair that is gone is never read again and costs nothing.
-- last_error is a one-line summary (exception type and the first line of its message), never a stack trace.

CREATE TABLE dropped_backfill_gap (
    market_type   varchar(32)  NOT NULL,
    pair_id       uuid         NOT NULL,
    timeframe     varchar(32)  NOT NULL,
    gap_from      timestamptz  NOT NULL,
    gap_to        timestamptz  NOT NULL,
    symbol        varchar(32)  NOT NULL,
    failure_count integer      NOT NULL,
    last_error    varchar(500) NOT NULL,
    dropped_at    timestamptz  NOT NULL,
    CONSTRAINT pk_dropped_backfill_gap PRIMARY KEY (market_type, pair_id, timeframe, gap_from, gap_to),
    CONSTRAINT ck_dropped_backfill_gap_market_type CHECK (market_type IN ('SPOT', 'FUTURES')),
    CONSTRAINT ck_dropped_backfill_gap_range CHECK (gap_from <= gap_to),
    CONSTRAINT ck_dropped_backfill_gap_failure_count CHECK (failure_count > 0)
);
