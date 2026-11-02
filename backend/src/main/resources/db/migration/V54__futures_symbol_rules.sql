-- TR-39 (plan 10.4): the Futures filters of the exchange beyond the tick, step and minimum notional. PERCENT_PRICE keeps a
-- limit price near the mark (a BUY at most mark × multiplierUp, a SELL at least mark × multiplierDown); MAX_NUM_ORDERS
-- and MAX_NUM_ALGO_ORDERS cap the open orders of a pair. Null until the next symbol synchronisation reads them.
ALTER TABLE crypto_pair
    ADD COLUMN futures_multiplier_up         numeric(28, 12),
    ADD COLUMN futures_multiplier_down       numeric(28, 12),
    ADD COLUMN futures_max_num_orders        integer,
    ADD COLUMN futures_max_num_algo_orders   integer;

-- A stored rule is a positive figure; zero is stored as null.
ALTER TABLE crypto_pair
    ADD CONSTRAINT ck_crypto_pair_futures_rules_positive CHECK (
        coalesce(futures_multiplier_up, 1) > 0
        AND coalesce(futures_multiplier_down, 1) > 0
        AND coalesce(futures_max_num_orders, 1) > 0
        AND coalesce(futures_max_num_algo_orders, 1) > 0);
