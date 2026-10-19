-- The clearance fee a liquidated Futures position pays instead of the taker fee (BR-36; D-97 as amended by Q-54;
-- T-044). One rate for every pair. Spot has no liquidation, so the Spot taker fee description no longer names one.
-- V3, which seeded the fee rates, is not edited.

INSERT INTO system_setting (setting_key, setting_value, value_type, description, updated_by, updated_at)
VALUES
    ('LIQUIDATION_CLEARANCE_FEE', '0.01250000', 'DECIMAL',
     'BR-36. Simulated liquidation clearance fee rate, charged on the notional at the liquidation price instead of the taker fee, never more than the maintenance margin left.',
     NULL, TIMESTAMPTZ '2026-01-01 00:00:00+00');

UPDATE system_setting
   SET description = 'BR-36. Simulated spot taker fee rate, charged on market entries, stop losses and manual closes.'
 WHERE setting_key = 'SPOT_TAKER_FEE';
