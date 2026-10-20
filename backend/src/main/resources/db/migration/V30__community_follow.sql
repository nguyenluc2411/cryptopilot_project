-- D-99, CoinMarketCap Community: a Trader follows other members and reads their posts in a Following tab.
CREATE TABLE user_follow (
    follow_id   uuid        NOT NULL,
    follower_id uuid        NOT NULL,
    followee_id uuid        NOT NULL,
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL,
    version     bigint      NOT NULL DEFAULT 0,
    CONSTRAINT pk_user_follow PRIMARY KEY (follow_id),
    -- A member follows another once.
    CONSTRAINT uq_user_follow_follower_followee UNIQUE (follower_id, followee_id),
    CONSTRAINT fk_user_follow_follower FOREIGN KEY (follower_id) REFERENCES user_account (user_id),
    CONSTRAINT fk_user_follow_followee FOREIGN KEY (followee_id) REFERENCES user_account (user_id),
    CONSTRAINT ck_user_follow_not_self CHECK (follower_id <> followee_id)
);

CREATE INDEX idx_user_follow_followee ON user_follow (followee_id, created_at DESC);
CREATE INDEX idx_user_follow_follower ON user_follow (follower_id, created_at DESC);
