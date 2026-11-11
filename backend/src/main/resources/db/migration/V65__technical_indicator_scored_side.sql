-- Setup score component formulas v2 (BR-13, TECHNICAL_DESIGN 7.4).
--
-- v2 reads every component for one side: LONG on Spot (BR-21), the dominant side of the trend on Futures. The
-- side is stored beside the components it was scored for. Rows written by v1 keep a null side and are not
-- recomputed; score_version tells the two apart.

ALTER TABLE technical_indicator ADD COLUMN scored_side varchar(32);

ALTER TABLE technical_indicator
    ADD CONSTRAINT ck_technical_indicator_scored_side CHECK (scored_side IN ('LONG', 'SHORT'));
