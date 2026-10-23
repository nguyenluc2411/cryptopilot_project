-- The Spot symbol filters paper orders are checked against, and an idempotency key for paper order lists (TR-02,
-- TR-10; NSF-01).
--
-- crypto_pair held only the tick, the step and the minimum notional of each market, so a paper Spot order could not be
-- refused the way the exchange refuses it (PRICE_FILTER, LOT_SIZE, MARKET_LOT_SIZE, NOTIONAL, PERCENT_PRICE_BY_SIDE,
-- TRAILING_DELTA, MAX_NUM_ORDERS, MAX_NUM_ALGO_ORDERS). The symbol synchronisation writes them beside the others.
-- All nullable: a null rule is off, as the exchange means by 0, and a pair not synchronised since has none yet.
--
-- paper_order_list had no client key, so a retried placement placed a second group and locked its funds twice. The
-- key is the client's listClientOrderId, or a generated one; the groups placed before this migration take their own
-- list id, which is unique already.

ALTER TABLE crypto_pair
    ADD COLUMN spot_min_price                numeric(28, 12),
    ADD COLUMN spot_max_price                numeric(28, 12),
    ADD COLUMN spot_min_qty                  numeric(28, 12),
    ADD COLUMN spot_max_qty                  numeric(28, 12),
    ADD COLUMN spot_market_min_qty           numeric(28, 12),
    ADD COLUMN spot_market_max_qty           numeric(28, 12),
    ADD COLUMN spot_market_step_size         numeric(28, 12),
    ADD COLUMN spot_apply_min_to_market      boolean,
    ADD COLUMN spot_max_notional             numeric(28, 12),
    ADD COLUMN spot_apply_max_to_market      boolean,
    ADD COLUMN spot_bid_multiplier_up        numeric(28, 12),
    ADD COLUMN spot_bid_multiplier_down      numeric(28, 12),
    ADD COLUMN spot_ask_multiplier_up        numeric(28, 12),
    ADD COLUMN spot_ask_multiplier_down      numeric(28, 12),
    ADD COLUMN spot_min_trailing_above_delta integer,
    ADD COLUMN spot_max_trailing_above_delta integer,
    ADD COLUMN spot_min_trailing_below_delta integer,
    ADD COLUMN spot_max_trailing_below_delta integer,
    ADD COLUMN spot_max_num_orders           integer,
    ADD COLUMN spot_max_num_algo_orders      integer;

-- A stored rule is a positive figure; zero is stored as null.
ALTER TABLE crypto_pair
    ADD CONSTRAINT ck_crypto_pair_spot_rules_positive CHECK (
        coalesce(spot_min_price, 1) > 0
        AND coalesce(spot_max_price, 1) > 0
        AND coalesce(spot_min_qty, 1) > 0
        AND coalesce(spot_max_qty, 1) > 0
        AND coalesce(spot_market_min_qty, 1) > 0
        AND coalesce(spot_market_max_qty, 1) > 0
        AND coalesce(spot_market_step_size, 1) > 0
        AND coalesce(spot_max_notional, 1) > 0
        AND coalesce(spot_bid_multiplier_up, 1) > 0
        AND coalesce(spot_bid_multiplier_down, 1) > 0
        AND coalesce(spot_ask_multiplier_up, 1) > 0
        AND coalesce(spot_ask_multiplier_down, 1) > 0
        AND coalesce(spot_min_trailing_above_delta, 1) > 0
        AND coalesce(spot_max_trailing_above_delta, 1) > 0
        AND coalesce(spot_min_trailing_below_delta, 1) > 0
        AND coalesce(spot_max_trailing_below_delta, 1) > 0
        AND coalesce(spot_max_num_orders, 1) > 0
        AND coalesce(spot_max_num_algo_orders, 1) > 0);

ALTER TABLE paper_order_list ADD COLUMN list_client_order_id varchar(64);

UPDATE paper_order_list SET list_client_order_id = list_id::text;

ALTER TABLE paper_order_list
    ALTER COLUMN list_client_order_id SET NOT NULL,
    ADD CONSTRAINT uq_paper_order_list_client_order_id UNIQUE (account_id, list_client_order_id);
