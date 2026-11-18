-- CoinPaprika gives the coins the names, logos and supplies Binance does not send (coin.coin_name, coin.logo_url,
-- both in V1) and the whole market's cap; alternative.me gives the Crypto Fear & Greed Index of the market list.
-- Their calls are watched like the other external services (UC-52), so the system log needs them as sources.
ALTER TABLE system_event DROP CONSTRAINT ck_system_event_source;
ALTER TABLE system_event ADD CONSTRAINT ck_system_event_source CHECK (source IN (
    'APPLICATION', 'SCHEDULED_JOB', 'BINANCE_REST', 'AI_SERVICE', 'MEDIA_STORAGE', 'NEWS_SOURCE', 'MAIL', 'COINPAPRIKA',
    'FEAR_GREED'));
