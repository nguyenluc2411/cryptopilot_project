-- Where the matching engine stopped (T-043).
--
-- One row per market and pair: the open time of the last 1-minute candle that was closed and fully
-- matched. After a restart the engine fetches the closed 1-minute candles from the next one on and
-- replays them through the same path as live updates (A-04, ADR-011). Advanced only when every fill
-- that candle decided is stored, and never moved backwards.
--
-- A technical table like binance_ban: operational state of the application, not a business entity of
-- the logical model, keyed by market and pair rather than by a UUID, written by one upsert. No foreign
-- key: a row of a pair that is gone is never read again and costs nothing.

CREATE TABLE matching_watermark (
    market_type           varchar(32) NOT NULL,
    pair_id               uuid        NOT NULL,
    last_candle_open_time timestamptz NOT NULL,
    updated_at            timestamptz NOT NULL,
    CONSTRAINT pk_matching_watermark PRIMARY KEY (market_type, pair_id),
    CONSTRAINT ck_matching_watermark_market_type CHECK (market_type IN ('SPOT', 'FUTURES'))
);
