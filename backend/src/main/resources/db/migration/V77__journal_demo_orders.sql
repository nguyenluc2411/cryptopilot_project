-- DEF-03, DEF-05 (Report 5): one engine for plans and demo orders. A plan no longer fills by itself; "Apply plan"
-- places a paper order that carries the plan (trade_order.plan_id, V47). Every paper fill now reaches the journal:
-- source SIMULATED when its order carries a plan, DEMO_ORDER when the Trader placed it directly (BM04 F-06, F-07).
--
-- paper_order_id: the Spot BUY order a Spot record was opened by; its later fills add to the same record.
-- paper_position_id: the paper Futures position a Futures record follows over its life.
-- exit_quantity, exit_notional, exit_realized_pnl, exit_fee_amount: what has been closed so far, while the record is
-- OPEN (a Spot sale or a Futures reduce may close part of it). They are summed into the exit columns when the record
-- closes; exit_price is then exit_notional / exit_quantity.
--
-- Rows opened by the plans' own engine before this migration (no paper link) are closed by that engine as before.
-- Earlier migrations are not edited.

ALTER TABLE trading_journal
    ADD COLUMN paper_order_id    uuid,
    ADD COLUMN paper_position_id uuid,
    ADD COLUMN exit_quantity     numeric(28, 12) NOT NULL DEFAULT 0,
    ADD COLUMN exit_notional     numeric(28, 12) NOT NULL DEFAULT 0,
    ADD COLUMN exit_realized_pnl numeric(28, 8)  NOT NULL DEFAULT 0,
    ADD COLUMN exit_fee_amount   numeric(28, 8)  NOT NULL DEFAULT 0;

ALTER TABLE trading_journal DROP CONSTRAINT ck_trading_journal_source;
ALTER TABLE trading_journal DROP CONSTRAINT ck_trading_journal_plan_source;
ALTER TABLE trading_journal DROP CONSTRAINT ck_trading_journal_closed_simulated_exit;

ALTER TABLE trading_journal
    ADD CONSTRAINT ck_trading_journal_source CHECK (source IN ('SIMULATED', 'MANUAL', 'DEMO_ORDER')),
    -- A plan's trade always names its plan; a manual entry and a demo order never do.
    ADD CONSTRAINT ck_trading_journal_plan_source CHECK (
        (source = 'SIMULATED' AND plan_id IS NOT NULL)
        OR (source IN ('MANUAL', 'DEMO_ORDER') AND plan_id IS NULL)),
    -- A record the system closed has all of its exit.
    ADD CONSTRAINT ck_trading_journal_closed_simulated_exit
        CHECK (trade_status <> 'CLOSED' OR source = 'MANUAL'
               OR (exit_price IS NOT NULL AND exit_time IS NOT NULL AND close_reason IS NOT NULL
                   AND realized_pnl IS NOT NULL AND fee_amount IS NOT NULL)),
    -- A demo order's record always comes from paper trading: a Spot buy order or a Futures position.
    ADD CONSTRAINT ck_trading_journal_demo_order_link
        CHECK (source <> 'DEMO_ORDER' OR paper_order_id IS NOT NULL OR paper_position_id IS NOT NULL),
    ADD CONSTRAINT ck_trading_journal_paper_link_market CHECK (
        (paper_order_id IS NULL OR market_type = 'SPOT')
        AND (paper_position_id IS NULL OR market_type = 'FUTURES')),
    ADD CONSTRAINT ck_trading_journal_exit_quantity
        CHECK (exit_quantity >= 0 AND exit_quantity <= quantity AND exit_notional >= 0 AND exit_fee_amount >= 0),
    ADD CONSTRAINT fk_trading_journal_paper_order
        FOREIGN KEY (paper_order_id) REFERENCES trade_order (order_id),
    ADD CONSTRAINT fk_trading_journal_paper_position
        FOREIGN KEY (paper_position_id) REFERENCES futures_position (position_id);

-- One record per Spot buy order and per Futures position (NULLs are distinct, so other records are not limited).
CREATE UNIQUE INDEX uq_trading_journal_paper_order ON trading_journal (paper_order_id);
CREATE UNIQUE INDEX uq_trading_journal_paper_position ON trading_journal (paper_position_id);
-- A Spot sale closes the Trader's open Spot records of the pair, oldest first.
CREATE INDEX idx_trading_journal_open_spot_paper
    ON trading_journal (user_id, pair_id, entry_time)
    WHERE trade_status = 'OPEN' AND paper_order_id IS NOT NULL;
