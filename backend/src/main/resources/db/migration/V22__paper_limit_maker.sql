-- TR-12: a Spot LIMIT_MAKER order, as Binance has one: a limit order that only ever waits in the book. It is refused,
-- and never stored, when it would execute on arrival. It carries a price and no time in force, which the
-- ck_paper_order_limit_price check of V21 already allows for a type outside its list.
ALTER TABLE paper_order DROP CONSTRAINT ck_paper_order_order_type;
ALTER TABLE paper_order ADD CONSTRAINT ck_paper_order_order_type CHECK (order_type IN (
    'MARKET', 'LIMIT', 'LIMIT_MAKER', 'STOP_MARKET', 'STOP_LIMIT', 'TAKE_PROFIT_MARKET', 'TAKE_PROFIT_LIMIT',
    'TRAILING_STOP_MARKET', 'TRAILING_STOP_LIMIT'));
