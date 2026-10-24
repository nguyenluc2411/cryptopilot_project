-- The verification resend limits of BR-65 as system settings, which SRS 3.11.8 lists among those an administrator
-- maintains on SCR-43 (T-082). The values are BR-65's: one mail per 60 seconds and five in any rolling 24 hours per
-- address. V32 is the trading plan validity migration of TP-A.

INSERT INTO system_setting (setting_key, setting_value, value_type, description, updated_by, updated_at)
VALUES
    ('RESEND_MIN_INTERVAL_SECONDS', '60', 'INT',
     'BR-65. Seconds an address waits between two verification emails.',
     NULL, TIMESTAMPTZ '2026-01-01 00:00:00+00'),
    ('RESEND_MAX_PER_24_HOURS', '5', 'INT',
     'BR-65. Verification emails one address may receive in any rolling 24 hours.',
     NULL, TIMESTAMPTZ '2026-01-01 00:00:00+00');
