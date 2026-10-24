-- Fees and slippage in the risk panel (TP-0; D-116; BR-29, BR-31, BR-36).
--
-- trading_plan: the expected fees and slippage of the snapshot, taken at every save and at activation like the rest
-- of the snapshot (BR-31). They are nullable: plans saved before this migration have none, and nothing here guesses
-- them. trading_plan_warning: the ninth warning type, HIGH_FEE_SHARE. system_setting: its threshold. Earlier
-- migrations are not edited.

ALTER TABLE trading_plan
    ADD COLUMN estimated_entry_fee            numeric(28, 8),
    ADD COLUMN estimated_stop_loss_exit_fee   numeric(28, 8),
    ADD COLUMN estimated_take_profit_exit_fee numeric(28, 8),
    ADD COLUMN estimated_slippage             numeric(28, 8),
    ADD COLUMN loss_at_stop_loss              numeric(28, 8),
    ADD COLUMN fee_share_of_risk              numeric(20, 8),
    ADD COLUMN net_risk_reward_ratio          numeric(20, 8);

-- The figures are stored together or not at all, and none of them is negative.
ALTER TABLE trading_plan
    ADD CONSTRAINT ck_trading_plan_fee_estimates
        CHECK ((estimated_entry_fee IS NULL) = (estimated_stop_loss_exit_fee IS NULL)
           AND (estimated_entry_fee IS NULL) = (estimated_take_profit_exit_fee IS NULL)
           AND (estimated_entry_fee IS NULL) = (estimated_slippage IS NULL)
           AND (estimated_entry_fee IS NULL) = (loss_at_stop_loss IS NULL)
           AND (estimated_entry_fee IS NULL) = (fee_share_of_risk IS NULL)
           AND (estimated_entry_fee IS NULL) = (net_risk_reward_ratio IS NULL)),
    ADD CONSTRAINT ck_trading_plan_fee_estimates_not_negative
        CHECK (estimated_entry_fee >= 0 AND estimated_stop_loss_exit_fee >= 0
           AND estimated_take_profit_exit_fee >= 0 AND estimated_slippage >= 0
           AND loss_at_stop_loss > 0 AND fee_share_of_risk >= 0);

ALTER TABLE trading_plan_warning DROP CONSTRAINT ck_trading_plan_warning_type;

ALTER TABLE trading_plan_warning
    ADD CONSTRAINT ck_trading_plan_warning_type CHECK (warning_type IN (
        'LOW_RR', 'HIGH_LEVERAGE', 'SL_BEYOND_LIQUIDATION', 'OVERSIZED_POSITION',
        'INSUFFICIENT_CAPITAL', 'HIGH_FUNDING_RATE', 'WIDE_STOP_LOSS', 'TOTAL_OPEN_RISK',
        'HIGH_FEE_SHARE'));

-- A decimal at the scale of the column it is compared with (numeric(20,8)), as V3 does.
INSERT INTO system_setting (setting_key, setting_value, value_type, description, updated_by, updated_at)
VALUES
    ('FEE_RISK_WARN_RATIO', '0.20000000', 'DECIMAL',
     'BR-29. A plan whose expected fees and slippage at the stop loss are at least this share of its risk amount raises HIGH_FEE_SHARE at severity WARNING.',
     NULL, TIMESTAMPTZ '2026-01-01 00:00:00+00');
