-- TR-39 (plan 10.4): the rest of the Futures filters paper Futures orders are checked against, as V30 did for Spot.
-- PRICE_FILTER bounds a price (minPrice, maxPrice), LOT_SIZE a quantity (minQty, maxQty) and MARKET_LOT_SIZE the
-- quantity of a MARKET order (minQty, maxQty, stepSize). All nullable: a null rule is off, as the exchange means by 0,
-- and a pair not synchronised since has none yet.
ALTER TABLE crypto_pair
    ADD COLUMN futures_min_price          numeric(28, 12),
    ADD COLUMN futures_max_price          numeric(28, 12),
    ADD COLUMN futures_min_qty            numeric(28, 12),
    ADD COLUMN futures_max_qty            numeric(28, 12),
    ADD COLUMN futures_market_min_qty     numeric(28, 12),
    ADD COLUMN futures_market_max_qty     numeric(28, 12),
    ADD COLUMN futures_market_step_size   numeric(28, 12);

-- A stored rule is a positive figure; zero is stored as null.
ALTER TABLE crypto_pair
    ADD CONSTRAINT ck_crypto_pair_futures_filters_positive CHECK (
        coalesce(futures_min_price, 1) > 0
        AND coalesce(futures_max_price, 1) > 0
        AND coalesce(futures_min_qty, 1) > 0
        AND coalesce(futures_max_qty, 1) > 0
        AND coalesce(futures_market_min_qty, 1) > 0
        AND coalesce(futures_market_max_qty, 1) > 0
        AND coalesce(futures_market_step_size, 1) > 0);
