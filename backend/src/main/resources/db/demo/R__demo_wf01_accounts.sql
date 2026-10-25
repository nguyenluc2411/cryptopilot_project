-- The accounts of the WF-01 demonstration (DEMO-01; BR-62, BR-66; UC-16 to UC-21), one per need of the scenarios.
--
-- Like the other files of db/demo, this one runs only where the demo location is on the Flyway path: the dev profile,
-- and the demo profile that a staging deployment adds (application-demo.yml). The base configuration and the prod
-- profile list db/migration alone, so production never reaches it.
--
--   demo.free@cryptopilot.invalid      FREE (no subscription), CONSERVATIVE, capital 10,000 USDT, risk 1 %
--   demo.pro@cryptopilot.invalid       PRO_YEARLY, BALANCED, capital 10,000 USDT, risk 1 %, style DAY
--   demo.premium@cryptopilot.invalid   PREMIUM_YEARLY, AGGRESSIVE, capital 50,000 USDT, risk 1 %
--
-- AGGRESSIVE is a stored choice: the confirmation of MSG48 is asked when a Trader switches to it and is not kept on the
-- profile, so the seeded row simply holds the profile the Trader confirmed.
--
-- The pairs, the leverage brackets and the three ACTIVE plans of demo.free are not here. Pairs are created by the
-- symbol synchronisation (NSF-01) after Flyway has run, and a plan is only valid when it is calculated by the plan
-- service against a current price; deploy/demo/load-wf01.ps1 sets both up through the API once the backend runs.
--
-- Password. The placeholder demo_password_hash, filled from DEMO_PASSWORD_HASH; nothing in this file opens an account.
-- The .invalid addresses of RFC 2606 can never belong to a person.
--
-- Time. The accounts carry the project epoch like every other seeded row. The subscriptions start on the fixed instant
-- 2026-10-25 00:00 UTC, the day they were added, and run 365 days, past the capstone defence (03/2027): the named
-- exception of D-19 (Q-25) that the T-108 subscriptions use. Each order copies its package's price (BR-56).
--
-- Idempotency. Literal version 7 keys and ON CONFLICT DO NOTHING on the primary key: a re-run changes nothing.
--
-- Reference: Humble, J., & Farley, D. (2010). Continuous Delivery, ch. 12. Addison-Wesley — sample data is kept in its
--   own set, applied per environment, and never reaches production.

INSERT INTO user_account (
    user_id, email, password_hash, role, account_status, email_verified_at,
    last_login_at, created_at, updated_at, version)
VALUES
    ('019b76da-a800-7d04-8000-000000000001', 'demo.free@cryptopilot.invalid', '${demo_password_hash}',
     'TRADER', 'ACTIVE', TIMESTAMPTZ '2026-01-01 00:00:00+00', NULL,
     TIMESTAMPTZ '2026-01-01 00:00:00+00', TIMESTAMPTZ '2026-01-01 00:00:00+00', 0),
    ('019b76da-a800-7d04-8000-000000000002', 'demo.pro@cryptopilot.invalid', '${demo_password_hash}',
     'TRADER', 'ACTIVE', TIMESTAMPTZ '2026-01-01 00:00:00+00', NULL,
     TIMESTAMPTZ '2026-01-01 00:00:00+00', TIMESTAMPTZ '2026-01-01 00:00:00+00', 0),
    ('019b76da-a800-7d04-8000-000000000003', 'demo.premium@cryptopilot.invalid', '${demo_password_hash}',
     'TRADER', 'ACTIVE', TIMESTAMPTZ '2026-01-01 00:00:00+00', NULL,
     TIMESTAMPTZ '2026-01-01 00:00:00+00', TIMESTAMPTZ '2026-01-01 00:00:00+00', 0)
ON CONFLICT (user_id) DO NOTHING;

