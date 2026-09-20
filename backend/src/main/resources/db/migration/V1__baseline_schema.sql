-- CryptoPilot baseline schema.
--
-- The 38 business tables of the logical model, plus the Spring Modulith event publication
-- registry. Everything TimescaleDB-specific (hypertables, compression, retention) is deliberately
-- left to its own migration, so that a database without the extension fails at one identifiable
-- step instead of half way through these definitions.
--
-- Conventions used throughout (SRS 3.1.5):
--   * snake_case, singular table names. USER is reserved in SQL, so the table is user_account.
--   * Primary keys are uuid version 7, assigned by the application before the insert. Time series
--     keep the composite natural key of the logical model, with the time column included.
--   * Every enumeration is varchar(32) with a CHECK listing its values, so a typo is rejected by
--     the database and not only by the mapping.
--   * Every instant is timestamptz. created_at and updated_at carry no DEFAULT: they are written
--     from the injected clock, which is what lets a test pin them.
--   * Numeric precision follows one table: price and quantity numeric(28,12); USDT amounts
--     numeric(28,8); VND amounts numeric(18,0); rates and ratios numeric(12,8); user-entered
--     percentages numeric(6,3); computed analytics numeric(28,10); scores numeric(5,2).
--   * Foreign keys cascade only from an aggregate root to rows that are part of it and cannot
--     exist without it. Everything else keeps the SQL default, so a delete that would orphan a row
--     is refused rather than silently widened. An account is never hard-deleted — it moves to
--     LOCKED or BANNED — so no foreign key to user_account cascades except the three rows that are
--     the account itself: its profile, its tokens and its devices. Trading history, community
--     content, orders and the audit trail therefore outlive any attempt to delete their author,
--     which is refused while they exist.
--   * Every table with a uuid primary key carries version, created_at and updated_at: the optimistic
--     locking counter and the audit instants that BaseEntity writes from the injected clock. They
--     are physical columns, not business attributes, so the logical model does not list them.
--   * Every foreign key column carries an index. PostgreSQL does not create one automatically, and
--     without it every parent delete degrades into a sequential scan of the child table.

-- =============================================================================================
-- Identity
-- =============================================================================================

CREATE TABLE user_account (
    user_id           uuid         NOT NULL,
    email             varchar(255) NOT NULL,
    password_hash     varchar(255) NOT NULL,
    role              varchar(32)  NOT NULL,
    account_status    varchar(32)  NOT NULL,
    email_verified_at timestamptz,
    last_login_at     timestamptz,
    created_at        timestamptz  NOT NULL,
    updated_at        timestamptz  NOT NULL,
    version           bigint       NOT NULL DEFAULT 0,
    CONSTRAINT pk_user_account PRIMARY KEY (user_id),
    CONSTRAINT uq_user_account_email UNIQUE (email),
    CONSTRAINT ck_user_account_role CHECK (role IN ('TRADER', 'ADMIN')),
    CONSTRAINT ck_user_account_status CHECK (account_status IN ('ACTIVE', 'LOCKED', 'BANNED'))
);

-- Registration compares addresses case-insensitively, so uniqueness has to hold case-insensitively
-- too; without this index "Trader@x.com" and "trader@x.com" would be two accounts.
CREATE UNIQUE INDEX uq_user_account_email_lower ON user_account (lower(email));

CREATE TABLE user_profile (
    user_id              uuid           NOT NULL,
    display_name         varchar(50)    NOT NULL,
    avatar_url           varchar(500),
    default_capital      numeric(28, 8),
    default_risk_percent numeric(6, 3),
    trading_style        varchar(32),
    notify_email         boolean        NOT NULL DEFAULT true,
    notify_push          boolean        NOT NULL DEFAULT true,
    created_at           timestamptz    NOT NULL,
    updated_at           timestamptz    NOT NULL,
    version              bigint         NOT NULL DEFAULT 0,
    CONSTRAINT pk_user_profile PRIMARY KEY (user_id),
    CONSTRAINT fk_user_profile_user FOREIGN KEY (user_id) REFERENCES user_account (user_id) ON DELETE CASCADE,
    CONSTRAINT ck_user_profile_trading_style
        CHECK (trading_style IS NULL OR trading_style IN ('SCALPING', 'DAY', 'SWING', 'POSITION'))
);

CREATE TABLE user_token (
    token_id   uuid        NOT NULL,
    user_id    uuid        NOT NULL,
    token_type varchar(32) NOT NULL,
    token_hash varchar(64) NOT NULL,
    expires_at timestamptz NOT NULL,
    used_at    timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version    bigint      NOT NULL DEFAULT 0,
    CONSTRAINT pk_user_token PRIMARY KEY (token_id),
    CONSTRAINT uq_user_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_user_token_user FOREIGN KEY (user_id) REFERENCES user_account (user_id) ON DELETE CASCADE,
    CONSTRAINT ck_user_token_type
        CHECK (token_type IN ('EMAIL_VERIFICATION', 'PASSWORD_RESET', 'REFRESH'))
);

CREATE INDEX idx_user_token_user_type ON user_token (user_id, token_type);
-- Retention deletes tokens that expired more than seven days ago.
CREATE INDEX idx_user_token_expires_at ON user_token (expires_at);

CREATE TABLE user_device (
    device_id    uuid         NOT NULL,
    user_id      uuid         NOT NULL,
    fcm_token    varchar(512) NOT NULL,
    platform     varchar(32)  NOT NULL,
    is_active    boolean      NOT NULL DEFAULT true,
    last_seen_at timestamptz,
    created_at   timestamptz  NOT NULL,
    updated_at   timestamptz  NOT NULL,
    version      bigint       NOT NULL DEFAULT 0,
    CONSTRAINT pk_user_device PRIMARY KEY (device_id),
    CONSTRAINT uq_user_device_fcm_token UNIQUE (fcm_token),
    CONSTRAINT fk_user_device_user FOREIGN KEY (user_id) REFERENCES user_account (user_id) ON DELETE CASCADE,
    CONSTRAINT ck_user_device_platform CHECK (platform IN ('ANDROID', 'IOS'))
);

CREATE INDEX idx_user_device_user ON user_device (user_id);

-- =============================================================================================
-- Market data
-- =============================================================================================

CREATE TABLE coin (
    coin_id    uuid         NOT NULL,
    symbol     varchar(32)  NOT NULL,
    coin_name  varchar(100) NOT NULL,
    logo_url   varchar(500),
    created_at timestamptz  NOT NULL,
    updated_at timestamptz  NOT NULL,
    version    bigint       NOT NULL DEFAULT 0,
    CONSTRAINT pk_coin PRIMARY KEY (coin_id),
    CONSTRAINT uq_coin_symbol UNIQUE (symbol)
);

