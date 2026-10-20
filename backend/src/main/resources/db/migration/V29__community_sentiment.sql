-- D-99, CoinMarketCap Community: a post says whether its author is bullish or bearish, and a Trader votes once a day
-- on how they feel about a coin.
ALTER TABLE forum_post ADD COLUMN sentiment varchar(32);
ALTER TABLE forum_post ADD CONSTRAINT ck_forum_post_sentiment
    CHECK (sentiment IS NULL OR sentiment IN ('BULLISH', 'BEARISH'));
CREATE INDEX idx_forum_post_sentiment ON forum_post (sentiment, created_at DESC) WHERE sentiment IS NOT NULL;

CREATE TABLE coin_sentiment_vote (
    vote_id    uuid        NOT NULL,
    user_id    uuid        NOT NULL,
    coin_id    uuid        NOT NULL,
    -- The day in UTC+7 the vote counts for (CR-01): one vote per Trader, coin and day.
    vote_date  date        NOT NULL,
    sentiment  varchar(32) NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version    bigint      NOT NULL DEFAULT 0,
    CONSTRAINT pk_coin_sentiment_vote PRIMARY KEY (vote_id),
    CONSTRAINT uq_coin_sentiment_vote_user_coin_day UNIQUE (user_id, coin_id, vote_date),
    CONSTRAINT fk_coin_sentiment_vote_user FOREIGN KEY (user_id) REFERENCES user_account (user_id),
    CONSTRAINT fk_coin_sentiment_vote_coin FOREIGN KEY (coin_id) REFERENCES coin (coin_id),
    CONSTRAINT ck_coin_sentiment_vote_sentiment CHECK (sentiment IN ('BULLISH', 'BEARISH'))
);

CREATE INDEX idx_coin_sentiment_vote_coin_day ON coin_sentiment_vote (coin_id, vote_date);
