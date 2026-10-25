-- Three news sources for a development database only (T-077; UC-49, NSF-10).
--
-- Like R__demo_dataset.sql this file lives in db/demo, which only the dev profile adds to the Flyway locations, so
-- staging and production never reach it: there the sources are what an administrator enters on SCR-41.
--
-- Each feed below was read, and the article pages of each were extracted with the selectors given here, by a trial
-- crawl before the module was built. One more outlet was tried and left out: its CDN refuses any client that names
-- itself a bot, and its pages carry boilerplate no selector removes reliably.
--
--   Cointelegraph Markets       one section feed
--   Bitcoin.com Market Updates  one section feed
--   CoinDesk Markets            the outlet's single feed, narrowed by category_filter to the markets desk
--
-- managed_by is NULL: no administrator saved them. Every seeded instant is the project epoch, and last_crawled_at is
-- NULL, so the first run of the crawl reads all three.
--
-- Idempotency. Literal version 7 keys and ON CONFLICT DO NOTHING on the primary key: a re-run changes nothing. A feed
-- an administrator already added by hand collides on uq_news_source_feed_url and is left to raise (D-19).

INSERT INTO news_source (
    source_id, managed_by, source_name, feed_url, source_type, crawl_interval_minutes, is_active, last_crawled_at,
    category_filter, body_selector, excluded_selectors, user_agent, created_at, updated_at, version)
VALUES
    ('019b76da-a800-7e0e-8000-000000000001', NULL, 'Cointelegraph Markets',
     'https://cointelegraph.com/rss/category/markets', 'RSS', 60, true, NULL,
     NULL, 'div.ct-prose', 'figure, aside, .related, blockquote, .ad, .social', NULL,
     TIMESTAMPTZ '2026-01-01 00:00:00+00', TIMESTAMPTZ '2026-01-01 00:00:00+00', 0),
    ('019b76da-a800-7e0e-8000-000000000002', NULL, 'Bitcoin.com Market Updates',
     'https://news.bitcoin.com/market-updates/feed/', 'RSS', 60, true, NULL,
     NULL, 'div.article__body', 'figure, aside, .not-prose, .related, .ad, table', NULL,
     TIMESTAMPTZ '2026-01-01 00:00:00+00', TIMESTAMPTZ '2026-01-01 00:00:00+00', 0),
    ('019b76da-a800-7e0e-8000-000000000003', NULL, 'CoinDesk Markets',
     'https://www.coindesk.com/arc/outboundfeeds/rss?outputType=xml', 'RSS', 60, true, NULL,
     'https://www.coindesk.com/markets', 'div.live-news-article, main#content',
     'figure, aside, .recommended, nav, .article-card', NULL,
     TIMESTAMPTZ '2026-01-01 00:00:00+00', TIMESTAMPTZ '2026-01-01 00:00:00+00', 0)
ON CONFLICT (source_id) DO NOTHING;
