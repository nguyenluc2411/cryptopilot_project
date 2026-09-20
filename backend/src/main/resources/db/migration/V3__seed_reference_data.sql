-- Reference and bootstrap data: the rows the system cannot run without.
--
-- What is seeded here
--   * system_setting — every limit, fee and threshold that an approved business rule either states
--     a default for or calls configurable (BR-15, BR-17, BR-29, BR-36, BR-44, BR-54). These are the
--     values the risk calculation, the alert limits, the media validation and the payment expiry
--     read at run time, so an empty table would leave each of them with no value at all.
--   * trading_strategy — the strategy catalogue a journal record is categorised by. Reference data
--     in the strict sense: a fixed, small, system-owned list that no user creates.
--   * user_account and user_profile — one bootstrap administrator. Every administrative screen
--     needs an ADMIN to exist before anybody can grant the role to anyone else (BR-05), so the
--     first one has to arrive with the schema.
--
-- What is deliberately NOT seeded, and why
--   * coin and crypto_pair. Which pairs are enabled at release is still an open decision, and the
--     tick and step sizes on those rows are what a position size is rounded to (BR-23, BR-30). A
--     made-up filter value here would become a wrong quantity in a trading plan later, and a
--     made-up pair list would silently answer a question the team has not answered. The daily
--     symbol synchronisation (NSF-01) is what fills the filters; the pair list itself waits for the
--     decision and arrives in its own migration.
--   * subscription_package. The number of packages, their prices, their durations and their daily
--     AI question quotas are one open decision (BR-50, BR-54, BR-55, BR-56). An order stores the
--     package price as it was at order time (BR-56), so a placeholder price would be copied into
--     orders and outlive the placeholder.
--   * ohlcv, technical_indicator, spot_market_data, futures_market_data, funding_rate_history and
--     leverage_bracket. All of it is collected from the exchange (NSF-01 … NSF-04). The first four
--     are hypertables carrying retention and compression policies; seeding them would write rows
--     into chunks that exist in order to be dropped.
--   * ai_configuration, news_source and news_tag. Each is configuration owned by a module that has
--     not been built: the model, the prompt and the feed list are decisions of those tasks.
--   * Everything a user creates — watchlists, alerts, plans, journals, posts, comments, orders,
--     notifications, audit entries. A production database starts with none of it.
--
-- Idempotency
--   Every statement below carries ON CONFLICT DO NOTHING on the primary key. The keys are literal,
--   so a second execution of this file collides with itself on the first column it inserts and
--   changes nothing, which is what the integration test re-executes and asserts. A collision on any
--   other unique key — the administrator's address, a strategy code — is deliberately left to
--   raise: it means a different row already occupies a business key this seed owns, and skipping
--   that silently would hide it. DO NOTHING rather than DO UPDATE for the mirror image of the same
--   reason: an administrator may have changed a setting through the settings screen, and re-running
--   the seed must not quietly revert an audited change (BR-57).
--
-- Fixed keys, fixed time
--   Primary keys are uuid version 7 assigned by the application at run time, which is exactly what a
--   seed cannot do: a generated key would differ between databases, so no test, no later migration
--   and no support query could name a seeded row. The literals below are therefore fixed and must
--   stay fixed. They are valid version 7 values whose timestamp is the project epoch constant and
--   whose low bits are a readable counter, so they sort where they belong without pretending to
--   have been generated. For the same reason no statement calls now(): every instant is that same
--   epoch constant, so two databases seeded a month apart hold identical rows.
--
-- References:
--   Ambler, S. W., & Sadalage, P. J. (2006). Refactoring Databases: Evolutionary Database Design.
--     Addison-Wesley — reference data belongs in versioned, repeatable migrations beside the schema
--     it depends on.
--   Humble, J., & Farley, D. (2010). Continuous Delivery, ch. 12. Addison-Wesley — the distinction
--     kept here between the data every environment needs and the sample data only a development
--     environment may hold.
--   Kimball, R., & Ross, M. (2013). The Data Warehouse Toolkit (3rd ed.). Wiley — reference data is
--     managed separately from transactional data and changes on its own, slower cycle.

-- =============================================================================================
-- System settings (BR-15, BR-17, BR-29, BR-36, BR-44, BR-54)
-- =============================================================================================

