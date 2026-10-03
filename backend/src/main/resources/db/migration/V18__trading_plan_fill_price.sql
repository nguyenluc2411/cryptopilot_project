-- The price a plan's entry was filled at (T-043).
--
-- A LIMIT entry reached by a candle fills at its entry price; a MARKET entry, or a LIMIT entry the last
-- price already reaches at activation, fills at the last price of that moment (BR-33), which the plan's
-- entry_price does not hold for a LIMIT. The journal of T-048 reads the fill price from here.
--
-- Set exactly when the plan is EXECUTED. Plans executed before this migration were filled at their
-- entry price, so they take it. That needs an entry price on every EXECUTED row; the check below stops
-- the migration with the count and the fix before any change, instead of failing on the constraint.

DO $$
DECLARE
    missing bigint;
BEGIN
    SELECT count(*) INTO missing
      FROM trading_plan
     WHERE plan_status = 'EXECUTED'
       AND entry_price IS NULL;
    IF missing > 0 THEN
        RAISE EXCEPTION 'V18 stopped: % EXECUTED trading_plan row(s) have a NULL entry_price, so fill_price cannot be set', missing
            USING HINT = 'Set entry_price of those rows to the price each plan was filled at, then run the migration again.';
    END IF;
END
$$;

ALTER TABLE trading_plan
    ADD COLUMN fill_price numeric(28, 12);

UPDATE trading_plan
   SET fill_price = entry_price
 WHERE plan_status = 'EXECUTED';

ALTER TABLE trading_plan
    ADD CONSTRAINT ck_trading_plan_fill_price CHECK ((plan_status = 'EXECUTED') = (fill_price IS NOT NULL));
