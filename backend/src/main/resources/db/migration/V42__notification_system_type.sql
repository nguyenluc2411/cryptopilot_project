-- T-078: NSF-10 tells the administrators when a news source has failed five crawls in a row. The logical model has no
-- kind of notification for a notice to administrators, so SYSTEM is added beside the kinds V29 left, the same way V29
-- added COMMENT. reference_type has no CHECK; its new value NEWS_SOURCE needs nothing here.

ALTER TABLE notification DROP CONSTRAINT ck_notification_type;
ALTER TABLE notification
    ADD CONSTRAINT ck_notification_type
        CHECK (notification_type IN ('ALERT', 'PLAN', 'MODERATION', 'SUBSCRIPTION', 'COMMENT', 'SYSTEM'));
