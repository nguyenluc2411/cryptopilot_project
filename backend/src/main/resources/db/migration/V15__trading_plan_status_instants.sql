-- When a plan reached each terminal status (T-038; BR-32). The lifecycle is linear and each status is reached at
-- most once, so these instants together with created_at and activated_at give the status history SCR-18 shows,
-- without a history table. Each instant is set exactly when its status is, and activated_at is set for every status
-- that can only follow ACTIVE. V1 is not edited; the table has no rows yet, so the checks hold from the start.

ALTER TABLE trading_plan
    ADD COLUMN executed_at  timestamptz,
    ADD COLUMN cancelled_at timestamptz,
    ADD COLUMN expired_at   timestamptz;

ALTER TABLE trading_plan
    ADD CONSTRAINT ck_trading_plan_executed_at CHECK ((plan_status = 'EXECUTED') = (executed_at IS NOT NULL)),
    ADD CONSTRAINT ck_trading_plan_cancelled_at CHECK ((plan_status = 'CANCELLED') = (cancelled_at IS NOT NULL)),
    ADD CONSTRAINT ck_trading_plan_expired_at CHECK ((plan_status = 'EXPIRED') = (expired_at IS NOT NULL)),
    ADD CONSTRAINT ck_trading_plan_activated_at
        CHECK (plan_status NOT IN ('ACTIVE', 'EXECUTED', 'EXPIRED') OR activated_at IS NOT NULL);
