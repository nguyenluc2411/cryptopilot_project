-- Spot paper orders take the order types of the exchange's Spot API: STOP_LOSS and TAKE_PROFIT (triggered, then
-- executed as MARKET), STOP_LOSS_LIMIT and TAKE_PROFIT_LIMIT (triggered, then a LIMIT), each with a stopPrice and/or a
-- trailingDelta, besides MARKET, LIMIT and LIMIT_MAKER. The kinds of V21 that named Futures orders in the Spot form
-- (STOP_LIMIT, TRAILING_STOP_LIMIT) are replaced, and the Futures types are those of the exchange's Futures API:
-- STOP and TAKE_PROFIT (limit), STOP_MARKET, TAKE_PROFIT_MARKET, TRAILING_STOP_MARKET.
--
-- On a Spot stop, stop_price is the exchange's stopPrice: the trigger of a plain stop, the activation of a trailing
-- one; callback_rate holds the trailing delta in percent (trailingDelta / 100); trigger_condition is the side of the
-- last price the stop condition is met on, fixed by the type and the side (STOP_LOSS BUY and TAKE_PROFIT SELL at or
-- above, STOP_LOSS SELL and TAKE_PROFIT BUY at or below).

ALTER TABLE paper_order DROP CONSTRAINT ck_paper_order_order_type;
ALTER TABLE paper_order DROP CONSTRAINT ck_paper_order_limit_price;
ALTER TABLE paper_order DROP CONSTRAINT ck_paper_order_stop_price;
ALTER TABLE paper_order DROP CONSTRAINT ck_paper_order_trailing;

-- The Spot stop limits stored under the earlier name: a stop the price rises to on a buy, or falls to on a sale, is a
-- stop loss; the other two are take profits.
UPDATE paper_order
   SET order_type = CASE
           WHEN (side = 'BUY' AND trigger_condition = 'AT_OR_ABOVE')
                OR (side = 'SELL' AND trigger_condition = 'AT_OR_BELOW') THEN 'STOP_LOSS_LIMIT'
           ELSE 'TAKE_PROFIT_LIMIT'
       END
 WHERE market_type = 'SPOT' AND order_type = 'STOP_LIMIT';

-- The Spot trailing stops stored under the earlier name: one without an activation price tracked from its placement,
-- a STOP_LOSS_LIMIT with a trailing delta and no stop; one with an activation price beyond the price on its favourable
-- side, a TAKE_PROFIT_LIMIT whose stop is that activation price.
UPDATE paper_order
   SET order_type = CASE WHEN activation_price IS NULL THEN 'STOP_LOSS_LIMIT' ELSE 'TAKE_PROFIT_LIMIT' END,
       stop_price = activation_price,
       trigger_condition = CASE
           WHEN activation_price IS NULL THEN trigger_condition
           WHEN side = 'SELL' THEN 'AT_OR_ABOVE'
           ELSE 'AT_OR_BELOW'
       END,
       activation_price = NULL
 WHERE market_type = 'SPOT' AND order_type = 'TRAILING_STOP_LIMIT';

ALTER TABLE paper_order ADD CONSTRAINT ck_paper_order_order_type CHECK (order_type IN (
    'MARKET', 'LIMIT', 'LIMIT_MAKER', 'STOP_LOSS', 'STOP_LOSS_LIMIT', 'TAKE_PROFIT', 'TAKE_PROFIT_LIMIT', 'STOP',
    'STOP_MARKET', 'TAKE_PROFIT_MARKET', 'TRAILING_STOP_MARKET'));

-- A limit leg waits at its price for as long as its time in force says: on Spot LIMIT, STOP_LOSS_LIMIT and
-- TAKE_PROFIT_LIMIT, on Futures LIMIT, STOP and TAKE_PROFIT. A LIMIT_MAKER has a price and no time in force.
ALTER TABLE paper_order ADD CONSTRAINT ck_paper_order_limit_price CHECK (
    (order_type IN ('LIMIT', 'STOP_LOSS_LIMIT', 'TAKE_PROFIT_LIMIT')
        OR (market_type = 'FUTURES' AND order_type IN ('STOP', 'TAKE_PROFIT')))
    = (limit_price IS NOT NULL AND time_in_force IS NOT NULL));

-- A Spot stop waits for its stopPrice, its trailing delta, or both; a Futures stop for its stopPrice.
ALTER TABLE paper_order ADD CONSTRAINT ck_paper_order_stop_price CHECK (
    CASE
        WHEN market_type = 'SPOT' AND order_type IN ('STOP_LOSS', 'STOP_LOSS_LIMIT', 'TAKE_PROFIT', 'TAKE_PROFIT_LIMIT')
            THEN (stop_price IS NOT NULL OR callback_rate IS NOT NULL) AND trigger_condition IS NOT NULL
        WHEN market_type = 'FUTURES' AND order_type IN ('STOP', 'STOP_MARKET', 'TAKE_PROFIT', 'TAKE_PROFIT_MARKET')
            THEN stop_price IS NOT NULL AND trigger_condition IS NOT NULL
        ELSE TRUE
    END);

-- A trailing delta belongs to a Spot stop or to a Futures TRAILING_STOP_MARKET, which always has one.
ALTER TABLE paper_order ADD CONSTRAINT ck_paper_order_trailing CHECK (
    (order_type <> 'TRAILING_STOP_MARKET' OR (callback_rate IS NOT NULL AND trigger_condition IS NOT NULL))
    AND (callback_rate IS NULL
        OR order_type = 'TRAILING_STOP_MARKET'
        OR (market_type = 'SPOT'
            AND order_type IN ('STOP_LOSS', 'STOP_LOSS_LIMIT', 'TAKE_PROFIT', 'TAKE_PROFIT_LIMIT'))));
