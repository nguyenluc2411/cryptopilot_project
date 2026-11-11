-- The system log of UC-46 (registration form: "View system logs, including important user actions, system errors,
-- and external API failures"). audit_log keeps what people did (BR-57); this table keeps what went wrong on its own:
-- an unexpected error answered with 500, a scheduled job that threw, and a call to an external service that failed
-- (Binance REST, the AI Service, Cloudinary, an RSS source, SMTP).
--
-- A failure that repeats is one row, not thousands: the same fingerprint (category, source, operation, error type)
-- seen again within ten minutes of its row's first_seen_at adds to occurrences and moves last_seen_at; buckets are
-- fixed, so an outage of an hour is six rows. A technical table like binance_ban — operational state of the
-- application, not a business entity of the logical model — written outside the failing command's transaction, and
-- deleted after 90 days.
CREATE TABLE system_event (
    system_event_id uuid          NOT NULL,
    category        varchar(32)   NOT NULL,
    source          varchar(32)   NOT NULL,
    operation       varchar(200)  NOT NULL,
    error_type      varchar(200)  NOT NULL,
    message         varchar(1000),
    reference       varchar(64),
    fingerprint     varchar(64)   NOT NULL,
    occurrences     integer       NOT NULL DEFAULT 1,
    first_seen_at   timestamptz   NOT NULL,
    last_seen_at    timestamptz   NOT NULL,
    CONSTRAINT pk_system_event PRIMARY KEY (system_event_id),
    CONSTRAINT ck_system_event_category CHECK (category IN ('SYSTEM_ERROR', 'EXTERNAL_API_FAILURE')),
    CONSTRAINT ck_system_event_source CHECK (source IN (
        'APPLICATION', 'SCHEDULED_JOB', 'BINANCE_REST', 'AI_SERVICE', 'MEDIA_STORAGE', 'NEWS_SOURCE', 'MAIL')),
    CONSTRAINT ck_system_event_occurrences CHECK (occurrences >= 1),
    CONSTRAINT ck_system_event_seen CHECK (last_seen_at >= first_seen_at)
);

-- The list, newest first, and the health panel's latest failure of each source.
CREATE INDEX idx_system_event_last_seen ON system_event (last_seen_at DESC);
CREATE INDEX idx_system_event_source ON system_event (source, last_seen_at DESC);
-- The ten-minute merge looks a fingerprint up by its latest bucket.
CREATE INDEX idx_system_event_fingerprint ON system_event (fingerprint, first_seen_at DESC);

-- SCR-44 filters the audit trail by action and by period; V1 has indexes by user, by entity and by time only.
CREATE INDEX idx_audit_log_action ON audit_log (action_code, created_at DESC);
