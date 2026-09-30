-- The OVERSIZED_POSITION and HIGH_LEVERAGE thresholds move from system_setting to the Trader's risk profile
-- (T-037; BR-29, BR-66; D-53, A-34). The fixed 2 % and 20x that V3 seeded no longer apply to anybody: the risk per
-- trade and the maximum leverage come from the profile, and no profile goes above 10x. The three BR-29 thresholds
-- the profile does not define stay settings. V3, which seeded the two rows, is not edited.

DELETE FROM system_setting WHERE setting_key IN ('WARN_OVERSIZED_POSITION_RISK_PERCENT', 'WARN_HIGH_LEVERAGE');