-- The capital and the risk percentage are the defaults the plan form starts from (BR-30), so a plan entered during the
-- demonstration needs neither; the risk profile gives the BR-29 thresholds the warnings are measured against (BR-66).
INSERT INTO user_profile (
    user_id, display_name, avatar_url, default_capital, default_risk_percent, trading_style, risk_profile,
    notify_email, notify_push, created_at, updated_at, version)
VALUES
    ('019b76da-a800-7d04-8000-000000000001', 'Demo Free', NULL, 10000.00000000, 1.000, NULL, 'CONSERVATIVE',
     true, true, TIMESTAMPTZ '2026-01-01 00:00:00+00', TIMESTAMPTZ '2026-01-01 00:00:00+00', 0),
    ('019b76da-a800-7d04-8000-000000000002', 'Demo Pro', NULL, 10000.00000000, 1.000, 'DAY', 'BALANCED',
     true, true, TIMESTAMPTZ '2026-01-01 00:00:00+00', TIMESTAMPTZ '2026-01-01 00:00:00+00', 0),
    ('019b76da-a800-7d04-8000-000000000003', 'Demo Premium', NULL, 50000.00000000, 1.000, NULL, 'AGGRESSIVE',
     true, true, TIMESTAMPTZ '2026-01-01 00:00:00+00', TIMESTAMPTZ '2026-01-01 00:00:00+00', 0)
ON CONFLICT (user_id) DO NOTHING;

INSERT INTO subscription_order (
    order_id, user_id, package_id, order_code, amount, currency, payment_gateway, order_status,
    gateway_transaction_no, gateway_response_code, created_at, ipn_received_at, paid_at, expires_at,
    updated_at, version)
VALUES
    ('019b76da-a800-7d05-8000-000000000001', '019b76da-a800-7d04-8000-000000000002',
     '019b76da-a800-7002-8000-000000000003', 'DEMO-WF01-PRO-YEARLY', 990000, 'VND', 'VNPAY', 'PAID',
     NULL, NULL, TIMESTAMPTZ '2026-10-25 00:00:00+00', NULL, TIMESTAMPTZ '2026-10-25 00:00:00+00',
     TIMESTAMPTZ '2026-10-25 00:15:00+00', TIMESTAMPTZ '2026-10-25 00:00:00+00', 0),
    ('019b76da-a800-7d05-8000-000000000002', '019b76da-a800-7d04-8000-000000000003',
     '019b76da-a800-7002-8000-000000000005', 'DEMO-WF01-PREMIUM-YEARLY', 1990000, 'VND', 'VNPAY', 'PAID',
     NULL, NULL, TIMESTAMPTZ '2026-10-25 00:00:00+00', NULL, TIMESTAMPTZ '2026-10-25 00:00:00+00',
     TIMESTAMPTZ '2026-10-25 00:15:00+00', TIMESTAMPTZ '2026-10-25 00:00:00+00', 0)
ON CONFLICT (order_id) DO NOTHING;

INSERT INTO user_subscription (
    subscription_id, order_id, start_at, end_at, subscription_status, created_at, updated_at, version)
VALUES
    ('019b76da-a800-7d06-8000-000000000001', '019b76da-a800-7d05-8000-000000000001',
     TIMESTAMPTZ '2026-10-25 00:00:00+00', TIMESTAMPTZ '2027-10-25 00:00:00+00', 'ACTIVE',
     TIMESTAMPTZ '2026-10-25 00:00:00+00', TIMESTAMPTZ '2026-10-25 00:00:00+00', 0),
    ('019b76da-a800-7d06-8000-000000000002', '019b76da-a800-7d05-8000-000000000002',
     TIMESTAMPTZ '2026-10-25 00:00:00+00', TIMESTAMPTZ '2027-10-25 00:00:00+00', 'ACTIVE',
     TIMESTAMPTZ '2026-10-25 00:00:00+00', TIMESTAMPTZ '2026-10-25 00:00:00+00', 0)
ON CONFLICT (subscription_id) DO NOTHING;
