-- TimescaleDB: hypertables, compression and retention, plus the one schema change the retention
-- design forces.
--
-- Everything extension-specific lives in this migration and not in V1, so a database without the
-- extension fails at one identifiable step instead of half way through the table definitions.
--
-- Why a table is split here rather than in V1 (SRS 3.1.5, NSF-17, BR-37):
--   futures_market_data was designed to hold two kinds of row, told apart by snapshot_type:
--   PERIODIC snapshots that are worth ninety days, and FUNDING_SETTLEMENT rows that the profit and
--   loss calculation reads and that therefore have to be kept for the life of the account.
--   TimescaleDB retention drops whole chunks. It cannot look at a column, so no retention policy on
--   that table can keep one kind and drop the other: the first policy that removed a ninety-day-old
--   chunk would take the funding history in it. Splitting the two kinds into two tables is what
--   makes the policy expressible, so the split belongs with the policies and not before them.

CREATE EXTENSION IF NOT EXISTS timescaledb;

-- =============================================================================================
-- Settled funding rates leave futures_market_data
-- =============================================================================================

-- The settled funding rate of one pair at one settlement instant, as the exchange reported it
-- (/fapi/v1/fundingRate). Funding settles every eight hours, so this table grows by about three
-- rows per pair per day and stays small enough to need no chunking and no compression: it is
-- deliberately a plain table and deliberately has no retention policy. Losing a row here changes
-- a closed position's realized profit and loss, which is why it outlives every snapshot.
--
-- This is not the same number as futures_market_data.funding_rate. That column carries the
-- predicted rate of the moment, streamed continuously and read by the setup score; the column
-- below carries the rate that was actually charged, which is the only one the P/L may use.
CREATE TABLE funding_rate_history (
    pair_id      uuid            NOT NULL,
    funding_time timestamptz     NOT NULL,
    funding_rate numeric(12, 8)  NOT NULL,
    mark_price   numeric(28, 12) NOT NULL,
    CONSTRAINT pk_funding_rate_history PRIMARY KEY (pair_id, funding_time),
    CONSTRAINT fk_funding_rate_history_pair FOREIGN KEY (pair_id) REFERENCES crypto_pair (pair_id)
);

-- The primary key leads with pair_id, so the foreign key column is already indexed.

-- Carry across anything a development database already holds. The migration runs on an empty
-- schema in test and in CI, so this normally moves no rows; it exists so that a developer who
-- ingested futures data against V1 does not silently lose the settlements. A settlement row that
-- never received a rate or a mark price carries no information for the P/L and is not moved.
INSERT INTO funding_rate_history (pair_id, funding_time, funding_rate, mark_price)
SELECT pair_id, snapshot_time, funding_rate, mark_price
  FROM futures_market_data
 WHERE snapshot_type = 'FUNDING_SETTLEMENT'
   AND funding_rate IS NOT NULL
   AND mark_price IS NOT NULL
ON CONFLICT DO NOTHING;

DELETE FROM futures_market_data WHERE snapshot_type = 'FUNDING_SETTLEMENT';

-- Every row left is a periodic snapshot, so the column that told the two apart has nothing left to
-- say. It is dropped rather than left in place: a nullable discriminator with one value invites a
-- second kind of row to be written into a table that now has a ninety-day retention policy.
ALTER TABLE futures_market_data DROP CONSTRAINT ck_futures_market_data_snapshot_type;
ALTER TABLE futures_market_data DROP COLUMN snapshot_type;

-- =============================================================================================
-- Hypertables
-- =============================================================================================

-- The time column of each primary key becomes the partitioning dimension. The chunk intervals are
-- the ones of the physical design: thirty days for the candle tables, which are read in long
-- ranges, and seven days for the snapshot tables, which are written constantly and read recent.
SELECT create_hypertable('ohlcv', by_range('open_time', INTERVAL '30 days'));
SELECT create_hypertable('technical_indicator', by_range('open_time', INTERVAL '30 days'));
SELECT create_hypertable('spot_market_data', by_range('snapshot_time', INTERVAL '7 days'));
SELECT create_hypertable('futures_market_data', by_range('snapshot_time', INTERVAL '7 days'));

-- =============================================================================================
-- Compression
-- =============================================================================================

-- Candles and their indicators are never updated once the bar has closed, so a chunk older than
-- sixty days can be compressed. Segmenting by the three columns every query binds by equality
-- keeps a segment readable without decompressing its neighbours; ordering by time descending puts
-- the newest row of a segment first, which is how the tables are read.
ALTER TABLE ohlcv SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'pair_id, market_type, timeframe',
    timescaledb.compress_orderby = 'open_time DESC'
);
ALTER TABLE technical_indicator SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'pair_id, market_type, timeframe',
    timescaledb.compress_orderby = 'open_time DESC'
);

SELECT add_compression_policy('ohlcv', INTERVAL '60 days');
SELECT add_compression_policy('technical_indicator', INTERVAL '60 days');

-- =============================================================================================
-- Retention (NSF-17)
-- =============================================================================================

-- A spot snapshot is a cache of what the ticker said; a week is more than the charts ask for.
SELECT add_retention_policy('spot_market_data', INTERVAL '7 days');

-- Ninety days of periodic futures snapshots. This policy is only safe because the settled funding
-- rates are no longer in this table.
SELECT add_retention_policy('futures_market_data', INTERVAL '90 days');

-- No retention policy is added for ohlcv, technical_indicator or funding_rate_history. Candles and
-- indicators are the history the product is about, and the funding rates are needed to recompute a
-- closed position's profit and loss for as long as the journal keeps it.
