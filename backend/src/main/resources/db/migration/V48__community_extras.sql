-- D-99, CoinMarketCap Community: what one member chose about another. A Trader follows members and reads their posts
-- in the Following tab, or blocks a member, whose posts and comments they then no longer see and who can no longer
-- follow them, comment on their posts or reply to their comments. One row per direction: following and blocking the
-- same member exclude each other.
CREATE TABLE user_relation (
    relation_id    uuid        NOT NULL,
    source_user_id uuid        NOT NULL,
    target_user_id uuid        NOT NULL,
    relation_type  varchar(32) NOT NULL,
    created_at     timestamptz NOT NULL,
    updated_at     timestamptz NOT NULL,
    version        bigint      NOT NULL DEFAULT 0,
    CONSTRAINT pk_user_relation PRIMARY KEY (relation_id),
    CONSTRAINT uq_user_relation_source_target UNIQUE (source_user_id, target_user_id),
    CONSTRAINT fk_user_relation_source FOREIGN KEY (source_user_id) REFERENCES user_account (user_id),
    CONSTRAINT fk_user_relation_target FOREIGN KEY (target_user_id) REFERENCES user_account (user_id),
    CONSTRAINT ck_user_relation_type CHECK (relation_type IN ('FOLLOW', 'BLOCK')),
    CONSTRAINT ck_user_relation_not_self CHECK (source_user_id <> target_user_id)
);

CREATE INDEX idx_user_relation_target ON user_relation (target_user_id, relation_type, created_at DESC);
CREATE INDEX idx_user_relation_source ON user_relation (source_user_id, relation_type, created_at DESC);

-- A member is told about a new follower and about being mentioned (D-99), beside comments, replies and moderation.
ALTER TABLE notification DROP CONSTRAINT ck_notification_type;
ALTER TABLE notification
    ADD CONSTRAINT ck_notification_type
        CHECK (notification_type IN ('ALERT', 'PLAN', 'MODERATION', 'SUBSCRIPTION', 'COMMENT', 'SYSTEM', 'FOLLOW',
                                     'MENTION'));

-- A report is of a post, or of one comment of that post (D-99). The comment's text is kept as it was reported, so an
-- edit after the report does not change what the moderator reviews.
ALTER TABLE post_report ADD COLUMN comment_id uuid;
ALTER TABLE post_report ADD COLUMN reported_content text;
ALTER TABLE post_report ADD CONSTRAINT fk_post_report_comment
    FOREIGN KEY (comment_id) REFERENCES forum_comment (comment_id) ON DELETE CASCADE;
ALTER TABLE post_report ADD CONSTRAINT ck_post_report_comment_has_content
    CHECK ((comment_id IS NULL) = (reported_content IS NULL));
-- A user reports a post once, and each of its comments once. Two partial unique indexes, because a NULL comment never
-- collides in a plain unique constraint.
ALTER TABLE post_report DROP CONSTRAINT uq_post_report_post_reporter;
CREATE UNIQUE INDEX uq_post_report_post_reporter ON post_report (post_id, reporter_id) WHERE comment_id IS NULL;
CREATE UNIQUE INDEX uq_post_report_comment_reporter ON post_report (comment_id, reporter_id)
    WHERE comment_id IS NOT NULL;
CREATE INDEX idx_post_report_comment ON post_report (comment_id);
ALTER TABLE post_report DROP CONSTRAINT ck_post_report_resolution_action;
ALTER TABLE post_report ADD CONSTRAINT ck_post_report_resolution_action CHECK (
    resolution_action IS NULL
    OR resolution_action IN ('HIDE_POST', 'REMOVE_POST', 'HIDE_COMMENT', 'REMOVE_COMMENT', 'DISMISS', 'WARN_USER',
                             'BAN_USER')
);
-- A post's report hides or removes the post, a comment's report the comment.
ALTER TABLE post_report ADD CONSTRAINT ck_post_report_action_fits_target CHECK (
    resolution_action IS NULL
    OR (comment_id IS NULL AND resolution_action NOT IN ('HIDE_COMMENT', 'REMOVE_COMMENT'))
    OR (comment_id IS NOT NULL AND resolution_action NOT IN ('HIDE_POST', 'REMOVE_POST'))
);

-- A signed-in member other than the author opening a post counts once as a view of it (D-99).
ALTER TABLE engagement DROP CONSTRAINT ck_engagement_action_type;
-- A reader votes a post Bullish or Bearish, as on CoinMarketCap Community (D-99): one vote per member and post, which
-- a new vote replaces.
ALTER TABLE engagement ADD CONSTRAINT ck_engagement_action_type
    CHECK (action_type IN ('LIKE', 'SAVE', 'VIEW', 'BULLISH', 'BEARISH'));
ALTER TABLE engagement ADD CONSTRAINT ck_engagement_view_of_post
    CHECK (action_type NOT IN ('VIEW', 'BULLISH', 'BEARISH') OR post_id IS NOT NULL);
CREATE UNIQUE INDEX uq_engagement_post_vote ON engagement (user_id, post_id)
    WHERE action_type IN ('BULLISH', 'BEARISH');

-- The author marks a post Bullish or Bearish when writing it (D-99); most posts mark nothing.
ALTER TABLE forum_post ADD COLUMN sentiment varchar(16);
ALTER TABLE forum_post ADD CONSTRAINT ck_forum_post_sentiment
    CHECK (sentiment IS NULL OR sentiment IN ('BULLISH', 'BEARISH'));
