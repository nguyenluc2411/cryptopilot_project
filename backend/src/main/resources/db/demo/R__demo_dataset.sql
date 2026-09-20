-- Sample data for a development database. Production never runs this file.
--
-- How production is kept away from it
--   This file is not in db/migration. It sits in its own migration location, db/demo, which only
--   the dev profile adds to spring.flyway.locations; the base configuration that staging and
--   production run lists db/migration alone. Separating the two by location rather than by a flag
--   inside one file is what makes the guarantee checkable: a production context cannot reach this
--   statement to skip it, because the file is not on its path at all. The integration tests assert
--   both halves — that the production locations do not include db/demo, and that no row this file
--   writes exists after the production migrations.
--
--   It is a repeatable migration (R__), not a versioned one. Versioned demo migrations would have
--   to interleave with the real version numbers, and a database that once ran one would carry it in
--   its schema history for ever; a repeatable migration runs after the versioned ones, re-runs when
--   it changes, and leaves the version sequence to the schema.
--
-- What is here, and what is not
--   A second account, a trader, so that development and manual testing are not done entirely as an
--   administrator: the two roles of BR-05 see different screens, and the difference is worth having
--   in front of whoever is building them.
--
--   Deliberately nothing else. The sample data a demo would really want — pairs to browse and
--   packages to buy — is exactly the data blocked on decisions the team has not taken, and a demo
--   file is not a way around that: a pair invented here would be tested against, demonstrated and
--   eventually believed. It is added here once the pair list and the package list are decided.
--
--   No candles, no snapshots, no funding history either. Those come from the exchange, and a
--   developer who wants them runs the ingestion.
--
-- Idempotency and time
--   The same rules the reference seed follows: literal version 7 keys, ON CONFLICT DO NOTHING on
--   the primary key, and the project epoch constant instead of now(). A repeatable migration is
--   re-applied whenever its checksum changes, so it has to be safe to run over its own result.
--
-- Reference: Humble, J., & Farley, D. (2010). Continuous Delivery, ch. 12. Addison-Wesley — sample
--   data is kept in its own set, applied per environment, and never reaches production.

-- The demo trader. Same placeholder treatment as the bootstrap administrator: the hash comes from
-- the environment (DEMO_PASSWORD_HASH) and falls back to a value no encoder can match, so even a
-- development account does not ship with a password that everyone can read. The .invalid address of
-- RFC 2606 marks the row as seeded and makes it unable to belong to a person.
INSERT INTO user_account (
    user_id, email, password_hash, role, account_status, email_verified_at,
    last_login_at, created_at, updated_at, version)
VALUES (
    '019b76da-a800-7d00-8000-000000000001',
    'demo.trader@cryptopilot.invalid',
    '${demo_password_hash}',
    'TRADER',
    'ACTIVE',
    TIMESTAMPTZ '2026-01-01 00:00:00+00',
    NULL,
    TIMESTAMPTZ '2026-01-01 00:00:00+00',
    TIMESTAMPTZ '2026-01-01 00:00:00+00',
    0)
ON CONFLICT (user_id) DO NOTHING;

-- The defaults on the profile are the ones the plan form starts from: a capital figure and a risk
-- percentage inside the range BR-30 allows, so that opening the form as this account shows a filled
-- example rather than an empty one.
INSERT INTO user_profile (
    user_id, display_name, avatar_url, default_capital, default_risk_percent, trading_style,
    notify_email, notify_push, created_at, updated_at, version)
VALUES (
    '019b76da-a800-7d00-8000-000000000001',
    'Demo Trader',
    NULL,
    1000.00000000,
    1.000,
    'SWING',
    true, true,
    TIMESTAMPTZ '2026-01-01 00:00:00+00',
    TIMESTAMPTZ '2026-01-01 00:00:00+00',
    0)
ON CONFLICT (user_id) DO NOTHING;
