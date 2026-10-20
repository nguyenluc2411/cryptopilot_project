-- D-99 (the registered topic): moderation may remove a reported post as well as hide it, and an official post says
-- whether it is an announcement, a platform update or educational content.
ALTER TABLE post_report DROP CONSTRAINT ck_post_report_resolution_action;
ALTER TABLE post_report ADD CONSTRAINT ck_post_report_resolution_action CHECK (
    resolution_action IS NULL
    OR resolution_action IN ('HIDE_POST', 'REMOVE_POST', 'DISMISS', 'WARN_USER', 'BAN_USER')
);

ALTER TABLE forum_post ADD COLUMN official_category varchar(32);
UPDATE forum_post SET official_category = 'ANNOUNCEMENT' WHERE is_official;
ALTER TABLE forum_post ADD CONSTRAINT ck_forum_post_official_category
    CHECK (official_category IS NULL OR official_category IN ('ANNOUNCEMENT', 'PLATFORM_UPDATE', 'EDUCATION'));
-- An official post has a category and a Trader's post has none.
ALTER TABLE forum_post ADD CONSTRAINT ck_forum_post_official_has_category
    CHECK (is_official = (official_category IS NOT NULL));
