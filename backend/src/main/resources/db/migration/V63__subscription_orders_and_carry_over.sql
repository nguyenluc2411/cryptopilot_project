-- The order and subscription schema of the payment flow (T-066, D-58), as changed by D-146, which replaces
-- VNPay with SePay, adds a switch per gateway and drops the downgrade (BR-55 and BR-63 as amended):
--
--   * an order is NEW, RENEW (same tier) or UPGRADE (higher tier); a lower tier is refused when the order is created,
--     so there is no DOWNGRADE and no SCHEDULED subscription. A Trader holds at most one ACTIVE subscription, and the
--     database keeps it that way with a partial unique index.
--   * an upgrade charges the full list price; the unused value of the subscription it replaces is carried over and
--     paid back in days of the new tier. value_vnd is what a subscription is worth over its whole period (the amount
--     paid plus the value carried in), read from the order rather than from the package's current price (BR-56), so a
--     later change of price, or several upgrades in a row, still carry the right amount.
--   * the gateways are MOMO and SEPAY. VNPAY stays in the list as a legacy value only: rows written before this
--     change, the demonstration data among them, still name it, and no code creates a VNPay order any more.
--
-- V62 is the AI Assistant's migration.

-- ---------------------------------------------------------------------------------------------
-- subscription_order
-- ---------------------------------------------------------------------------------------------

ALTER TABLE subscription_order DROP CONSTRAINT ck_subscription_order_gateway;
ALTER TABLE subscription_order
    ADD CONSTRAINT ck_subscription_order_gateway CHECK (payment_gateway IN ('VNPAY', 'MOMO', 'SEPAY'));

-- Every order written before this migration bought a package without holding one, which is also what an order
-- inserted without a type is; the application always names it.
ALTER TABLE subscription_order ADD COLUMN order_type varchar(32) NOT NULL DEFAULT 'NEW';
ALTER TABLE subscription_order
    ADD CONSTRAINT ck_subscription_order_type CHECK (order_type IN ('NEW', 'RENEW', 'UPGRADE'));

-- There is no credit any more, so the amount is the package's list price when the order was created (BR-56): no
-- separate list_price column.
ALTER TABLE subscription_order
    ADD CONSTRAINT ck_subscription_order_amount CHECK (amount >= 0);

-- An order paid after the Trader's subscription changed under it (it would now be a downgrade) is kept PAID, leaves
-- the subscriptions alone and waits here for an administrator (SCR-40, UC-48).
ALTER TABLE subscription_order ADD COLUMN manual_handling boolean NOT NULL DEFAULT false;
ALTER TABLE subscription_order ADD COLUMN handled_at timestamptz;
ALTER TABLE subscription_order ADD COLUMN handled_by uuid;
ALTER TABLE subscription_order ADD COLUMN handling_note varchar(1000);
ALTER TABLE subscription_order
    ADD CONSTRAINT fk_subscription_order_handled_by FOREIGN KEY (handled_by) REFERENCES user_account (user_id);
ALTER TABLE subscription_order
    ADD CONSTRAINT ck_subscription_order_handled CHECK (
        (handled_at IS NULL AND handled_by IS NULL AND handling_note IS NULL)
        OR (manual_handling AND handled_at IS NOT NULL AND handled_by IS NOT NULL));

-- What the gateway answered (IPN or query), for the administrator to read on SCR-40.
ALTER TABLE subscription_order ADD COLUMN gateway_payload jsonb;

CREATE INDEX idx_subscription_order_handled_by ON subscription_order (handled_by);
-- SCR-40 lists the flagged orders still waiting for an administrator.
CREATE INDEX idx_subscription_order_manual ON subscription_order (created_at)
    WHERE manual_handling AND handled_at IS NULL;

-- ---------------------------------------------------------------------------------------------
-- user_subscription
-- ---------------------------------------------------------------------------------------------

-- The owner, copied from the order so that the one-ACTIVE rule can be an index on this table.
ALTER TABLE user_subscription ADD COLUMN user_id uuid;
UPDATE user_subscription s SET user_id = o.user_id FROM subscription_order o WHERE o.order_id = s.order_id;
ALTER TABLE user_subscription ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE user_subscription
    ADD CONSTRAINT fk_user_subscription_user FOREIGN KEY (user_id) REFERENCES user_account (user_id);
