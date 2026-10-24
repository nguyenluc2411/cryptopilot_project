-- Plan validity (TP-A; D-111, D-112; BR-33, BR-36, BR-68, BR-69, BR-70; A-48).
--
-- trading_plan: the STOP entry type, the INVALIDATED status with its instant and reason, the optional invalidation
-- price and maximum entry deviation, the planned entry price, the liquidity role of the fill, the analysis timeframe
-- and the setup score taken at activation. trading_journal: the learning columns of BR-70, all nullable; only the
-- journal writes them (T-048). system_setting: the thresholds of BR-36, BR-69 and BR-70. Earlier migrations are not
-- edited.

-- ---------------------------------------------------------------------------------------------------------------
-- trading_plan: entry type and status
-- ---------------------------------------------------------------------------------------------------------------

ALTER TABLE trading_plan
    DROP CONSTRAINT ck_trading_plan_entry_type,
    ADD CONSTRAINT ck_trading_plan_entry_type CHECK (entry_type IN ('LIMIT', 'MARKET', 'STOP')),
    -- A STOP waits at its stop price, which is kept in entry_price like a LIMIT price.
    DROP CONSTRAINT ck_trading_plan_limit_has_entry_price,
    ADD CONSTRAINT ck_trading_plan_limit_has_entry_price
        CHECK (entry_type NOT IN ('LIMIT', 'STOP') OR entry_price IS NOT NULL),
    DROP CONSTRAINT ck_trading_plan_status,
    ADD CONSTRAINT ck_trading_plan_status
        CHECK (plan_status IN ('DRAFT', 'ACTIVE', 'EXECUTED', 'CANCELLED', 'EXPIRED', 'INVALIDATED')),
    DROP CONSTRAINT ck_trading_plan_activated_at,
    ADD CONSTRAINT ck_trading_plan_activated_at
        CHECK (plan_status NOT IN ('ACTIVE', 'EXECUTED', 'EXPIRED', 'INVALIDATED') OR activated_at IS NOT NULL);

ALTER TABLE trading_plan
    ADD COLUMN invalidated_at        timestamptz,
    ADD COLUMN invalidation_reason   varchar(32),
    ADD COLUMN invalidation_price    numeric(28, 12),
    ADD COLUMN planned_entry_price   numeric(28, 12),
    ADD COLUMN max_entry_deviation_r numeric(12, 8),
    ADD COLUMN fill_liquidity        varchar(32),
    ADD COLUMN analysis_timeframe    varchar(32),
    ADD COLUMN setup_score_at_plan   numeric(5, 2),
    ADD COLUMN setup_score_preset    varchar(32);

-- ---------------------------------------------------------------------------------------------------------------
-- trading_plan: backfill
-- ---------------------------------------------------------------------------------------------------------------

-- The planned entry of a LIMIT is its price. A MARKET plan not yet filled still holds the reference price of its
-- last save; an EXECUTED MARKET plan holds the price it filled at, so its planned entry is unknown.
UPDATE trading_plan
   SET planned_entry_price = entry_price
 WHERE entry_type = 'LIMIT'
    OR (entry_type = 'MARKET' AND plan_status <> 'EXECUTED');

-- Plans filled before the liquidity role was recorded: a LIMIT counts as maker, a MARKET as taker.
UPDATE trading_plan
   SET fill_liquidity = CASE entry_type WHEN 'LIMIT' THEN 'MAKER' ELSE 'TAKER' END
 WHERE plan_status = 'EXECUTED';

-- The timeframe of the owner's style preset (D-112): SCALPING 15m, DAY 1h, SWING and POSITION 4h, none 1h.
UPDATE trading_plan p
   SET analysis_timeframe = CASE up.trading_style
                                WHEN 'SCALPING' THEN '15m'
                                WHEN 'SWING' THEN '4h'
                                WHEN 'POSITION' THEN '4h'
                                ELSE '1h'
                            END
  FROM user_account ua
  LEFT JOIN user_profile up ON up.user_id = ua.user_id
 WHERE ua.user_id = p.user_id;

ALTER TABLE trading_plan ALTER COLUMN analysis_timeframe SET NOT NULL;

-- ---------------------------------------------------------------------------------------------------------------
-- trading_plan: checks
-- ---------------------------------------------------------------------------------------------------------------

