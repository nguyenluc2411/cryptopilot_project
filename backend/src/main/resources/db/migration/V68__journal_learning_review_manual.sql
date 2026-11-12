-- T-048c, T-049, T-050: learning data at the fill, trade review and manual trades (BR-40, BR-41, BR-70; D-111,
-- D-112, D-139). The learning columns exist since V32; earlier migrations are not edited.
--
-- 1. D-139 (2): simulated trades opened before the learning data was written take the planned entry price and the
--    planned risk amount of their plan, so their R figures use the plan, not the fill price fallback. Trades without
--    a plan keep the fallback. Nothing else of the learning data can be rebuilt: the scores, indicators and extremes
--    of past fills stay null.
-- 2. The chart image of a review is one file of the media storage: its key is kept so a replaced or removed image is
--    deleted from the storage, not only from the row.
-- 3. The review texts are limited to 4,000 characters (SRS 3.6.1); the limit is checked by the API and again here.

UPDATE trading_journal j
   SET planned_entry_price = coalesce(j.planned_entry_price, p.planned_entry_price, p.entry_price),
       risk_amount         = coalesce(j.risk_amount, p.risk_amount)
  FROM trading_plan p
 WHERE j.plan_id = p.plan_id
   AND j.source = 'SIMULATED'
   AND (j.planned_entry_price IS NULL OR j.risk_amount IS NULL);

ALTER TABLE trading_journal
    ADD COLUMN chart_image_public_id varchar(255);

ALTER TABLE trading_journal
    ADD CONSTRAINT ck_trading_journal_setup_description_length
        CHECK (setup_description IS NULL OR char_length(setup_description) <= 4000),
    ADD CONSTRAINT ck_trading_journal_lessons_learned_length
        CHECK (lessons_learned IS NULL OR char_length(lessons_learned) <= 4000),
    ADD CONSTRAINT ck_trading_journal_mistakes_length
        CHECK (mistakes IS NULL OR char_length(mistakes) <= 4000),
    -- A stored key always comes with the address it is served from.
    ADD CONSTRAINT ck_trading_journal_chart_image
        CHECK (chart_image_public_id IS NULL OR chart_image_url IS NOT NULL);
