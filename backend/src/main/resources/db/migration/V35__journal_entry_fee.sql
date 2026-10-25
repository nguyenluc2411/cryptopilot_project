-- T-048a: the entry fee of a simulated trade, stored when the journal opens the record at the fill (BR-34, BR-36;
-- D-97, D-119). fee_amount keeps its meaning, entry fee + exit fee, and is written at the close (T-048b); the entry
-- fee alone stays readable after the close. Nullable: manual trades carry the fees the Trader entered in fee_amount
-- (BR-41). Earlier migrations are not edited.

ALTER TABLE trading_journal
    ADD COLUMN entry_fee_amount numeric(28, 8);

ALTER TABLE trading_journal
    ADD CONSTRAINT ck_trading_journal_entry_fee_amount CHECK (entry_fee_amount IS NULL OR entry_fee_amount >= 0);
