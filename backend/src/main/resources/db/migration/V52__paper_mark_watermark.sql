-- TR-42: a Futures pair keeps two watermarks (plan 9.5, 10.2): the last 1-minute candle of the last price the engine
-- matched, as before, and the last 1-minute mark price candle it ran its mark triggers and liquidations over. Either
-- source is replayed from its own watermark, so a lost mark stream never replays the last price candles again.
-- A pair may have only a mark watermark yet, so the last price one becomes optional; one of the two is always set.
ALTER TABLE order_matching_watermark ALTER COLUMN last_candle_open_time DROP NOT NULL;
ALTER TABLE order_matching_watermark ADD COLUMN last_mark_candle_open_time timestamptz;
ALTER TABLE order_matching_watermark
    ADD CONSTRAINT ck_order_matching_watermark_set
        CHECK (last_candle_open_time IS NOT NULL OR last_mark_candle_open_time IS NOT NULL),
    ADD CONSTRAINT ck_order_matching_watermark_mark_futures
        CHECK (last_mark_candle_open_time IS NULL OR market_type = 'FUTURES');
