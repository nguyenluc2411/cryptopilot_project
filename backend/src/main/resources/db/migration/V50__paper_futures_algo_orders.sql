-- TR-20: paper Futures conditional (algo) orders. An algo order is a trade_order row of type STOP, STOP_MARKET,
-- TAKE_PROFIT or TAKE_PROFIT_MARKET (allowed since V24) that waits for the mark or the last price; once triggered it
-- places the LIMIT or MARKET order it carries, which points back at it (the exchange's actualOrderId).
ALTER TABLE trade_order ADD COLUMN parent_order_id uuid;
ALTER TABLE trade_order ADD COLUMN price_protect boolean NOT NULL DEFAULT FALSE;
ALTER TABLE trade_order
    ADD CONSTRAINT fk_trade_order_parent FOREIGN KEY (parent_order_id) REFERENCES trade_order (order_id),
    -- Only a Futures order is placed by an algo order, and only a Futures algo order is protected.
    ADD CONSTRAINT ck_trade_order_parent CHECK (parent_order_id IS NULL OR market_type = 'FUTURES'),
    ADD CONSTRAINT ck_trade_order_price_protect CHECK (NOT price_protect OR market_type = 'FUTURES');
-- One order per conditional order; NULL, every order not placed by one, never collides.
CREATE UNIQUE INDEX uq_trade_order_parent ON trade_order (parent_order_id);
