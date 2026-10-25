-- T-048b: closing a simulated trade (BR-35, BR-36, BR-37; D-97 and its note of 2026-10-24; D-119).
--
-- liquidation_price: the liquidation price of a Futures position at its fill price, written when the journal opens
-- the record; the mark price book reads it, with the current stop loss and take profit, from this row only.
--
-- funding_incomplete, missing_funding_times: a close is never blocked by a funding settlement not stored yet. The row
-- keeps the known sum in funding_fee_amount, the flag and the settlement instants known to be missing; the journal
-- recomputes the funding once a late settlement is stored and clears the flag. Spot has no funding: null amount, no
-- flag.
--
-- The exit columns (exit_price, exit_time, close_reason, realized_pnl, fee_amount) exist since V1; net_pnl is derived
-- and has no column (D-97). Earlier migrations are not edited.

ALTER TABLE trading_journal
    ADD COLUMN liquidation_price     numeric(28, 12),
    ADD COLUMN funding_incomplete    boolean       NOT NULL DEFAULT false,
    ADD COLUMN missing_funding_times timestamptz[];

ALTER TABLE trading_journal
    ADD CONSTRAINT ck_trading_journal_liquidation_price
        CHECK (liquidation_price IS NULL OR (liquidation_price > 0 AND market_type = 'FUTURES')),
    -- Only a trade with funding can have an incomplete funding, and only an incomplete one names missing times.
    ADD CONSTRAINT ck_trading_journal_funding_incomplete
        CHECK (NOT funding_incomplete OR funding_fee_amount IS NOT NULL),
    ADD CONSTRAINT ck_trading_journal_missing_funding_times
        CHECK (funding_incomplete OR missing_funding_times IS NULL),
    -- An OPEN record has no exit yet; a CLOSED simulated record has all of it.
    ADD CONSTRAINT ck_trading_journal_open_has_no_exit
        CHECK (trade_status <> 'OPEN'
               OR (exit_price IS NULL AND exit_time IS NULL AND close_reason IS NULL AND realized_pnl IS NULL)),
    ADD CONSTRAINT ck_trading_journal_closed_simulated_exit
        CHECK (trade_status <> 'CLOSED' OR source <> 'SIMULATED'
               OR (exit_price IS NOT NULL AND exit_time IS NOT NULL AND close_reason IS NOT NULL
                   AND realized_pnl IS NOT NULL AND fee_amount IS NOT NULL));

-- The Futures records opened before this migration take their plan's estimated liquidation price, so their open
-- positions can be liquidated too; a price of 0 (no price liquidates the position) stays null.
UPDATE trading_journal j
   SET liquidation_price = p.estimated_liquidation_price
  FROM trading_plan p
 WHERE j.plan_id = p.plan_id
   AND j.market_type = 'FUTURES'
   AND p.estimated_liquidation_price > 0;

-- The journal's funding job reads the closed trades still waiting for a settlement.
CREATE INDEX ix_trading_journal_funding_incomplete ON trading_journal (exit_time) WHERE funding_incomplete;
