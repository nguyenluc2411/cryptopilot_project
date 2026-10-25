-- D-129: an article is read on its own page in the app (SCR-28), in the style of a news site. NSF-11 writes the
-- article again in its own words, shorter than the original (ai_body); the full original text is still never stored
-- (BR-48). image_url is the address of the outlet's cover image, from the feed or the page's og:image; the image
-- itself is never copied, it is shown from the outlet. Both are NULL until known, when the summary and a placeholder
-- are shown.

ALTER TABLE news_article ADD COLUMN ai_body text;
ALTER TABLE news_article ADD COLUMN image_url varchar(2048);
