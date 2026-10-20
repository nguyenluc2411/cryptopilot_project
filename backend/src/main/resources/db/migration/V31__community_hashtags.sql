-- D-99, CoinMarketCap Community: the hashtags a post's title and text carry, for the hashtag filter and the trending
-- topics. A hashtag is part of its post, so it goes with it.
CREATE TABLE post_hashtag (
    post_id uuid        NOT NULL,
    hashtag varchar(50) NOT NULL,
    CONSTRAINT pk_post_hashtag PRIMARY KEY (post_id, hashtag),
    CONSTRAINT fk_post_hashtag_post FOREIGN KEY (post_id) REFERENCES forum_post (post_id) ON DELETE CASCADE,
    -- Stored in lower case without the '#', so #BTC and #btc are one topic.
    CONSTRAINT ck_post_hashtag_lower CHECK (hashtag = lower(hashtag) AND left(hashtag, 1) <> chr(35))
);

CREATE INDEX idx_post_hashtag_hashtag ON post_hashtag (hashtag);
