-- TR-43: a Trader is told about a margin call and a liquidation of their paper Futures positions (plan 4.10, 4.12).
-- reference_type has no CHECK; its new value FUTURES_POSITION needs nothing here.
ALTER TABLE notification DROP CONSTRAINT ck_notification_type;
ALTER TABLE notification
    ADD CONSTRAINT ck_notification_type
        CHECK (notification_type IN ('ALERT', 'PLAN', 'MODERATION', 'SUBSCRIPTION', 'COMMENT', 'SYSTEM', 'FOLLOW',
                                     'MENTION', 'FUTURES'));
