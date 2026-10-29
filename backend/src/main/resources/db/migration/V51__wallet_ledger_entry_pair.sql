-- TR-19: the income history of paper Futures names the pair of each realised profit, fee, funding and insurance cover,
-- as the exchange's income does. A funding fee's reference is derived from its position and settlement and points at
-- no row, so the pair is kept on the entry itself; every other entry leaves it NULL.
ALTER TABLE wallet_ledger_entry ADD COLUMN pair_id uuid;
ALTER TABLE wallet_ledger_entry
    ADD CONSTRAINT fk_wallet_ledger_entry_pair FOREIGN KEY (pair_id) REFERENCES crypto_pair (pair_id),
    ADD CONSTRAINT ck_wallet_ledger_entry_pair CHECK (pair_id IS NULL OR wallet_type = 'FUTURES');
CREATE INDEX idx_wallet_ledger_entry_pair ON wallet_ledger_entry (pair_id);

-- The entries written before this migration: a fill's pair, a position's pair. A funding fee written before it keeps
-- no pair (none exists outside a development database).
UPDATE wallet_ledger_entry e SET pair_id = f.pair_id
  FROM order_fill f
 WHERE e.wallet_type = 'FUTURES' AND e.ref_type = 'FILL' AND e.ref_id = f.fill_id;
UPDATE wallet_ledger_entry e SET pair_id = p.pair_id
  FROM futures_position p
 WHERE e.wallet_type = 'FUTURES' AND e.ref_type = 'POSITION' AND e.ref_id = p.position_id;
