-- The setup score is no longer stored (T-028, D-53).
--
-- A setup score is now read under a style preset: the stored component scores are weighted when read, so one
-- stored total would belong to no preset in particular. technical_indicator keeps the indicators, the nearest
-- support and resistance and the five component scores; score_version keeps the version of the component formulas.
-- No column for a direction is added: the score has none (A-07 closed).
--
-- Nothing has written technical_indicator before this migration (NSF-05 starts with T-028), so the column is
-- dropped empty.

ALTER TABLE technical_indicator DROP CONSTRAINT ck_technical_indicator_setup_score;

ALTER TABLE technical_indicator DROP COLUMN setup_score;
