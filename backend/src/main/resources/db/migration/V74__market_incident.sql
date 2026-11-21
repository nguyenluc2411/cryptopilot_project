-- The incidents during which trading on a market was closed (D-159): its exchange data was lost, or an administrator
-- held it under maintenance. One row per incident, open while resumed_at is null; a relapse while recovering, or
-- maintenance started during an outage, stays the same incident.
--
-- Kept so that a restart during an incident starts closed rather than live, so that the replay and the funding run
-- reach back to the start of a long incident beyond their usual windows, and so that the administration console and
-- the Traders' summaries can name the period. A technical table like binance_ban and dropped_backfill_gap:
-- operational state of the application, not a business entity of the logical model.
CREATE TABLE market_incident (
    market_incident_id  uuid          NOT NULL,
    market_type         varchar(32)   NOT NULL,
    incident_kind       varchar(32)   NOT NULL,
    reason              varchar(32)   NOT NULL,
    last_live_at        timestamptz   NOT NULL,
    paused_at           timestamptz   NOT NULL,
    recovery_started_at timestamptz,
    resumed_at          timestamptz,
    message             varchar(500),
    expected_end_at     timestamptz,
    created_by          uuid,
    -- Bumped by every update: a write from a stale state changes no row (optimistic lock).
    version             int           NOT NULL DEFAULT 0,
    CONSTRAINT pk_market_incident PRIMARY KEY (market_incident_id),
    CONSTRAINT ck_market_incident_market_type CHECK (market_type IN ('SPOT', 'FUTURES')),
    CONSTRAINT ck_market_incident_kind CHECK (incident_kind IN ('OUTAGE', 'MAINTENANCE')),
    CONSTRAINT ck_market_incident_reason CHECK (reason IN (
        'STREAM_LOST', 'REST_UNAVAILABLE', 'RATE_LIMITED', 'IP_BANNED', 'MAINTENANCE')),
    CONSTRAINT ck_market_incident_paused CHECK (paused_at >= last_live_at),
    CONSTRAINT ck_market_incident_recovery CHECK (recovery_started_at IS NULL OR recovery_started_at >= paused_at),
    CONSTRAINT ck_market_incident_resumed CHECK (
        resumed_at IS NULL OR (recovery_started_at IS NOT NULL AND resumed_at >= recovery_started_at))
);

-- At most one open incident per market.
CREATE UNIQUE INDEX uq_market_incident_open ON market_incident (market_type) WHERE resumed_at IS NULL;
-- The console's list, newest first.
CREATE INDEX idx_market_incident_paused ON market_incident (market_type, paused_at DESC);

-- The Traders are told when trading on a market they have orders, positions or plans on is paused and reopened.
ALTER TABLE notification DROP CONSTRAINT ck_notification_type;
ALTER TABLE notification
    ADD CONSTRAINT ck_notification_type
        CHECK (notification_type IN ('ALERT', 'PLAN', 'MODERATION', 'SUBSCRIPTION', 'COMMENT', 'SYSTEM', 'FOLLOW',
                                     'MENTION', 'FUTURES', 'MARKET'));
