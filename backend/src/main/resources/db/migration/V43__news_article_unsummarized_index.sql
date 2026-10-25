-- T-079: NSF-11 asks every five minutes for the newest articles that have no summary yet. Once most articles are
-- summarized that is a small slice of a growing table, so a partial index holds exactly that slice, newest first:
-- the query reads the few rows it returns instead of filtering the whole feed index.

CREATE INDEX idx_news_article_unsummarized ON news_article (published_at DESC) WHERE ai_summary IS NULL;
