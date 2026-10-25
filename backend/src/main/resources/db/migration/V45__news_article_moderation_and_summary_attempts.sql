-- T-080b: an administrator can hide an article from the feed and restore it (audited, BR-57), and NSF-11 keeps count
-- of its attempts at an article, so one it cannot summarize is not asked for every five minutes for a week: after the
-- configured number of attempts it waits for an administrator to ask again (D-128). The last reason is kept for SCR-41.

ALTER TABLE news_article
    ADD COLUMN hidden_at            timestamptz,
    ADD COLUMN hidden_by            uuid,
    ADD COLUMN summary_attempts     integer      NOT NULL DEFAULT 0,
    ADD COLUMN summary_error        varchar(500),
    ADD COLUMN summary_attempted_at timestamptz,
    ADD CONSTRAINT fk_news_article_hidden_by FOREIGN KEY (hidden_by) REFERENCES user_account (user_id),
    ADD CONSTRAINT ck_news_article_summary_attempts CHECK (summary_attempts >= 0),
    -- Hidden by someone exactly when hidden.
    ADD CONSTRAINT ck_news_article_hidden CHECK ((hidden_at IS NULL) = (hidden_by IS NULL));

CREATE INDEX idx_news_article_hidden_by ON news_article (hidden_by);
