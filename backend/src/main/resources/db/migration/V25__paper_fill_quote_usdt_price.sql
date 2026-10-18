-- PT-F1a, D-96: the USDT rate of a Spot fill's quote coin and its source. Rows before V25 stay NULL (USDT pairs only).
ALTER TABLE paper_fill ADD COLUMN quote_usdt_price numeric(28, 12);
ALTER TABLE paper_fill ADD COLUMN quote_usdt_price_source varchar(32);
ALTER TABLE paper_fill ADD CONSTRAINT ck_paper_fill_quote_usdt_price_source
    CHECK (quote_usdt_price_source IS NULL OR quote_usdt_price_source IN ('ONE', 'LIVE', 'CANDLE_1M', 'NONE'));
ALTER TABLE paper_fill ADD CONSTRAINT ck_paper_fill_quote_usdt_price
    CHECK ((quote_usdt_price_source IS NULL AND quote_usdt_price IS NULL)
        OR (quote_usdt_price_source = 'NONE' AND quote_usdt_price IS NULL)
        OR (quote_usdt_price_source <> 'NONE' AND quote_usdt_price > 0));