-- A key is seeded here when an approved business rule either states its default or calls the value
-- configurable. Nothing else is: a limit no rule says may be changed without a redeployment belongs
-- in the code that enforces it, and a row here that nothing reads is a value an administrator can
-- edit with no effect.
--
-- setting_value is text, so the scale of a decimal is a convention rather than something the column
-- enforces. Each one below is written at the scale of the column it will be compared against — the
-- rates at the eight decimals of numeric(12,8), the user-entered percentages at the three of
-- numeric(6,3) — so that reading a setting and comparing it is exact and needs no rescaling.
INSERT INTO system_setting (setting_key, setting_value, value_type, description, updated_by, updated_at)
VALUES
    ('MAX_WATCHLIST_ITEMS', '50', 'INT',
     'BR-15. Maximum number of pairs one trader may keep in the watchlist.',
     NULL, TIMESTAMPTZ '2026-01-01 00:00:00+00'),
    ('MAX_ACTIVE_ALERTS', '20', 'INT',
     'BR-17. Maximum number of alerts one trader may keep in status ACTIVE.',
     NULL, TIMESTAMPTZ '2026-01-01 00:00:00+00'),

    ('SPOT_MAKER_FEE', '0.00100000', 'DECIMAL',
     'BR-36. Simulated spot maker fee rate, charged on limit entries and take profit exits.',
     NULL, TIMESTAMPTZ '2026-01-01 00:00:00+00'),
    ('SPOT_TAKER_FEE', '0.00100000', 'DECIMAL',
     'BR-36. Simulated spot taker fee rate, charged on market entries, stop losses, manual closes and liquidations.',
     NULL, TIMESTAMPTZ '2026-01-01 00:00:00+00'),
    ('FUTURES_MAKER_FEE', '0.00020000', 'DECIMAL',
     'BR-36. Simulated futures maker fee rate.',
     NULL, TIMESTAMPTZ '2026-01-01 00:00:00+00'),
    ('FUTURES_TAKER_FEE', '0.00050000', 'DECIMAL',
     'BR-36. Simulated futures taker fee rate.',
     NULL, TIMESTAMPTZ '2026-01-01 00:00:00+00'),

    ('WARN_LOW_RR_RATIO', '1.50000000', 'DECIMAL',
     'BR-29. A plan whose reward to risk ratio is below this value raises LOW_RR at severity WARNING.',
     NULL, TIMESTAMPTZ '2026-01-01 00:00:00+00'),
    ('WARN_OVERSIZED_POSITION_RISK_PERCENT', '2.000', 'DECIMAL',
     'BR-29. A plan risking more than this percentage of capital raises OVERSIZED_POSITION at severity WARNING.',
     NULL, TIMESTAMPTZ '2026-01-01 00:00:00+00'),
    ('WARN_HIGH_LEVERAGE', '20', 'INT',
     'BR-29. A futures plan above this leverage raises HIGH_LEVERAGE at severity WARNING.',
     NULL, TIMESTAMPTZ '2026-01-01 00:00:00+00'),
    ('WARN_WIDE_STOP_LOSS_PERCENT', '10.000', 'DECIMAL',
     'BR-29. A stop loss further than this percentage from the entry price raises WIDE_STOP_LOSS at severity INFO.',
     NULL, TIMESTAMPTZ '2026-01-01 00:00:00+00'),
    ('WARN_HIGH_FUNDING_RATE', '0.00100000', 'DECIMAL',
     'BR-29. An absolute funding rate at or above this value raises HIGH_FUNDING_RATE at severity WARNING when the position would pay funding.',
     NULL, TIMESTAMPTZ '2026-01-01 00:00:00+00'),

    ('MAX_IMAGES_PER_POST', '4', 'INT',
     'BR-44. Maximum number of images one post may carry.',
     NULL, TIMESTAMPTZ '2026-01-01 00:00:00+00'),
    ('MAX_IMAGE_SIZE_MB', '5', 'INT',
     'BR-44. Maximum size of one uploaded image, in megabytes.',
     NULL, TIMESTAMPTZ '2026-01-01 00:00:00+00'),
    ('MAX_VIDEO_SIZE_MB', '100', 'INT',
     'BR-44. Maximum size of one uploaded video, in megabytes.',
     NULL, TIMESTAMPTZ '2026-01-01 00:00:00+00'),
    ('MAX_VIDEO_DURATION_SECONDS', '300', 'INT',
     'BR-44. Maximum duration of one uploaded video, in seconds.',
     NULL, TIMESTAMPTZ '2026-01-01 00:00:00+00'),

    ('ORDER_EXPIRY_MINUTES', '15', 'INT',
     'BR-54. A subscription order not paid within this many minutes becomes EXPIRED.',
     NULL, TIMESTAMPTZ '2026-01-01 00:00:00+00')