CREATE TABLE crypto_pair (
    pair_id              uuid            NOT NULL,
    base_coin_id         uuid            NOT NULL,
    quote_coin_id        uuid            NOT NULL,
    symbol               varchar(32)     NOT NULL,
    is_spot_enabled      boolean         NOT NULL DEFAULT false,
    is_futures_enabled   boolean         NOT NULL DEFAULT false,
    spot_tick_size       numeric(28, 12),
    spot_step_size       numeric(28, 12),
    futures_tick_size    numeric(28, 12),
    futures_step_size    numeric(28, 12),
    spot_min_notional    numeric(28, 8),
    futures_min_notional numeric(28, 8),
    max_leverage         integer,
    pair_status          varchar(32)     NOT NULL,
    display_order        integer         NOT NULL DEFAULT 0,
    created_at           timestamptz     NOT NULL,
    updated_at           timestamptz     NOT NULL,
    version              bigint          NOT NULL DEFAULT 0,
    CONSTRAINT pk_crypto_pair PRIMARY KEY (pair_id),
    CONSTRAINT uq_crypto_pair_symbol UNIQUE (symbol),
    CONSTRAINT fk_crypto_pair_base_coin FOREIGN KEY (base_coin_id) REFERENCES coin (coin_id),
    CONSTRAINT fk_crypto_pair_quote_coin FOREIGN KEY (quote_coin_id) REFERENCES coin (coin_id),
    CONSTRAINT ck_crypto_pair_status CHECK (pair_status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_crypto_pair_distinct_coins CHECK (base_coin_id <> quote_coin_id),
    -- "null if no futures": a pair without a futures market has no maximum leverage.
    CONSTRAINT ck_crypto_pair_max_leverage CHECK (is_futures_enabled OR max_leverage IS NULL)
);

CREATE INDEX idx_crypto_pair_base_coin ON crypto_pair (base_coin_id);
CREATE INDEX idx_crypto_pair_quote_coin ON crypto_pair (quote_coin_id);

-- Candles. The primary key is also the access path for "the latest N candles of this pair": the
-- three leading columns are bound by equality and open_time is scanned backwards, which a btree
-- does at the same cost as forwards, so no second descending index is created on the table that
-- takes the most writes.
CREATE TABLE ohlcv (
    pair_id      uuid            NOT NULL,
    market_type  varchar(32)     NOT NULL,
    timeframe    varchar(32)     NOT NULL,
    open_time    timestamptz     NOT NULL,
    close_time   timestamptz     NOT NULL,
    open_price   numeric(28, 12) NOT NULL,
    high_price   numeric(28, 12) NOT NULL,
    low_price    numeric(28, 12) NOT NULL,
    close_price  numeric(28, 12) NOT NULL,
    base_volume  numeric(28, 12) NOT NULL,
    quote_volume numeric(28, 12) NOT NULL,
    trade_count  integer,
    CONSTRAINT pk_ohlcv PRIMARY KEY (pair_id, market_type, timeframe, open_time),
    CONSTRAINT fk_ohlcv_pair FOREIGN KEY (pair_id) REFERENCES crypto_pair (pair_id),
    CONSTRAINT ck_ohlcv_market_type CHECK (market_type IN ('SPOT', 'FUTURES')),
    CONSTRAINT ck_ohlcv_timeframe CHECK (timeframe IN ('15m', '1h', '4h', '1d'))
);

-- One indicator row per candle. There is deliberately no foreign key to ohlcv: both are hypertables
-- once T-006 runs, and a hypertable-to-hypertable foreign key is not supported in every TimescaleDB
-- version. Consistency holds because the same ingestion flow writes both rows.
CREATE TABLE technical_indicator (
    pair_id            uuid            NOT NULL,
    market_type        varchar(32)     NOT NULL,
    timeframe          varchar(32)     NOT NULL,
    open_time          timestamptz     NOT NULL,
    sma_20             numeric(28, 10),
    ema_20             numeric(28, 10),
    ema_50             numeric(28, 10),
    ema_200            numeric(28, 10),
    rsi_14             numeric(28, 10),
    macd_line          numeric(28, 10),
    macd_signal        numeric(28, 10),
    macd_histogram     numeric(28, 10),
    bb_upper           numeric(28, 10),
    bb_middle          numeric(28, 10),
    bb_lower           numeric(28, 10),
    volume_sma_20      numeric(28, 10),
    nearest_support    numeric(28, 10),
    nearest_resistance numeric(28, 10),
    trend_score        numeric(5, 2),
    momentum_score     numeric(5, 2),
    volume_score       numeric(5, 2),
    level_score        numeric(5, 2),
    derivatives_score  numeric(5, 2),
    setup_score        numeric(5, 2),
    score_version      varchar(32),
    calculated_at      timestamptz     NOT NULL,
    CONSTRAINT pk_technical_indicator PRIMARY KEY (pair_id, market_type, timeframe, open_time),
    CONSTRAINT fk_technical_indicator_pair FOREIGN KEY (pair_id) REFERENCES crypto_pair (pair_id),
    CONSTRAINT ck_technical_indicator_market_type CHECK (market_type IN ('SPOT', 'FUTURES')),
    CONSTRAINT ck_technical_indicator_timeframe CHECK (timeframe IN ('15m', '1h', '4h', '1d')),
    CONSTRAINT ck_technical_indicator_setup_score
        CHECK (setup_score IS NULL OR setup_score BETWEEN 0 AND 100),
    -- "null if SPOT": a spot market has no derivatives component in the setup score.
    CONSTRAINT ck_technical_indicator_derivatives_score
        CHECK (market_type <> 'SPOT' OR derivatives_score IS NULL)
);

CREATE TABLE spot_market_data (
    pair_id                  uuid            NOT NULL,
    snapshot_time            timestamptz     NOT NULL,
    last_price               numeric(28, 12),
    best_bid_price           numeric(28, 12),
    best_ask_price           numeric(28, 12),
    high_price_24h           numeric(28, 12),
    low_price_24h            numeric(28, 12),
    price_change_percent_24h numeric(28, 10),
    base_volume_24h          numeric(28, 12),
    quote_volume_24h         numeric(28, 12),
    CONSTRAINT pk_spot_market_data PRIMARY KEY (pair_id, snapshot_time),
    CONSTRAINT fk_spot_market_data_pair FOREIGN KEY (pair_id) REFERENCES crypto_pair (pair_id)
);

CREATE TABLE futures_market_data (
    pair_id             uuid            NOT NULL,
    snapshot_time       timestamptz     NOT NULL,
    snapshot_type       varchar(32)     NOT NULL,
    mark_price          numeric(28, 12),
    index_price         numeric(28, 12),
    funding_rate        numeric(12, 8),
    next_funding_time   timestamptz,
    open_interest       numeric(28, 12),
    open_interest_value numeric(28, 8),
    long_short_ratio    numeric(12, 8),
    long_account_ratio  numeric(12, 8),
    short_account_ratio numeric(12, 8),
    CONSTRAINT pk_futures_market_data PRIMARY KEY (pair_id, snapshot_time),
    CONSTRAINT fk_futures_market_data_pair FOREIGN KEY (pair_id) REFERENCES crypto_pair (pair_id),
    CONSTRAINT ck_futures_market_data_snapshot_type
        CHECK (snapshot_type IN ('PERIODIC', 'FUNDING_SETTLEMENT'))
);

CREATE TABLE leverage_bracket (
    pair_id                 uuid           NOT NULL,
    bracket_no              integer        NOT NULL,
    notional_floor          numeric(28, 8) NOT NULL,
    notional_cap            numeric(28, 8) NOT NULL,
    max_leverage            integer        NOT NULL,
    maintenance_margin_rate numeric(12, 8) NOT NULL,
    maintenance_amount      numeric(28, 8) NOT NULL,
    updated_by              uuid,
    updated_at              timestamptz    NOT NULL,
    CONSTRAINT pk_leverage_bracket PRIMARY KEY (pair_id, bracket_no),
    CONSTRAINT fk_leverage_bracket_pair FOREIGN KEY (pair_id) REFERENCES crypto_pair (pair_id) ON DELETE CASCADE,
    CONSTRAINT fk_leverage_bracket_updated_by FOREIGN KEY (updated_by) REFERENCES user_account (user_id)
);

CREATE INDEX idx_leverage_bracket_updated_by ON leverage_bracket (updated_by);

-- =============================================================================================
-- Watchlist, alerts and notifications
-- =============================================================================================

CREATE TABLE watchlist (
    watchlist_id uuid         NOT NULL,
    user_id      uuid         NOT NULL,
    pair_id      uuid         NOT NULL,
    label        varchar(100),
    sort_order   integer      NOT NULL DEFAULT 0,
    note         text,
    added_at     timestamptz  NOT NULL,
    created_at   timestamptz  NOT NULL,
    updated_at   timestamptz  NOT NULL,
    version      bigint       NOT NULL DEFAULT 0,
    CONSTRAINT pk_watchlist PRIMARY KEY (watchlist_id),
    CONSTRAINT uq_watchlist_user_pair UNIQUE (user_id, pair_id),
    CONSTRAINT fk_watchlist_user FOREIGN KEY (user_id) REFERENCES user_account (user_id),
    CONSTRAINT fk_watchlist_pair FOREIGN KEY (pair_id) REFERENCES crypto_pair (pair_id)
);

CREATE INDEX idx_watchlist_pair ON watchlist (pair_id);

-- Removing a watched pair removes its alerts with it, which is the cascade the rule asks for.
CREATE TABLE alert (
    alert_id           uuid            NOT NULL,
    user_id            uuid            NOT NULL,
    watchlist_id       uuid            NOT NULL,
    market_type        varchar(32)     NOT NULL,
    alert_type         varchar(32)     NOT NULL,
    indicator_name     varchar(32),
    timeframe          varchar(32),
    condition_operator varchar(32)     NOT NULL,
    threshold_value    numeric(28, 12) NOT NULL,
    trigger_mode       varchar(32)     NOT NULL,
    cooldown_minutes   integer,
    notify_in_app      boolean         NOT NULL DEFAULT true,
    notify_email       boolean         NOT NULL DEFAULT false,
    notify_push        boolean         NOT NULL DEFAULT false,
    alert_status       varchar(32)     NOT NULL,
    trigger_count      integer         NOT NULL DEFAULT 0,
    last_triggered_at  timestamptz,
    expires_at         timestamptz,
    created_at         timestamptz     NOT NULL,
    updated_at         timestamptz     NOT NULL,
    version            bigint          NOT NULL DEFAULT 0,
    CONSTRAINT pk_alert PRIMARY KEY (alert_id),
    CONSTRAINT fk_alert_user FOREIGN KEY (user_id) REFERENCES user_account (user_id),
    CONSTRAINT fk_alert_watchlist FOREIGN KEY (watchlist_id) REFERENCES watchlist (watchlist_id) ON DELETE CASCADE,
    CONSTRAINT ck_alert_market_type CHECK (market_type IN ('SPOT', 'FUTURES')),
    CONSTRAINT ck_alert_type CHECK (alert_type IN ('PRICE', 'INDICATOR')),
    CONSTRAINT ck_alert_condition_operator
        CHECK (condition_operator IN ('CROSS_ABOVE', 'CROSS_BELOW', 'GREATER_THAN', 'LESS_THAN')),
    CONSTRAINT ck_alert_trigger_mode CHECK (trigger_mode IN ('ONCE', 'ONCE_PER_BAR', 'EVERY_TIME')),
    CONSTRAINT ck_alert_status CHECK (alert_status IN ('ACTIVE', 'PAUSED', 'TRIGGERED', 'EXPIRED')),
    CONSTRAINT ck_alert_timeframe CHECK (timeframe IS NULL OR timeframe IN ('15m', '1h', '4h', '1d')),
    -- "null if PRICE": a price alert names no indicator and no timeframe; an indicator alert needs both.
    CONSTRAINT ck_alert_indicator_fields CHECK (
        (alert_type = 'PRICE' AND indicator_name IS NULL AND timeframe IS NULL)
        OR (alert_type = 'INDICATOR' AND indicator_name IS NOT NULL AND timeframe IS NOT NULL)
    )
);

CREATE INDEX idx_alert_watchlist ON alert (watchlist_id);
CREATE INDEX idx_alert_user ON alert (user_id);
CREATE INDEX idx_alert_active ON alert (alert_status) WHERE alert_status = 'ACTIVE';

CREATE TABLE notification (
    notification_id   uuid         NOT NULL,
    user_id           uuid         NOT NULL,
    alert_id          uuid,
    notification_type varchar(32)  NOT NULL,
    title             varchar(255) NOT NULL,
    content           text         NOT NULL,
    reference_type    varchar(64),
    reference_id      uuid,
    sent_via_push     boolean      NOT NULL DEFAULT false,
    sent_via_email    boolean      NOT NULL DEFAULT false,
    is_read           boolean      NOT NULL DEFAULT false,
    read_at           timestamptz,
    created_at        timestamptz  NOT NULL,
    updated_at        timestamptz  NOT NULL,
    version           bigint       NOT NULL DEFAULT 0,
    CONSTRAINT pk_notification PRIMARY KEY (notification_id),
    CONSTRAINT fk_notification_user FOREIGN KEY (user_id) REFERENCES user_account (user_id),
    -- A deleted alert must not take the notification history with it.
    CONSTRAINT fk_notification_alert FOREIGN KEY (alert_id) REFERENCES alert (alert_id) ON DELETE SET NULL,
    CONSTRAINT ck_notification_type
        CHECK (notification_type IN ('ALERT', 'PLAN', 'MODERATION', 'SUBSCRIPTION'))
);

CREATE INDEX idx_notification_user_unread ON notification (user_id, is_read, created_at DESC);
CREATE INDEX idx_notification_alert ON notification (alert_id);

-- =============================================================================================
-- Trading
-- =============================================================================================

CREATE TABLE trading_strategy (
    strategy_id   uuid         NOT NULL,
    strategy_code varchar(32)  NOT NULL,
    strategy_name varchar(100) NOT NULL,
    description   text,
    created_at    timestamptz  NOT NULL,
    updated_at    timestamptz  NOT NULL,
    version       bigint       NOT NULL DEFAULT 0,
    CONSTRAINT pk_trading_strategy PRIMARY KEY (strategy_id),
    CONSTRAINT uq_trading_strategy_code UNIQUE (strategy_code)
);

CREATE TABLE trading_plan (
    plan_id                      uuid            NOT NULL,
    user_id                      uuid            NOT NULL,
    pair_id                      uuid            NOT NULL,
    market_type                  varchar(32)     NOT NULL,
    direction                    varchar(32)     NOT NULL,
    entry_type                   varchar(32)     NOT NULL,
    entry_price                  numeric(28, 12),
    stop_loss_price              numeric(28, 12) NOT NULL,
    take_profit_price            numeric(28, 12) NOT NULL,
    capital_amount               numeric(28, 8)  NOT NULL,
    risk_percent                 numeric(6, 3)   NOT NULL,
    leverage                     integer,
    margin_mode                  varchar(32),
    position_quantity            numeric(28, 12),
    notional_value               numeric(28, 8),
    initial_margin               numeric(28, 8),
    maintenance_margin_rate_used numeric(12, 8),
    risk_amount                  numeric(28, 8),
    reward_amount                numeric(28, 8),
    risk_reward_ratio            numeric(12, 8),
    estimated_liquidation_price  numeric(28, 12),
    plan_status                  varchar(32)     NOT NULL,
    plan_note                    text,
    activated_at                 timestamptz,
    expires_at                   timestamptz,
    created_at                   timestamptz     NOT NULL,
    updated_at                   timestamptz     NOT NULL,
    version                      bigint          NOT NULL DEFAULT 0,
    CONSTRAINT pk_trading_plan PRIMARY KEY (plan_id),
    CONSTRAINT fk_trading_plan_user FOREIGN KEY (user_id) REFERENCES user_account (user_id),
    CONSTRAINT fk_trading_plan_pair FOREIGN KEY (pair_id) REFERENCES crypto_pair (pair_id),
    CONSTRAINT ck_trading_plan_market_type CHECK (market_type IN ('SPOT', 'FUTURES')),
    CONSTRAINT ck_trading_plan_direction CHECK (direction IN ('LONG', 'SHORT')),
    CONSTRAINT ck_trading_plan_entry_type CHECK (entry_type IN ('LIMIT', 'MARKET')),
    CONSTRAINT ck_trading_plan_margin_mode CHECK (margin_mode IS NULL OR margin_mode = 'ISOLATED'),
    CONSTRAINT ck_trading_plan_status
        CHECK (plan_status IN ('DRAFT', 'ACTIVE', 'EXECUTED', 'CANCELLED', 'EXPIRED')),
    -- "SPOT only LONG": a spot position cannot be short, has no leverage, no margin mode, no
    -- maintenance margin rate and no liquidation price.
    CONSTRAINT ck_trading_plan_spot_is_long CHECK (market_type <> 'SPOT' OR direction = 'LONG'),
    CONSTRAINT ck_trading_plan_spot_has_no_futures_fields CHECK (
        market_type <> 'SPOT'
        OR (leverage IS NULL
            AND margin_mode IS NULL
            AND maintenance_margin_rate_used IS NULL
            AND estimated_liquidation_price IS NULL)
    ),
    -- A limit order needs the price it waits at; a market order is filled at the price of the moment.
    CONSTRAINT ck_trading_plan_limit_has_entry_price
        CHECK (entry_type <> 'LIMIT' OR entry_price IS NOT NULL)
);

CREATE INDEX idx_trading_plan_user ON trading_plan (user_id);
CREATE INDEX idx_trading_plan_pair ON trading_plan (pair_id);
-- The matching engine rebuilds its book from the active plans of one pair.
CREATE INDEX idx_trading_plan_active ON trading_plan (pair_id, plan_status) WHERE plan_status = 'ACTIVE';

CREATE TABLE trading_plan_warning (
    warning_id      uuid         NOT NULL,
    plan_id         uuid         NOT NULL,
    warning_type    varchar(32)  NOT NULL,
    severity        varchar(32)  NOT NULL,
    warning_message varchar(500) NOT NULL,
    created_at      timestamptz  NOT NULL,
    updated_at      timestamptz  NOT NULL,
    version         bigint       NOT NULL DEFAULT 0,
    CONSTRAINT pk_trading_plan_warning PRIMARY KEY (warning_id),
    CONSTRAINT fk_trading_plan_warning_plan FOREIGN KEY (plan_id) REFERENCES trading_plan (plan_id) ON DELETE CASCADE,
    CONSTRAINT ck_trading_plan_warning_type CHECK (warning_type IN (
        'LOW_RR', 'HIGH_LEVERAGE', 'SL_BEYOND_LIQUIDATION', 'OVERSIZED_POSITION',
        'INSUFFICIENT_CAPITAL', 'HIGH_FUNDING_RATE', 'WIDE_STOP_LOSS')),
    CONSTRAINT ck_trading_plan_warning_severity CHECK (severity IN ('INFO', 'WARNING', 'BLOCKING'))
);

CREATE INDEX idx_trading_plan_warning_plan ON trading_plan_warning (plan_id);

CREATE TABLE trading_journal (
    journal_id         uuid            NOT NULL,
    user_id            uuid            NOT NULL,
    pair_id            uuid            NOT NULL,
    plan_id            uuid,
    strategy_id        uuid,
    source             varchar(32)     NOT NULL,
    market_type        varchar(32)     NOT NULL,
    direction          varchar(32)     NOT NULL,
    leverage           integer,
    entry_price        numeric(28, 12) NOT NULL,
    stop_loss_price    numeric(28, 12),
    take_profit_price  numeric(28, 12),
    exit_price         numeric(28, 12),
    quantity           numeric(28, 12) NOT NULL,
    entry_time         timestamptz     NOT NULL,
    exit_time          timestamptz,
    close_reason       varchar(32),
    fee_amount         numeric(28, 8),
    funding_fee_amount numeric(28, 8),
    realized_pnl       numeric(28, 8),
    trade_status       varchar(32)     NOT NULL,
    setup_description  text,
    lessons_learned    text,
    mistakes           text,
    chart_image_url    varchar(500),
    created_at         timestamptz     NOT NULL,
    updated_at         timestamptz     NOT NULL,
    version            bigint          NOT NULL DEFAULT 0,
    CONSTRAINT pk_trading_journal PRIMARY KEY (journal_id),
    -- One journal record per plan: a plan results in at most one trade.
    CONSTRAINT uq_trading_journal_plan UNIQUE (plan_id),
    CONSTRAINT fk_trading_journal_user FOREIGN KEY (user_id) REFERENCES user_account (user_id),
    CONSTRAINT fk_trading_journal_pair FOREIGN KEY (pair_id) REFERENCES crypto_pair (pair_id),
    CONSTRAINT fk_trading_journal_plan FOREIGN KEY (plan_id) REFERENCES trading_plan (plan_id),
    CONSTRAINT fk_trading_journal_strategy FOREIGN KEY (strategy_id) REFERENCES trading_strategy (strategy_id),
    CONSTRAINT ck_trading_journal_source CHECK (source IN ('SIMULATED', 'MANUAL')),
    CONSTRAINT ck_trading_journal_market_type CHECK (market_type IN ('SPOT', 'FUTURES')),
    CONSTRAINT ck_trading_journal_direction CHECK (direction IN ('LONG', 'SHORT')),
    CONSTRAINT ck_trading_journal_close_reason
        CHECK (close_reason IS NULL OR close_reason IN ('TAKE_PROFIT', 'STOP_LOSS', 'MANUAL', 'LIQUIDATION')),
    CONSTRAINT ck_trading_journal_trade_status CHECK (trade_status IN ('OPEN', 'CLOSED')),
    -- "SPOT only LONG": the same rule trading_plan carries. A journal record is reachable without a
    -- plan (source MANUAL), so the constraint has to sit on this table too, or a spot short could be
    -- recorded by hand and then counted in the performance statistics.
    CONSTRAINT ck_trading_journal_spot_is_long CHECK (market_type <> 'SPOT' OR direction = 'LONG'),
    CONSTRAINT ck_trading_journal_spot_has_no_futures_fields
        CHECK (market_type <> 'SPOT' OR leverage IS NULL),
    -- "null if MANUAL": a simulated trade always comes from a plan, a manual one never does.
    CONSTRAINT ck_trading_journal_plan_source CHECK (
        (source = 'SIMULATED' AND plan_id IS NOT NULL)
        OR (source = 'MANUAL' AND plan_id IS NULL)
    )
);

CREATE INDEX idx_trading_journal_user_status ON trading_journal (user_id, trade_status, exit_time DESC);
CREATE INDEX idx_trading_journal_open ON trading_journal (pair_id, trade_status) WHERE trade_status = 'OPEN';
CREATE INDEX idx_trading_journal_pair ON trading_journal (pair_id);
CREATE INDEX idx_trading_journal_strategy ON trading_journal (strategy_id);

-- =============================================================================================
-- AI assistant
-- =============================================================================================

CREATE TABLE ai_conversation (
    conversation_id    uuid         NOT NULL,
    user_id            uuid         NOT NULL,
    pair_id            uuid,
    conversation_title varchar(255),
    created_at         timestamptz  NOT NULL,
    updated_at         timestamptz  NOT NULL,
    version            bigint       NOT NULL DEFAULT 0,
    CONSTRAINT pk_ai_conversation PRIMARY KEY (conversation_id),
    CONSTRAINT fk_ai_conversation_user FOREIGN KEY (user_id) REFERENCES user_account (user_id),
    CONSTRAINT fk_ai_conversation_pair FOREIGN KEY (pair_id) REFERENCES crypto_pair (pair_id)
);

CREATE INDEX idx_ai_conversation_user ON ai_conversation (user_id, updated_at DESC);
CREATE INDEX idx_ai_conversation_pair ON ai_conversation (pair_id);

CREATE TABLE ai_message (
    message_id       uuid        NOT NULL,
    conversation_id  uuid        NOT NULL,
    sender_role      varchar(32) NOT NULL,
    content          text        NOT NULL,
    context_snapshot jsonb,
    input_tokens     integer,
    output_tokens    integer,
    message_status   varchar(32) NOT NULL,
    created_at       timestamptz NOT NULL,
    updated_at       timestamptz NOT NULL,
    version          bigint      NOT NULL DEFAULT 0,
    CONSTRAINT pk_ai_message PRIMARY KEY (message_id),
    CONSTRAINT fk_ai_message_conversation
        FOREIGN KEY (conversation_id) REFERENCES ai_conversation (conversation_id) ON DELETE CASCADE,
    CONSTRAINT ck_ai_message_sender_role CHECK (sender_role IN ('USER', 'ASSISTANT')),
    CONSTRAINT ck_ai_message_status CHECK (message_status IN ('PENDING', 'COMPLETED', 'FAILED'))
);

CREATE INDEX idx_ai_message_conversation ON ai_message (conversation_id, created_at);

CREATE TABLE ai_configuration (
    config_id         uuid          NOT NULL,
    updated_by        uuid,
    model_name        varchar(100)  NOT NULL,
    system_prompt     text          NOT NULL,
    temperature       numeric(3, 2) NOT NULL,
    max_output_tokens integer       NOT NULL,
    is_active         boolean       NOT NULL DEFAULT false,
    created_at        timestamptz   NOT NULL,
    updated_at        timestamptz   NOT NULL,
    version           bigint        NOT NULL DEFAULT 0,
    CONSTRAINT pk_ai_configuration PRIMARY KEY (config_id),
    CONSTRAINT fk_ai_configuration_updated_by FOREIGN KEY (updated_by) REFERENCES user_account (user_id)
);

CREATE INDEX idx_ai_configuration_updated_by ON ai_configuration (updated_by);
-- "only one active": a partial unique index lets any number of inactive rows coexist with exactly
-- one active one.
CREATE UNIQUE INDEX uq_ai_configuration_active ON ai_configuration (is_active) WHERE is_active;

-- =============================================================================================
-- Subscriptions and payment
-- =============================================================================================

CREATE TABLE subscription_package (
    package_id     uuid           NOT NULL,
    package_code   varchar(32)    NOT NULL,
    package_name   varchar(100)   NOT NULL,
    price_amount   numeric(18, 0) NOT NULL,
    currency       varchar(32)    NOT NULL,
    duration_days  integer        NOT NULL,
    ai_daily_quota integer        NOT NULL,
    can_post_video boolean        NOT NULL DEFAULT false,
    is_active      boolean        NOT NULL DEFAULT true,
    created_at     timestamptz    NOT NULL,
    updated_at     timestamptz    NOT NULL,
    version        bigint         NOT NULL DEFAULT 0,
    CONSTRAINT pk_subscription_package PRIMARY KEY (package_id),
    CONSTRAINT uq_subscription_package_code UNIQUE (package_code),
    CONSTRAINT ck_subscription_package_currency CHECK (currency = 'VND')
);

CREATE TABLE subscription_order (
    order_id               uuid           NOT NULL,
    user_id                uuid           NOT NULL,
    package_id             uuid           NOT NULL,
    order_code             varchar(64)    NOT NULL,
    amount                 numeric(18, 0) NOT NULL,
    currency               varchar(32)    NOT NULL,
    payment_gateway        varchar(32)    NOT NULL,
    order_status           varchar(32)    NOT NULL,
    gateway_transaction_no varchar(100),
    gateway_response_code  varchar(32),
    created_at             timestamptz    NOT NULL,
    ipn_received_at        timestamptz,
    paid_at                timestamptz,
    expires_at             timestamptz    NOT NULL,
    updated_at             timestamptz    NOT NULL,
    version                bigint         NOT NULL DEFAULT 0,
    CONSTRAINT pk_subscription_order PRIMARY KEY (order_id),
    CONSTRAINT uq_subscription_order_code UNIQUE (order_code),
    CONSTRAINT fk_subscription_order_user FOREIGN KEY (user_id) REFERENCES user_account (user_id),
    CONSTRAINT fk_subscription_order_package FOREIGN KEY (package_id) REFERENCES subscription_package (package_id),
    CONSTRAINT ck_subscription_order_currency CHECK (currency = 'VND'),
    CONSTRAINT ck_subscription_order_gateway CHECK (payment_gateway IN ('VNPAY', 'MOMO')),
    CONSTRAINT ck_subscription_order_status CHECK (order_status IN ('PENDING', 'PAID', 'FAILED', 'EXPIRED'))
);

CREATE INDEX idx_subscription_order_user ON subscription_order (user_id);
CREATE INDEX idx_subscription_order_package ON subscription_order (package_id);
-- Reconciliation walks the orders that are still pending, oldest first.
CREATE INDEX idx_subscription_order_status ON subscription_order (order_status, created_at);

CREATE TABLE user_subscription (
    subscription_id     uuid        NOT NULL,
    order_id            uuid        NOT NULL,
    start_at            timestamptz NOT NULL,
    end_at              timestamptz NOT NULL,
    subscription_status varchar(32) NOT NULL,
    created_at          timestamptz NOT NULL,
    updated_at          timestamptz NOT NULL,
    version             bigint      NOT NULL DEFAULT 0,
    CONSTRAINT pk_user_subscription PRIMARY KEY (subscription_id),
    -- One subscription per paid order, which is what makes a duplicate gateway callback harmless.
    CONSTRAINT uq_user_subscription_order UNIQUE (order_id),
    CONSTRAINT fk_user_subscription_order FOREIGN KEY (order_id) REFERENCES subscription_order (order_id),
    CONSTRAINT ck_user_subscription_status CHECK (subscription_status IN ('ACTIVE', 'EXPIRED', 'CANCELLED')),
    CONSTRAINT ck_user_subscription_period CHECK (end_at > start_at)
);

-- Entitlement checks and the expiry job both read status together with the end instant.
CREATE INDEX idx_user_subscription_status_end ON user_subscription (subscription_status, end_at);

-- =============================================================================================
-- Community
-- =============================================================================================

CREATE TABLE forum_post (
    post_id     uuid         NOT NULL,
    author_id   uuid         NOT NULL,
    post_type   varchar(32)  NOT NULL,
    title       varchar(255) NOT NULL,
    content     text         NOT NULL,
    market_type varchar(32),
    is_official boolean      NOT NULL DEFAULT false,
    post_status varchar(32)  NOT NULL,
    created_at  timestamptz  NOT NULL,
    updated_at  timestamptz  NOT NULL,
    version     bigint       NOT NULL DEFAULT 0,
    CONSTRAINT pk_forum_post PRIMARY KEY (post_id),
    CONSTRAINT fk_forum_post_author FOREIGN KEY (author_id) REFERENCES user_account (user_id),
    CONSTRAINT ck_forum_post_type CHECK (post_type IN ('TEXT', 'VIDEO')),
    CONSTRAINT ck_forum_post_market_type CHECK (market_type IS NULL OR market_type IN ('SPOT', 'FUTURES')),
    CONSTRAINT ck_forum_post_status CHECK (post_status IN ('PUBLISHED', 'HIDDEN', 'DELETED'))
);

CREATE INDEX idx_forum_post_author ON forum_post (author_id);
CREATE INDEX idx_forum_post_feed ON forum_post (post_status, created_at DESC);

CREATE TABLE post_coin (
    post_id uuid NOT NULL,
    coin_id uuid NOT NULL,
    CONSTRAINT pk_post_coin PRIMARY KEY (post_id, coin_id),
    CONSTRAINT fk_post_coin_post FOREIGN KEY (post_id) REFERENCES forum_post (post_id) ON DELETE CASCADE,
    CONSTRAINT fk_post_coin_coin FOREIGN KEY (coin_id) REFERENCES coin (coin_id)
);

CREATE INDEX idx_post_coin_coin ON post_coin (coin_id);

CREATE TABLE forum_comment (
    comment_id        uuid        NOT NULL,
    post_id           uuid        NOT NULL,
    author_id         uuid        NOT NULL,
    parent_comment_id uuid,
    content           text        NOT NULL,
    comment_status    varchar(32) NOT NULL,
    created_at        timestamptz NOT NULL,
    updated_at        timestamptz NOT NULL,
    version           bigint      NOT NULL DEFAULT 0,
    CONSTRAINT pk_forum_comment PRIMARY KEY (comment_id),
    CONSTRAINT fk_forum_comment_post FOREIGN KEY (post_id) REFERENCES forum_post (post_id) ON DELETE CASCADE,
    CONSTRAINT fk_forum_comment_author FOREIGN KEY (author_id) REFERENCES user_account (user_id),
    CONSTRAINT fk_forum_comment_parent
        FOREIGN KEY (parent_comment_id) REFERENCES forum_comment (comment_id) ON DELETE CASCADE,
    CONSTRAINT ck_forum_comment_status CHECK (comment_status IN ('PUBLISHED', 'HIDDEN', 'DELETED')),
    CONSTRAINT ck_forum_comment_not_its_own_parent CHECK (parent_comment_id <> comment_id)
);

CREATE INDEX idx_forum_comment_post ON forum_comment (post_id, created_at);
CREATE INDEX idx_forum_comment_author ON forum_comment (author_id);
CREATE INDEX idx_forum_comment_parent ON forum_comment (parent_comment_id);

CREATE TABLE engagement (
    engagement_id uuid        NOT NULL,
    user_id       uuid        NOT NULL,
    post_id       uuid,
    comment_id    uuid,
    action_type   varchar(32) NOT NULL,
    created_at    timestamptz NOT NULL,
    updated_at    timestamptz NOT NULL,
    version       bigint      NOT NULL DEFAULT 0,
    CONSTRAINT pk_engagement PRIMARY KEY (engagement_id),
    CONSTRAINT fk_engagement_user FOREIGN KEY (user_id) REFERENCES user_account (user_id),
    CONSTRAINT fk_engagement_post FOREIGN KEY (post_id) REFERENCES forum_post (post_id) ON DELETE CASCADE,
    CONSTRAINT fk_engagement_comment FOREIGN KEY (comment_id) REFERENCES forum_comment (comment_id) ON DELETE CASCADE,
    CONSTRAINT ck_engagement_action_type CHECK (action_type IN ('LIKE', 'SAVE')),
    -- "exactly one of post_id, comment_id": an engagement is on a post or on a comment, never both.
    CONSTRAINT ck_engagement_single_target CHECK ((post_id IS NULL) <> (comment_id IS NULL))
);

-- One like and one save per user per target. Two partial unique indexes rather than one constraint,
-- because a NULL column never collides in a plain unique index.
CREATE UNIQUE INDEX uq_engagement_user_post
    ON engagement (user_id, post_id, action_type) WHERE post_id IS NOT NULL;
CREATE UNIQUE INDEX uq_engagement_user_comment
    ON engagement (user_id, comment_id, action_type) WHERE comment_id IS NOT NULL;
CREATE INDEX idx_engagement_user ON engagement (user_id);
CREATE INDEX idx_engagement_post ON engagement (post_id);
CREATE INDEX idx_engagement_comment ON engagement (comment_id);

CREATE TABLE post_media (
    media_id          uuid          NOT NULL,
    post_id           uuid          NOT NULL,
    media_type        varchar(32)   NOT NULL,
    storage_public_id varchar(255)  NOT NULL,
    media_url         varchar(1000) NOT NULL,
    thumbnail_url     varchar(1000),
    file_size_bytes   bigint,
    duration_seconds  integer,
    width             integer,
    height            integer,
    processing_status varchar(32)   NOT NULL,
    sort_order        integer       NOT NULL DEFAULT 0,
    created_at        timestamptz   NOT NULL,
    updated_at        timestamptz   NOT NULL,
    version           bigint        NOT NULL DEFAULT 0,
    CONSTRAINT pk_post_media PRIMARY KEY (media_id),
    CONSTRAINT uq_post_media_storage_public_id UNIQUE (storage_public_id),
    CONSTRAINT fk_post_media_post FOREIGN KEY (post_id) REFERENCES forum_post (post_id) ON DELETE CASCADE,
    CONSTRAINT ck_post_media_type CHECK (media_type IN ('IMAGE', 'VIDEO')),
    CONSTRAINT ck_post_media_processing_status
        CHECK (processing_status IN ('UPLOADING', 'READY', 'FAILED'))
);

CREATE INDEX idx_post_media_post ON post_media (post_id, sort_order);

CREATE TABLE post_report (
    report_id         uuid        NOT NULL,
    post_id           uuid        NOT NULL,
    reporter_id       uuid        NOT NULL,
    resolved_by       uuid,
    reason            varchar(32) NOT NULL,
    description       text,
    report_status     varchar(32) NOT NULL,
    resolution_action varchar(32),
    resolved_at       timestamptz,
    created_at        timestamptz NOT NULL,
    updated_at        timestamptz NOT NULL,
    version           bigint      NOT NULL DEFAULT 0,
    CONSTRAINT pk_post_report PRIMARY KEY (report_id),
    -- A user reports a post once.
    CONSTRAINT uq_post_report_post_reporter UNIQUE (post_id, reporter_id),
    CONSTRAINT fk_post_report_post FOREIGN KEY (post_id) REFERENCES forum_post (post_id) ON DELETE CASCADE,
    CONSTRAINT fk_post_report_reporter FOREIGN KEY (reporter_id) REFERENCES user_account (user_id),
    CONSTRAINT fk_post_report_resolved_by FOREIGN KEY (resolved_by) REFERENCES user_account (user_id),
    CONSTRAINT ck_post_report_reason CHECK (reason IN ('SPAM', 'SCAM', 'ABUSE', 'MISLEADING', 'OTHER')),
    CONSTRAINT ck_post_report_status CHECK (report_status IN ('OPEN', 'REVIEWING', 'RESOLVED')),
    CONSTRAINT ck_post_report_resolution_action CHECK (
        resolution_action IS NULL
        OR resolution_action IN ('HIDE_POST', 'DISMISS', 'WARN_USER', 'BAN_USER')
    ),
    -- A resolved report names the moderator who resolved it, so a decision is always attributable.
    CONSTRAINT ck_post_report_resolved_has_resolver
        CHECK (report_status <> 'RESOLVED' OR resolved_by IS NOT NULL)
);

CREATE INDEX idx_post_report_post ON post_report (post_id);
CREATE INDEX idx_post_report_reporter ON post_report (reporter_id);
CREATE INDEX idx_post_report_resolved_by ON post_report (resolved_by);
CREATE INDEX idx_post_report_queue ON post_report (report_status, created_at);

-- =============================================================================================
-- News
-- =============================================================================================

CREATE TABLE news_source (
    source_id              uuid          NOT NULL,
    managed_by             uuid,
    source_name            varchar(100)  NOT NULL,
    feed_url               varchar(1000) NOT NULL,
    source_type            varchar(32)   NOT NULL,
    crawl_interval_minutes integer       NOT NULL,
    is_active              boolean       NOT NULL DEFAULT true,
    last_crawled_at        timestamptz,
    created_at             timestamptz   NOT NULL,
    updated_at             timestamptz   NOT NULL,
    version                bigint        NOT NULL DEFAULT 0,
    CONSTRAINT pk_news_source PRIMARY KEY (source_id),
    CONSTRAINT uq_news_source_feed_url UNIQUE (feed_url),
    CONSTRAINT fk_news_source_managed_by FOREIGN KEY (managed_by) REFERENCES user_account (user_id),
    CONSTRAINT ck_news_source_type CHECK (source_type IN ('RSS')),
    -- A source is polled at most every fifteen minutes.
    CONSTRAINT ck_news_source_crawl_interval CHECK (crawl_interval_minutes >= 15)
);

CREATE INDEX idx_news_source_managed_by ON news_source (managed_by);

CREATE TABLE news_crawl_log (
    crawl_log_id      uuid        NOT NULL,
    source_id         uuid        NOT NULL,
    started_at        timestamptz NOT NULL,
    finished_at       timestamptz,
    crawl_status      varchar(32) NOT NULL,
    articles_found    integer,
    articles_inserted integer,
    error_message     text,
    created_at        timestamptz NOT NULL,
    updated_at        timestamptz NOT NULL,
    version           bigint      NOT NULL DEFAULT 0,
    CONSTRAINT pk_news_crawl_log PRIMARY KEY (crawl_log_id),
    CONSTRAINT fk_news_crawl_log_source FOREIGN KEY (source_id) REFERENCES news_source (source_id) ON DELETE CASCADE,
    CONSTRAINT ck_news_crawl_log_status CHECK (crawl_status IN ('SUCCESS', 'PARTIAL', 'FAILED'))
);

CREATE INDEX idx_news_crawl_log_source ON news_crawl_log (source_id, started_at DESC);

CREATE TABLE news_article (
    article_id      uuid          NOT NULL,
    source_id       uuid          NOT NULL,
    title           varchar(500)  NOT NULL,
    original_url    varchar(2048) NOT NULL,
    url_hash        varchar(64)   NOT NULL,
    title_hash      varchar(64)   NOT NULL,
    excerpt         text,
    ai_summary      text,
    sentiment_label varchar(32),
    published_at    timestamptz   NOT NULL,
    collected_at    timestamptz   NOT NULL,
    created_at      timestamptz   NOT NULL,
    updated_at      timestamptz   NOT NULL,
    version         bigint        NOT NULL DEFAULT 0,
    CONSTRAINT pk_news_article PRIMARY KEY (article_id),
    -- The same article reached twice is skipped on the hash of its normalized URL.
    CONSTRAINT uq_news_article_url_hash UNIQUE (url_hash),
    CONSTRAINT fk_news_article_source FOREIGN KEY (source_id) REFERENCES news_source (source_id),
    CONSTRAINT ck_news_article_sentiment
        CHECK (sentiment_label IS NULL OR sentiment_label IN ('BULLISH', 'NEUTRAL', 'BEARISH'))
);

CREATE INDEX idx_news_article_source ON news_article (source_id);
-- The same story from another outlet is caught on the title hash within a time window.
CREATE INDEX idx_news_article_title_hash ON news_article (title_hash, published_at);
CREATE INDEX idx_news_article_feed ON news_article (published_at DESC);

CREATE TABLE news_tag (
    tag_id     uuid        NOT NULL,
    tag_name   varchar(64) NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version    bigint      NOT NULL DEFAULT 0,
    CONSTRAINT pk_news_tag PRIMARY KEY (tag_id),
    CONSTRAINT uq_news_tag_name UNIQUE (tag_name)
);

CREATE TABLE news_article_tag (
    article_id uuid NOT NULL,
    tag_id     uuid NOT NULL,
    CONSTRAINT pk_news_article_tag PRIMARY KEY (article_id, tag_id),
    CONSTRAINT fk_news_article_tag_article
        FOREIGN KEY (article_id) REFERENCES news_article (article_id) ON DELETE CASCADE,
    CONSTRAINT fk_news_article_tag_tag FOREIGN KEY (tag_id) REFERENCES news_tag (tag_id) ON DELETE CASCADE
);

CREATE INDEX idx_news_article_tag_tag ON news_article_tag (tag_id);

CREATE TABLE news_article_coin (
    article_id uuid    NOT NULL,
    coin_id    uuid    NOT NULL,
    is_primary boolean NOT NULL DEFAULT false,
    CONSTRAINT pk_news_article_coin PRIMARY KEY (article_id, coin_id),
    CONSTRAINT fk_news_article_coin_article
        FOREIGN KEY (article_id) REFERENCES news_article (article_id) ON DELETE CASCADE,
    CONSTRAINT fk_news_article_coin_coin FOREIGN KEY (coin_id) REFERENCES coin (coin_id)
);

CREATE INDEX idx_news_article_coin_coin ON news_article_coin (coin_id);

-- =============================================================================================
-- Administration
-- =============================================================================================

CREATE TABLE system_setting (
    setting_key   varchar(64)  NOT NULL,
    setting_value varchar(500) NOT NULL,
    value_type    varchar(32)  NOT NULL,
    description   varchar(500),
    updated_by    uuid,
    updated_at    timestamptz  NOT NULL,
    CONSTRAINT pk_system_setting PRIMARY KEY (setting_key),
    CONSTRAINT fk_system_setting_updated_by FOREIGN KEY (updated_by) REFERENCES user_account (user_id),
    CONSTRAINT ck_system_setting_value_type CHECK (value_type IN ('INT', 'DECIMAL', 'BOOLEAN', 'STRING'))
);

CREATE INDEX idx_system_setting_updated_by ON system_setting (updated_by);

CREATE TABLE audit_log (
    audit_id    uuid        NOT NULL,
    user_id     uuid,
    action_code varchar(64) NOT NULL,
    entity_type varchar(64),
    entity_id   uuid,
    old_value   jsonb,
    new_value   jsonb,
    ip_address  varchar(45),
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL,
    version     bigint      NOT NULL DEFAULT 0,
    CONSTRAINT pk_audit_log PRIMARY KEY (audit_id),
    -- No cascade: deleting a user must never erase the record of what was done.
    CONSTRAINT fk_audit_log_user FOREIGN KEY (user_id) REFERENCES user_account (user_id)
);

CREATE INDEX idx_audit_log_user ON audit_log (user_id, created_at DESC);
CREATE INDEX idx_audit_log_entity ON audit_log (entity_type, entity_id);
CREATE INDEX idx_audit_log_created_at ON audit_log (created_at DESC);

-- =============================================================================================
-- Technical tables (not business entities, not in the logical model)
-- =============================================================================================

-- The Spring Modulith event publication registry: the outbox that makes an event survive a restart.
-- The columns match the schema Spring Modulith 2.1 expects, so its own schema initializer stays
-- switched off and this migration remains the single source of the schema.
CREATE TABLE event_publication (
    id                     uuid        NOT NULL,
    listener_id            text        NOT NULL,
    event_type             text        NOT NULL,
    serialized_event       text        NOT NULL,
    publication_date       timestamptz NOT NULL,
    completion_date        timestamptz,
    status                 text,
    completion_attempts    integer,
    last_resubmission_date timestamptz,
    PRIMARY KEY (id)
);

CREATE INDEX event_publication_serialized_event_hash_idx ON event_publication USING hash (serialized_event);
CREATE INDEX event_publication_by_completion_date_idx ON event_publication (completion_date);
