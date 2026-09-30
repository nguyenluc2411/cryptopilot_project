-- TOTAL_OPEN_RISK, severity WARNING: the plan's risk plus the risk of the Trader's other ACTIVE plans and open
-- positions is above the maximum total open risk of the Trader's risk profile (T-037; BR-29, BR-66; D-53; Q-27).
-- The CHECK of V1 is replaced with one that also accepts the new type; V1 is not edited.

ALTER TABLE trading_plan_warning DROP CONSTRAINT ck_trading_plan_warning_type;

ALTER TABLE trading_plan_warning
    ADD CONSTRAINT ck_trading_plan_warning_type CHECK (warning_type IN (
        'LOW_RR', 'HIGH_LEVERAGE', 'SL_BEYOND_LIQUIDATION', 'OVERSIZED_POSITION',
        'INSUFFICIENT_CAPITAL', 'HIGH_FUNDING_RATE', 'WIDE_STOP_LOSS', 'TOTAL_OPEN_RISK'));