CREATE INDEX idx_user_subscription_user ON user_subscription (user_id);

-- The value of the whole period; a subscription written before this migration is worth what its order charged.
ALTER TABLE user_subscription ADD COLUMN value_vnd numeric(18, 0);
UPDATE user_subscription s SET value_vnd = o.amount FROM subscription_order o WHERE o.order_id = s.order_id;
ALTER TABLE user_subscription ALTER COLUMN value_vnd SET NOT NULL;

-- The unused value brought over from the subscription this one replaced, and the days it bought.
ALTER TABLE user_subscription ADD COLUMN carried_value_vnd numeric(18, 0) NOT NULL DEFAULT 0;
ALTER TABLE user_subscription ADD COLUMN bonus_days integer NOT NULL DEFAULT 0;
ALTER TABLE user_subscription ADD COLUMN carried_from_id uuid;
ALTER TABLE user_subscription
    ADD CONSTRAINT fk_user_subscription_carried_from
        FOREIGN KEY (carried_from_id) REFERENCES user_subscription (subscription_id);
-- A subscription is replaced at most once.
ALTER TABLE user_subscription
    ADD CONSTRAINT uq_user_subscription_carried_from UNIQUE (carried_from_id);
ALTER TABLE user_subscription
    ADD CONSTRAINT ck_user_subscription_value CHECK (
        value_vnd >= 0 AND carried_value_vnd >= 0 AND carried_value_vnd <= value_vnd AND bonus_days >= 0);

ALTER TABLE user_subscription DROP CONSTRAINT ck_user_subscription_status;
ALTER TABLE user_subscription
    ADD CONSTRAINT ck_user_subscription_status
        CHECK (subscription_status IN ('ACTIVE', 'EXPIRED', 'CANCELLED', 'REPLACED'));

-- Before the index: a Trader with two ACTIVE rows (written by hand, never by the application) keeps the one that runs
-- longest; the others are marked REPLACED, which leaves the plan the Trader holds unchanged (the highest tier was
-- read until now, and the row kept runs at least as long).
UPDATE user_subscription s
   SET subscription_status = 'REPLACED'
 WHERE s.subscription_status = 'ACTIVE'
   AND EXISTS (
        SELECT 1
          FROM user_subscription other
         WHERE other.user_id = s.user_id
           AND other.subscription_status = 'ACTIVE'
           AND (other.end_at > s.end_at
                OR (other.end_at = s.end_at AND other.subscription_id > s.subscription_id)));

CREATE UNIQUE INDEX uq_user_subscription_one_active ON user_subscription (user_id)
    WHERE subscription_status = 'ACTIVE';

-- The application writes the owner and the value of every subscription it creates. A row inserted without them
-- (sample data and test fixtures written as SQL) takes both from its order: the order's Trader, and what the order
-- charged, which is the value of a subscription that carried nothing over.
CREATE FUNCTION fill_user_subscription_from_order() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.user_id IS NULL OR NEW.value_vnd IS NULL THEN
        SELECT coalesce(NEW.user_id, o.user_id), coalesce(NEW.value_vnd, o.amount)
          INTO NEW.user_id, NEW.value_vnd
          FROM subscription_order o
         WHERE o.order_id = NEW.order_id;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_user_subscription_fill_from_order
    BEFORE INSERT ON user_subscription
    FOR EACH ROW EXECUTE FUNCTION fill_user_subscription_from_order();

-- ---------------------------------------------------------------------------------------------
-- The switch of each gateway, which an administrator turns off on SCR-43 when the gateway is down. An order already
-- created with a gateway that is then turned off is still settled by its IPN and by reconciliation.
-- ---------------------------------------------------------------------------------------------

INSERT INTO system_setting (setting_key, setting_value, value_type, description, updated_by, updated_at)
VALUES
    ('PAYMENT_MOMO_ENABLED', 'true', 'BOOLEAN',
     'Whether Traders may pay a new order with MoMo. Orders already created are still settled.',
     NULL, TIMESTAMPTZ '2026-01-01 00:00:00+00'),
    ('PAYMENT_SEPAY_ENABLED', 'true', 'BOOLEAN',
     'Whether Traders may pay a new order with SePay. Orders already created are still settled.',
     NULL, TIMESTAMPTZ '2026-01-01 00:00:00+00');
