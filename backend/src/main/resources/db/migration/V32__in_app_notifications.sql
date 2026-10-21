-- E-1, SRS 3.4.5 (UC-15): the in-app notification centre. The community tells a post's author about new comments
-- (SRS 3.7.3) and a member about a new follower (D-99), beside the moderation decisions (SRS 3.11.3).
ALTER TABLE notification DROP CONSTRAINT ck_notification_type;
ALTER TABLE notification
    ADD CONSTRAINT ck_notification_type
        CHECK (notification_type IN ('ALERT', 'PLAN', 'MODERATION', 'SUBSCRIPTION', 'COMMENT', 'FOLLOW'));

-- What the notification was written for, such as one comment for one recipient. Events are delivered at least once
-- (NSF-15), so a second delivery finds the key taken and writes nothing.
ALTER TABLE notification ADD COLUMN source_key varchar(128);
ALTER TABLE notification ADD CONSTRAINT uq_notification_source_key UNIQUE (source_key);

-- The centre lists all of a Trader's notifications newest first, read or not.
CREATE INDEX idx_notification_user_created ON notification (user_id, created_at DESC);
-- NSF-17 deletes notifications older than 90 days.
CREATE INDEX idx_notification_created ON notification (created_at);
