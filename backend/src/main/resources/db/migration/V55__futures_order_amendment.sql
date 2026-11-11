-- TR-25 (plan 5.7): a working Futures LIMIT order may be amended (PUT /fapi/v1/order), its price and quantity changed
-- in place. amended_at is when it last changed price or grew, from which it waits again at its price (D-77: a price
-- traded before then never fills it); amend_count caps the amendments of one order at the exchange's 10,000.
ALTER TABLE trade_order
    ADD COLUMN amended_at   timestamptz,
    ADD COLUMN amend_count  integer NOT NULL DEFAULT 0;

ALTER TABLE trade_order
    ADD CONSTRAINT ck_trade_order_amend_count CHECK (amend_count >= 0 AND amend_count <= 10000);
