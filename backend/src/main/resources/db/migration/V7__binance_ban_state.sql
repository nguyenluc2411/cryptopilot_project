-- Where a Binance IP ban is remembered across restarts (T-017).
--
-- A 418 means the exchange has banned this server's IP for continuing after 429s, and the ban grows
-- with every repeat, from two minutes to three days (Binance Spot API documentation, "LIMITS"). Held
-- only in memory, the ban would be forgotten by a restart, the first job after it would call again, and
-- a repeat offence is exactly what makes the next ban longer. So the instant the ban ends is written
-- here before the refusal is returned, and read back the first time the client is used after a start.
--
-- One row per market, keyed by it: the latest ban is the only one that matters. A technical table like
-- event_publication — operational state of the application, not a business entity of the logical model.
-- A 429 back-off is not stored: it lasts seconds and a restart outlives it anyway.

CREATE TABLE binance_ban (
    market_type  varchar(32)  NOT NULL,
    banned_until timestamptz  NOT NULL,
    reason       varchar(500) NOT NULL,
    updated_at   timestamptz  NOT NULL,
    CONSTRAINT pk_binance_ban PRIMARY KEY (market_type),
    CONSTRAINT ck_binance_ban_market_type CHECK (market_type IN ('SPOT', 'FUTURES'))
);
