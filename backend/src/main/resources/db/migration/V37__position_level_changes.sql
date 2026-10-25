-- TP-E: the stop loss and take profit of an open simulated position can be changed (D-124; D-119 (5)).
--
-- stop_loss_price / take_profit_price stay the CURRENT levels, the only ones the exits read, versioned by the row's
-- version. original_stop_loss_price / original_take_profit_price keep the levels at the fill and never change; the
-- records opened before this migration had no change, so their current levels are their original ones.
--
-- stop_widened is set once a stop loss was moved further from the entry than the original one, and stays set;
-- level_change_count counts the changes. The realized R keeps the original planned risk (BR-42).
--
-- trading_journal_level_change: one row per change, with the levels before and after and its instant. A replay
-- reads it to apply, to each candle, the levels in force when the candle opened. Only the journal module writes
-- both tables. The history is trading history: deleting a journal row with changes is refused, never cascaded.
-- Earlier migrations are not edited.

ALTER TABLE trading_journal
    ADD COLUMN original_stop_loss_price   numeric(28, 12),
    ADD COLUMN original_take_profit_price numeric(28, 12),
    ADD COLUMN stop_widened               boolean NOT NULL DEFAULT false,
    ADD COLUMN level_change_count         integer NOT NULL DEFAULT 0;

UPDATE trading_journal
   SET original_stop_loss_price = stop_loss_price,
       original_take_profit_price = take_profit_price;

ALTER TABLE trading_journal
    ADD CONSTRAINT ck_trading_journal_level_change_count CHECK (level_change_count >= 0);

CREATE TABLE trading_journal_level_change (
    level_change_id       uuid            NOT NULL,
    journal_id            uuid            NOT NULL,
    changed_at            timestamptz     NOT NULL,
    old_stop_loss_price   numeric(28, 12),
    old_take_profit_price numeric(28, 12),
    new_stop_loss_price   numeric(28, 12),
    new_take_profit_price numeric(28, 12),
    created_at            timestamptz     NOT NULL,
    CONSTRAINT pk_trading_journal_level_change PRIMARY KEY (level_change_id),
    CONSTRAINT fk_trading_journal_level_change_journal
        FOREIGN KEY (journal_id) REFERENCES trading_journal (journal_id)
);

-- The history of one position, oldest first: the plan detail and the replay read it by position.
CREATE INDEX ix_trading_journal_level_change_journal ON trading_journal_level_change (journal_id, changed_at);
