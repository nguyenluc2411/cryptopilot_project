-- TR-13: what a Spot holding cost, in USDT, so its average cost and unrealised PnL can be shown as Binance's Spot
-- PnL does. A buy adds what it paid; a sale takes away the share of the cost it sold; the opening grant is valued at
-- the last price of its coin when the account opened. NULL when that cost is not known: a holding granted while no
-- price of its coin was known, or one held before this column existed.
ALTER TABLE paper_balance ADD COLUMN cost_basis_amount numeric(28, 8);
ALTER TABLE paper_balance ADD CONSTRAINT ck_paper_balance_cost_basis
    CHECK (cost_basis_amount IS NULL OR cost_basis_amount >= 0);
