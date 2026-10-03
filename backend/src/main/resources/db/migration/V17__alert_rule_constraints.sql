-- T-054: alert rules.
--
-- * The bookkeeping the alert engine (T-055) reads back after a restart: the open time of the last candle an
--   ONCE_PER_BAR alert fired on, and the last value the condition was compared with, which a cross needs as its
--   "previous value" (BR-19, BR-20).
-- * The rules of BR-19 and SRS 3.4.2 the baseline left to the application: a cooldown of at least one minute, and an
--   expiry at most 90 days ahead. The expiry is measured from updated_at, the instant the rule was last written:
--   an edit can set a new expiry, and updated_at only moves forward, so a row that passed once keeps passing.
-- * The indicator names of SRS 3.4.2, and A-40: FUNDING_RATE and OPEN_INTEREST_CHANGE are Futures-only and are read
--   on the 1h timeframe, which the server sets. ck_alert_indicator_fields (V1) stays as it is.
-- * MACD_CROSS and EMA_CROSS compare two lines and take no threshold, so threshold_value is NULL exactly for them
--   (D-76). coalesce keeps a PRICE alert, whose indicator is NULL, from passing the check unevaluated.

ALTER TABLE alert
    ADD COLUMN last_bar_open_time   timestamptz,
    ADD COLUMN last_evaluated_value numeric(28, 12);

ALTER TABLE alert
    ALTER COLUMN threshold_value DROP NOT NULL;

ALTER TABLE alert
    ADD CONSTRAINT ck_alert_cooldown_minutes CHECK (cooldown_minutes IS NULL OR cooldown_minutes >= 1),
    ADD CONSTRAINT ck_alert_expiry_window
        CHECK (expires_at IS NULL OR expires_at <= updated_at + interval '90 days'),
    ADD CONSTRAINT ck_alert_indicator_name
        CHECK (indicator_name IS NULL
               OR indicator_name IN ('RSI_14', 'MACD_CROSS', 'EMA_CROSS', 'FUNDING_RATE', 'OPEN_INTEREST_CHANGE')),
    ADD CONSTRAINT ck_alert_futures_indicator
        CHECK (indicator_name IS NULL
               OR indicator_name NOT IN ('FUNDING_RATE', 'OPEN_INTEREST_CHANGE')
               OR (market_type = 'FUTURES' AND timeframe = '1h')),
    ADD CONSTRAINT ck_alert_threshold_presence
        CHECK ((threshold_value IS NULL) = (coalesce(indicator_name, '') IN ('MACD_CROSS', 'EMA_CROSS')));