ALTER TABLE trading_plan
    ADD CONSTRAINT ck_trading_plan_invalidated_at
        CHECK ((plan_status = 'INVALIDATED') = (invalidated_at IS NOT NULL)),
    ADD CONSTRAINT ck_trading_plan_invalidation_reason_value
        CHECK (invalidation_reason IN ('TAKE_PROFIT_REACHED', 'STOP_LOSS_REACHED', 'INVALIDATION_PRICE')),
    ADD CONSTRAINT ck_trading_plan_invalidation_reason
        CHECK ((plan_status = 'INVALIDATED') = (invalidation_reason IS NOT NULL)),
    ADD CONSTRAINT ck_trading_plan_invalidation_price
        CHECK (invalidation_price IS NULL OR (entry_type <> 'MARKET' AND invalidation_price > 0)),
    -- The upper bound is the system's block value, a setting, so the application checks it.
    ADD CONSTRAINT ck_trading_plan_max_entry_deviation_r
        CHECK (max_entry_deviation_r IS NULL OR max_entry_deviation_r > 0),
    ADD CONSTRAINT ck_trading_plan_fill_liquidity_value CHECK (fill_liquidity IN ('MAKER', 'TAKER')),
    ADD CONSTRAINT ck_trading_plan_fill_liquidity
        CHECK ((plan_status = 'EXECUTED') = (fill_liquidity IS NOT NULL)),
    ADD CONSTRAINT ck_trading_plan_analysis_timeframe CHECK (analysis_timeframe IN ('15m', '1h', '4h', '1d')),
    ADD CONSTRAINT ck_trading_plan_setup_score_at_plan
        CHECK (setup_score_at_plan IS NULL OR setup_score_at_plan BETWEEN 0 AND 100);

-- ---------------------------------------------------------------------------------------------------------------
-- trading_journal: learning data of BR-70
-- ---------------------------------------------------------------------------------------------------------------

ALTER TABLE trading_journal
    ADD COLUMN planned_entry_price     numeric(28, 12),
    ADD COLUMN entry_deviation_r       numeric(20, 8),
    ADD COLUMN entry_liquidity         varchar(32),
    ADD COLUMN risk_amount             numeric(28, 8),
    ADD COLUMN analysis_timeframe      varchar(32),
    ADD COLUMN setup_score_preset      varchar(32),
    ADD COLUMN setup_score_at_plan     numeric(5, 2),
    ADD COLUMN setup_score_at_fill     numeric(5, 2),
    ADD COLUMN setup_score_drop_warned boolean,
    ADD COLUMN fill_indicators         jsonb,
    ADD COLUMN max_adverse_price       numeric(28, 12),
    ADD COLUMN max_favorable_price     numeric(28, 12);

ALTER TABLE trading_journal
    ADD CONSTRAINT ck_trading_journal_entry_liquidity CHECK (entry_liquidity IN ('MAKER', 'TAKER')),
    ADD CONSTRAINT ck_trading_journal_analysis_timeframe CHECK (analysis_timeframe IN ('15m', '1h', '4h', '1d'));

-- ---------------------------------------------------------------------------------------------------------------
-- system_setting
-- ---------------------------------------------------------------------------------------------------------------

-- Decimals at the scale of the column they are compared with (numeric(12,8)), as V3 does.
INSERT INTO system_setting (setting_key, setting_value, value_type, description, updated_by, updated_at)
VALUES
    ('ENTRY_DEVIATION_CONFIRM_R', '0.25000000', 'DECIMAL',
     'BR-69. A MARKET entry that moved from the planned entry by at least this many R needs the Trader''s confirmation.',
     NULL, TIMESTAMPTZ '2026-01-01 00:00:00+00'),
    ('ENTRY_DEVIATION_BLOCK_R', '0.75000000', 'DECIMAL',
     'BR-69. A MARKET entry that moved from the planned entry by more than this many R is refused; also the highest maximum deviation a plan may set.',
     NULL, TIMESTAMPTZ '2026-01-01 00:00:00+00'),
    ('SETUP_SCORE_DROP_WARN', '15', 'INT',
     'BR-70. A setup score that fell by at least this many points between activation and fill adds a warning; never blocks.',
     NULL, TIMESTAMPTZ '2026-01-01 00:00:00+00'),
    ('SLIPPAGE_BPS', '0', 'INT',
     'BR-36. Simulated slippage in basis points, applied against the Trader to taker fills only.',
     NULL, TIMESTAMPTZ '2026-01-01 00:00:00+00');

-- Fees go by liquidity role, not by entry type (D-111 E1).
UPDATE system_setting
   SET description = 'BR-36. Simulated spot maker fee rate, charged on orders that rested and were then reached: waiting limit entries and take profits.'
 WHERE setting_key = 'SPOT_MAKER_FEE';

UPDATE system_setting
   SET description = 'BR-36. Simulated spot taker fee rate, charged on fills that take liquidity: market and stop entries, limit entries marketable at activation, stop losses and manual closes.'
 WHERE setting_key = 'SPOT_TAKER_FEE';

UPDATE system_setting
   SET description = 'BR-36. Simulated futures maker fee rate, charged on orders that rested and were then reached: waiting limit entries and take profits.'
 WHERE setting_key = 'FUTURES_MAKER_FEE';

UPDATE system_setting
   SET description = 'BR-36. Simulated futures taker fee rate, charged on fills that take liquidity: market and stop entries, limit entries marketable at activation, stop losses and manual closes.'
 WHERE setting_key = 'FUTURES_TAKER_FEE';