ON CONFLICT (setting_key) DO NOTHING;

-- =============================================================================================
-- Trading strategies
-- =============================================================================================

-- The catalogue a journal record is categorised by and that the performance breakdown groups on.
-- The three codes are the ones the data model names. A journal record may also carry no strategy at
-- all, which is why this list is short rather than an attempt at an exhaustive taxonomy.
INSERT INTO trading_strategy (
    strategy_id, strategy_code, strategy_name, description, created_at, updated_at, version)
VALUES
    ('019b76da-a800-7001-8000-000000000001', 'BREAKOUT', 'Breakout',
     'Entry as price leaves a range through a support or resistance level.',
     TIMESTAMPTZ '2026-01-01 00:00:00+00', TIMESTAMPTZ '2026-01-01 00:00:00+00', 0),
    ('019b76da-a800-7001-8000-000000000002', 'SWING', 'Swing',
     'Entry at a swing point, held for several days within the prevailing trend.',
     TIMESTAMPTZ '2026-01-01 00:00:00+00', TIMESTAMPTZ '2026-01-01 00:00:00+00', 0),
    ('019b76da-a800-7001-8000-000000000003', 'SCALPING', 'Scalping',
     'Entry and exit inside one session, on the shortest analysis timeframes.',
     TIMESTAMPTZ '2026-01-01 00:00:00+00', TIMESTAMPTZ '2026-01-01 00:00:00+00', 0)
ON CONFLICT (strategy_id) DO NOTHING;

-- =============================================================================================
-- Bootstrap administrator (BR-05)
-- =============================================================================================

-- One account with role ADMIN, because only an Admin can grant the ADMIN role and a self-registered
-- account is always a TRADER (BR-05): without this row the role could never be reached at all.
--
-- No credential is committed. The password hash arrives as a migration placeholder whose value the
-- environment supplies (ADMIN_PASSWORD_HASH), already hashed — the database never sees a password
-- and this file never contains one. When the environment supplies nothing the placeholder falls
-- back to a sentinel that is not a hash in any format an encoder produces, so no password matches
-- it and the account cannot be signed in to until somebody sets one deliberately. That is the safe
-- direction to fail in: an account nobody can use, rather than an account whose password is known
-- to everyone who has read this file.
--
-- The address sits in the .invalid top-level domain, which RFC 2606 reserves precisely so that it
-- can never resolve. It cannot receive the verification mail and cannot belong to a person, which
-- is what makes the row unmistakably a seeded one; email_verified_at is set for the same reason,
-- since the verification BR-01 asks for could otherwise never complete for this account.
INSERT INTO user_account (
    user_id, email, password_hash, role, account_status, email_verified_at,
    last_login_at, created_at, updated_at, version)
VALUES (
    '019b76da-a800-7000-8000-000000000001',
    'admin@cryptopilot.invalid',
    '${admin_password_hash}',
    'ADMIN',
    'ACTIVE',
    TIMESTAMPTZ '2026-01-01 00:00:00+00',
    NULL,
    TIMESTAMPTZ '2026-01-01 00:00:00+00',
    TIMESTAMPTZ '2026-01-01 00:00:00+00',
    0)
ON CONFLICT (user_id) DO NOTHING;

-- The profile is part of the account rather than something a user creates separately: registration
-- writes one with every account. The seeded account gets one too, so that a screen reading the
-- profile of the signed-in administrator finds a row instead of nothing.
INSERT INTO user_profile (
    user_id, display_name, avatar_url, default_capital, default_risk_percent, trading_style,
    notify_email, notify_push, created_at, updated_at, version)
VALUES (
    '019b76da-a800-7000-8000-000000000001',
    'CryptoPilot Admin',
    NULL, NULL, NULL, NULL,
    true, true,
    TIMESTAMPTZ '2026-01-01 00:00:00+00',
    TIMESTAMPTZ '2026-01-01 00:00:00+00',
    0)
ON CONFLICT (user_id) DO NOTHING;
