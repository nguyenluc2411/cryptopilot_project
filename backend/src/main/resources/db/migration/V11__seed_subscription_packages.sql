-- The subscription packages every environment starts with (T-007; SRS v1.1 BR-62, Table 3.1; D-58, D-62).
--
-- Five packages across the three plan tiers: FREE, and a monthly and a yearly package of PRO and of PREMIUM. The
-- FREE package is the plan of every Trader without an ACTIVE subscription, so it is production data rather than
-- sample data: without it the entitlement checks have no plan to fall back to and refuse to guess one (D-62).
--
-- Entitlements are stored per package and are the same on every package of a tier; an administrator changes them
-- afterwards through the package screen, per tier. Null in a maximum means unlimited.
--
--   tier     code             price VND  days  futures components plans watch alerts indicator external perf news ai perf-ctx video
--   FREE     FREE                     0     -     no      no         3     5     3      no        no     no   no   0    no      no
--   PRO      PRO_MONTHLY         99,000    30    yes     yes        -    50    20     yes       yes    yes  yes  30    no      no
--   PRO      PRO_YEARLY         990,000   365    yes     yes        -    50    20     yes       yes    yes  yes  30    no      no
--   PREMIUM  PREMIUM_MONTHLY    199,000    30    yes     yes        -   100    50     yes       yes    yes  yes 100   yes     yes
--   PREMIUM  PREMIUM_YEARLY   1,990,000   365    yes     yes        -   100    50     yes       yes    yes  yes 100   yes     yes
--
-- Idempotency and time follow V3: literal version 7 keys, ON CONFLICT DO NOTHING on the primary key so a second run
-- changes nothing and never reverts a price or an entitlement an administrator has since edited, and the project
-- epoch instead of now().
--
-- Reference: Ambler, S. W., & Sadalage, P. J. (2006). Refactoring Databases: Evolutionary Database Design.
--   Addison-Wesley — reference data is shipped as a versioned migration beside the schema it depends on.

INSERT INTO subscription_package (
    package_id, package_code, package_name, price_amount, currency, duration_days,
    tier, tier_rank, is_purchasable, is_active,
    futures_analysis, score_components, active_plan_max, watchlist_max, active_alert_max,
    indicator_alert, external_alert_channels, advanced_performance, news_ai_insight,
    ai_daily_quota, ai_performance_context, can_post_video,
    created_at, updated_at, version)
VALUES
    ('019b76da-a800-7002-8000-000000000001', 'FREE', 'Free', 0, 'VND', NULL,
     'FREE', 0, false, true,
     false, false, 3, 5, 3,
     false, false, false, false,
     0, false, false,
     TIMESTAMPTZ '2026-01-01 00:00:00+00', TIMESTAMPTZ '2026-01-01 00:00:00+00', 0),
    ('019b76da-a800-7002-8000-000000000002', 'PRO_MONTHLY', 'Pro Monthly', 99000, 'VND', 30,
     'PRO', 1, true, true,
     true, true, NULL, 50, 20,
     true, true, true, true,
     30, false, false,
     TIMESTAMPTZ '2026-01-01 00:00:00+00', TIMESTAMPTZ '2026-01-01 00:00:00+00', 0),
    ('019b76da-a800-7002-8000-000000000003', 'PRO_YEARLY', 'Pro Yearly', 990000, 'VND', 365,
     'PRO', 1, true, true,
     true, true, NULL, 50, 20,
     true, true, true, true,
     30, false, false,
     TIMESTAMPTZ '2026-01-01 00:00:00+00', TIMESTAMPTZ '2026-01-01 00:00:00+00', 0),
    ('019b76da-a800-7002-8000-000000000004', 'PREMIUM_MONTHLY', 'Premium Monthly', 199000, 'VND', 30,
     'PREMIUM', 2, true, true,
     true, true, NULL, 100, 50,
     true, true, true, true,
     100, true, true,
     TIMESTAMPTZ '2026-01-01 00:00:00+00', TIMESTAMPTZ '2026-01-01 00:00:00+00', 0),
    ('019b76da-a800-7002-8000-000000000005', 'PREMIUM_YEARLY', 'Premium Yearly', 1990000, 'VND', 365,
     'PREMIUM', 2, true, true,
     true, true, NULL, 100, 50,
     true, true, true, true,
     100, true, true,
     TIMESTAMPTZ '2026-01-01 00:00:00+00', TIMESTAMPTZ '2026-01-01 00:00:00+00', 0)
ON CONFLICT (package_id) DO NOTHING;
