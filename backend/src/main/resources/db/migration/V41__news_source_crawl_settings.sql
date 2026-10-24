-- T-077: the settings an administrator keeps on a news source so the crawl of NSF-10 can read each outlet well
-- (UC-49, SCR-41). V1 created the table with what every source has; these four columns are what differs per outlet,
-- found by running the crawl against real feeds before the module was built:
--
--   category_filter     a feed that mixes sections (one outlet's feed carries every desk) is narrowed to the items
--                       whose <category> carries one of these values, comma separated; NULL keeps every item.
--   body_selector       the CSS selectors of the article body on the outlet's pages, comma separated, the first that
--                       matches wins; NULL means the crawl keeps to what the feed itself carries.
--   excluded_selectors  CSS selectors removed from the body before it is read (related links, widgets, FAQ blocks).
--   user_agent          the User-Agent sent to this outlet; NULL sends the configured default. One outlet's CDN
--                       refuses any agent that names itself a bot.
--
-- All four are optional, so the rows already stored stay valid and no default is invented for them. The selectors are
-- checked when an administrator saves them, not here: a CHECK cannot parse CSS.

ALTER TABLE news_source
    ADD COLUMN category_filter    varchar(256),
    ADD COLUMN body_selector      varchar(512),
    ADD COLUMN excluded_selectors varchar(1024),
    ADD COLUMN user_agent         varchar(256);
