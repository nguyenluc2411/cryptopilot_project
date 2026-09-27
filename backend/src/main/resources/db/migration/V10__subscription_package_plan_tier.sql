-- The plan tier of a package and the entitlements of that tier (T-106; SRS v1.1 BR-62, Table 3.1; D-58, D-59).
--
-- Every package belongs to one tier: FREE, PRO or PREMIUM, ranked 0, 1, 2 so an upgrade and a lower tier can be
-- told apart by comparing ranks (BR-55, BR-63). The entitlements are stored on the package, because an
-- administrator edits them per tier and saves them to every package of the tier (SRS 3.11.5); that all packages of
-- one tier agree is kept by that use case, not by the schema.
--
-- Two entitlements already had a column in V1 and keep it: ai_daily_quota is AI_CHAT_DAILY and can_post_video is
-- VIDEO_POST. The seven switches are booleans without a default, so a package cannot be created without saying
-- what its tier allows. The three maxima are nullable: null means unlimited, which PRO and PREMIUM use for ACTIVE
-- plans.
--
-- FREE is the plan of a Trader without an ACTIVE subscription, not a thing sold: exactly one FREE package exists,
-- priced 0, without a duration and not purchasable, and a paid tier always has a duration (BR-62, SRS 3.11.5).
-- A paid package is priced above 0: the gateway charges a real amount and the upgrade credit of BR-63 is computed
-- from it. Together with the FREE rule this also keeps every price from being negative.
-- The FREE package is never deactivated (SRS 3.11.5): the default plan must always exist.
-- The table is empty when this runs (the packages are seeded by T-007), so no existing row needs a tier.
--
-- The watchlist and active alert limits move from system_setting to the plan (BR-15, BR-17, BR-62); nothing reads
-- the two settings, and V3, which seeded them, is not edited.

ALTER TABLE subscription_package
    ADD COLUMN tier                    varchar(32) NOT NULL,
    ADD COLUMN tier_rank               smallint    NOT NULL,
    ADD COLUMN is_purchasable          boolean     NOT NULL,
    ADD COLUMN futures_analysis        boolean     NOT NULL,
    ADD COLUMN score_components        boolean     NOT NULL,
    ADD COLUMN active_plan_max         integer,
    ADD COLUMN watchlist_max           integer,
    ADD COLUMN active_alert_max        integer,
    ADD COLUMN indicator_alert         boolean     NOT NULL,
    ADD COLUMN external_alert_channels boolean     NOT NULL,
    ADD COLUMN advanced_performance    boolean     NOT NULL,
    ADD COLUMN news_ai_insight         boolean     NOT NULL,
    ADD COLUMN ai_performance_context  boolean     NOT NULL;

ALTER TABLE subscription_package ALTER COLUMN duration_days DROP NOT NULL;

ALTER TABLE subscription_package
    ADD CONSTRAINT ck_subscription_package_tier CHECK (tier IN ('FREE', 'PRO', 'PREMIUM'));

ALTER TABLE subscription_package
    ADD CONSTRAINT ck_subscription_package_tier_rank CHECK (
        (tier = 'FREE' AND tier_rank = 0) OR (tier = 'PRO' AND tier_rank = 1) OR (tier = 'PREMIUM' AND tier_rank = 2));

ALTER TABLE subscription_package
    ADD CONSTRAINT ck_subscription_package_free CHECK (
        (tier = 'FREE') = (price_amount = 0 AND duration_days IS NULL AND NOT is_purchasable));

ALTER TABLE subscription_package
    ADD CONSTRAINT ck_subscription_package_paid_duration CHECK (tier = 'FREE' OR duration_days IS NOT NULL);

ALTER TABLE subscription_package
    ADD CONSTRAINT ck_subscription_package_duration CHECK (duration_days IS NULL OR duration_days BETWEEN 1 AND 366);

ALTER TABLE subscription_package
    ADD CONSTRAINT ck_subscription_package_paid_price CHECK (tier = 'FREE' OR price_amount > 0);

ALTER TABLE subscription_package
    ADD CONSTRAINT ck_subscription_package_free_active CHECK (tier <> 'FREE' OR is_active);

ALTER TABLE subscription_package
    ADD CONSTRAINT ck_subscription_package_ai_daily_quota CHECK (ai_daily_quota >= 0);

ALTER TABLE subscription_package
    ADD CONSTRAINT ck_subscription_package_active_plan_max CHECK (active_plan_max IS NULL OR active_plan_max >= 0);

ALTER TABLE subscription_package
    ADD CONSTRAINT ck_subscription_package_watchlist_max CHECK (watchlist_max IS NULL OR watchlist_max >= 0);

ALTER TABLE subscription_package
    ADD CONSTRAINT ck_subscription_package_active_alert_max CHECK (active_alert_max IS NULL OR active_alert_max >= 0);

CREATE UNIQUE INDEX uq_subscription_package_one_free ON subscription_package (tier) WHERE tier = 'FREE';

DELETE FROM system_setting WHERE setting_key IN ('MAX_WATCHLIST_ITEMS', 'MAX_ACTIVE_ALERTS');
