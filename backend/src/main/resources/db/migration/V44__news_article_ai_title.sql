-- D-127: NSF-11 translates the headline together with the summary, so the feed reads in one language. The original
-- title stays as collected (BR-48, the 48 h title-hash check reads it); NULL until the article is summarized, when the
-- original is shown.

ALTER TABLE news_article ADD COLUMN ai_title varchar(500);
