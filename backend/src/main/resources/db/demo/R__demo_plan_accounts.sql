-- One demo Trader on each plan tier, for a development database only (T-108; BR-62, UC-53; D-62).
--
-- Like R__demo_dataset.sql this file lives in db/demo, which only the dev profile adds to the Flyway locations, so
-- staging and production never reach it. It needs the packages of V11 and nothing of the other demo file.
--
--   trader.free@cryptopilot.invalid      no subscription, so the FREE plan
--   trader.pro@cryptopilot.invalid       a PAID order for PRO_YEARLY and its ACTIVE subscription
--   trader.premium@cryptopilot.invalid   a PAID order for PREMIUM_YEARLY and its ACTIVE subscription
--
-- The password hash is the Flyway placeholder demo_password_hash, filled from DEMO_PASSWORD_HASH (a hash produced by
-- the application's password encoder); nothing in this file can open an account. The .invalid addresses of RFC 2606
-- can never belong to a person.
--
-- Time. The accounts carry the project epoch like every other seeded row. The subscriptions cannot: a year from the
-- epoch ends on 2027-01-01, before the capstone demonstration, and now() would make the rows differ per database. They
-- start on the fixed instant 2026-09-28 00:00 UTC, the day the accounts were added, and run 365 days. Each order
-- copies the package price (BR-56) and would have expired 15 minutes after it was created (BR-54).
--
-- Idempotency. Literal version 7 keys and ON CONFLICT DO NOTHING on the primary key: a re-run changes nothing.
--
-- Reference: Humble, J., & Farley, D. (2010). Continuous Delivery, ch. 12. Addison-Wesley — sample data is kept in its
--   own set, applied per environment, and never reaches production.

INSERT INTO user_account (
    user_id, email, password_hash, role, account_status, email_verified_at,
    last_login_at, created_at, updated_at, version)
VALUES
    ('019b76da-a800-7d01-8000-000000000001', 'trader.free@cryptopilot.invalid', '${demo_password_hash}',
     'TRADER', 'ACTIVE', TIMESTAMPTZ '2026-01-01 00:00:00+00', NULL,
     TIMESTAMPTZ '2026-01-01 00:00:00+00', TIMESTAMPTZ '2026-01-01 00:00:00+00', 0),
    ('019b76da-a800-7d01-8000-000000000002', 'trader.pro@cryptopilot.invalid', '${demo_password_hash}',
     'TRADER', 'ACTIVE', TIMESTAMPTZ '2026-01-01 00:00:00+00', NULL,
     TIMESTAMPTZ '2026-01-01 00:00:00+00', TIMESTAMPTZ '2026-01-01 00:00:00+00', 0),
    ('019b76da-a800-7d01-8000-000000000003', 'trader.premium@cryptopilot.invalid', '${demo_password_hash}',
     'TRADER', 'ACTIVE', TIMESTAMPTZ '2026-01-01 00:00:00+00', NULL,
     TIMESTAMPTZ '2026-01-01 00:00:00+00', TIMESTAMPTZ '2026-01-01 00:00:00+00', 0)
ON CONFLICT (user_id) DO NOTHING;

-- Every account has a profile from registration on (UC-01); the defaults are the ones of the demo trader.
INSERT INTO user_profile (
    user_id, display_name, avatar_url, default_capital, default_risk_percent, trading_style,
    notify_email, notify_push, created_at, updated_at, version)
VALUES
    ('019b76da-a800-7d01-8000-000000000001', 'Free Trader', NULL, 1000.00000000, 1.000, NULL, true, true,
     TIMESTAMPTZ '2026-01-01 00:00:00+00', TIMESTAMPTZ '2026-01-01 00:00:00+00', 0),
    ('019b76da-a800-7d01-8000-000000000002', 'Pro Trader', NULL, 1000.00000000, 1.000, NULL, true, true,
     TIMESTAMPTZ '2026-01-01 00:00:00+00', TIMESTAMPTZ '2026-01-01 00:00:00+00', 0),
    ('019b76da-a800-7d01-8000-000000000003', 'Premium Trader', NULL, 1000.00000000, 1.000, NULL, true, true,
     TIMESTAMPTZ '2026-01-01 00:00:00+00', TIMESTAMPTZ '2026-01-01 00:00:00+00', 0)
ON CONFLICT (user_id) DO NOTHING;

INSERT INTO subscription_order (
    order_id, user_id, package_id, order_code, amount, currency, payment_gateway, order_status,
    gateway_transaction_no, gateway_response_code, created_at, ipn_received_at, paid_at, expires_at,
    updated_at, version)
VALUES
    ('019b76da-a800-7d02-8000-000000000001', '019b76da-a800-7d01-8000-000000000002',
     '019b76da-a800-7002-8000-000000000003', 'DEMO-PRO-YEARLY', 990000, 'VND', 'VNPAY', 'PAID',
     NULL, NULL, TIMESTAMPTZ '2026-09-28 00:00:00+00', NULL, TIMESTAMPTZ '2026-09-28 00:00:00+00',
     TIMESTAMPTZ '2026-09-28 00:15:00+00', TIMESTAMPTZ '2026-09-28 00:00:00+00', 0),
    ('019b76da-a800-7d02-8000-000000000002', '019b76da-a800-7d01-8000-000000000003',
     '019b76da-a800-7002-8000-000000000005', 'DEMO-PREMIUM-YEARLY', 1990000, 'VND', 'VNPAY', 'PAID',
     NULL, NULL, TIMESTAMPTZ '2026-09-28 00:00:00+00', NULL, TIMESTAMPTZ '2026-09-28 00:00:00+00',
     TIMESTAMPTZ '2026-09-28 00:15:00+00', TIMESTAMPTZ '2026-09-28 00:00:00+00', 0)
ON CONFLICT (order_id) DO NOTHING;

INSERT INTO user_subscription (
    subscription_id, order_id, start_at, end_at, subscription_status, created_at, updated_at, version)
VALUES
    ('019b76da-a800-7d03-8000-000000000001', '019b76da-a800-7d02-8000-000000000001',
     TIMESTAMPTZ '2026-09-28 00:00:00+00', TIMESTAMPTZ '2027-09-28 00:00:00+00', 'ACTIVE',
     TIMESTAMPTZ '2026-09-28 00:00:00+00', TIMESTAMPTZ '2026-09-28 00:00:00+00', 0),
    ('019b76da-a800-7d03-8000-000000000002', '019b76da-a800-7d02-8000-000000000002',
     TIMESTAMPTZ '2026-09-28 00:00:00+00', TIMESTAMPTZ '2027-09-28 00:00:00+00', 'ACTIVE',
     TIMESTAMPTZ '2026-09-28 00:00:00+00', TIMESTAMPTZ '2026-09-28 00:00:00+00', 0)
ON CONFLICT (subscription_id) DO NOTHING;
