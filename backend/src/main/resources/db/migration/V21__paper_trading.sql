-- Paper trading: the simulated exchange of the trading screens (TR-02; Q-T1 to Q-T10).
--
-- A Trader practises on Binance Spot and USDⓈ-M Futures with virtual funds, priced from the public market data the
-- market module already streams. Nothing here reaches an exchange account or holds real money. The tables are
-- independent of trading_plan (Q-T1): an order is placed directly, never by a plan, and no column points at one.
--
-- Shape of the module, one table per concept:
--
--   paper_account          one per Trader: position mode (Q-T4)
--   paper_balance          free and locked amount of one coin in the SPOT or FUTURES wallet
--   paper_transfer         a move of one coin between the two wallets
--   paper_ledger_entry     every change of a balance, append-only; a balance always equals the sum of its entries
--   paper_futures_setting  leverage and margin mode of one Futures pair (Q-T2, Q-T3)
--   paper_order_list       OCO, OTO and OTOCO groups
--   paper_order            every order, open or finished; never deleted, so the order history is complete
--   paper_fill             every execution of an order, append-only (the trade history)
--   paper_position         a Futures position from opening to closing (the position history)
--   paper_funding_payment  the funding fee one position paid or received at one settlement
--   paper_equity_snapshot  the value of an account at 00:00 UTC, for the PnL analysis
--   paper_matching_watermark  technical: where the paper matching engine stopped, like matching_watermark (V19)
--
-- History is kept for good: nothing here is ever deleted. The virtual funds are granted once, when the account is
-- opened; nothing grants them again (Q-T5 as revised on 2026-10-06: there is no reset).
--
-- Conventions of V1 apply: application-assigned uuid keys without a default, varchar enumerations with a check,
-- timestamptz instants, numeric precision by kind (price and quantity (28,12), amount (28,8), rate (12,8)), no
-- cascading delete, every foreign key the leading column of an index. Amounts are in the coin the row names;
-- the equity snapshot is in USDT.

-- =============================================================================================
-- Account and wallets
-- =============================================================================================

CREATE TABLE paper_account (
    account_id    uuid        NOT NULL,
    user_id       uuid        NOT NULL,
    position_mode varchar(32) NOT NULL,
    created_at    timestamptz NOT NULL,
    updated_at    timestamptz NOT NULL,
    version       bigint      NOT NULL DEFAULT 0,
    CONSTRAINT pk_paper_account PRIMARY KEY (account_id),
    CONSTRAINT uq_paper_account_user UNIQUE (user_id),
    CONSTRAINT fk_paper_account_user FOREIGN KEY (user_id) REFERENCES user_account (user_id),
    -- Q-T4: one-way holds one position per pair, hedge one per side.
    CONSTRAINT ck_paper_account_position_mode CHECK (position_mode IN ('ONE_WAY', 'HEDGE'))
);

CREATE TABLE paper_balance (
    balance_id    uuid           NOT NULL,
    account_id    uuid           NOT NULL,
    wallet_type   varchar(32)    NOT NULL,
    coin_id       uuid           NOT NULL,
    free_amount   numeric(28, 8) NOT NULL,
    locked_amount numeric(28, 8) NOT NULL,
    created_at    timestamptz    NOT NULL,
    updated_at    timestamptz    NOT NULL,
    version       bigint         NOT NULL DEFAULT 0,
    CONSTRAINT pk_paper_balance PRIMARY KEY (balance_id),
    CONSTRAINT uq_paper_balance_coin UNIQUE (account_id, wallet_type, coin_id),
    CONSTRAINT fk_paper_balance_account FOREIGN KEY (account_id) REFERENCES paper_account (account_id),
    CONSTRAINT fk_paper_balance_coin FOREIGN KEY (coin_id) REFERENCES coin (coin_id),
    CONSTRAINT ck_paper_balance_wallet_type CHECK (wallet_type IN ('SPOT', 'FUTURES')),
    -- A wallet never owes: what an order needs is locked out of what is free, and neither goes below zero.
    CONSTRAINT ck_paper_balance_free CHECK (free_amount >= 0),
    CONSTRAINT ck_paper_balance_locked CHECK (locked_amount >= 0)
);

CREATE INDEX idx_paper_balance_coin ON paper_balance (coin_id);

CREATE TABLE paper_transfer (
    transfer_id        uuid           NOT NULL,
    account_id         uuid           NOT NULL,
    client_transfer_id varchar(64)    NOT NULL,
    coin_id            uuid           NOT NULL,
    from_wallet        varchar(32)    NOT NULL,
    to_wallet          varchar(32)    NOT NULL,
    amount             numeric(28, 8) NOT NULL,
    created_at         timestamptz    NOT NULL,
    updated_at         timestamptz    NOT NULL,
    version            bigint         NOT NULL DEFAULT 0,
    CONSTRAINT pk_paper_transfer PRIMARY KEY (transfer_id),
    -- The idempotency key of a transfer: a request sent again after a timeout is the same transfer, not a second one.
    CONSTRAINT uq_paper_transfer_client_transfer_id UNIQUE (account_id, client_transfer_id),
    CONSTRAINT fk_paper_transfer_account FOREIGN KEY (account_id) REFERENCES paper_account (account_id),
    CONSTRAINT fk_paper_transfer_coin FOREIGN KEY (coin_id) REFERENCES coin (coin_id),
    CONSTRAINT ck_paper_transfer_from_wallet CHECK (from_wallet IN ('SPOT', 'FUTURES')),
    CONSTRAINT ck_paper_transfer_to_wallet CHECK (to_wallet IN ('SPOT', 'FUTURES')),
    CONSTRAINT ck_paper_transfer_direction CHECK (from_wallet <> to_wallet),
    CONSTRAINT ck_paper_transfer_amount CHECK (amount > 0)
);

-- The transfer history reads an account newest first.
CREATE INDEX idx_paper_transfer_account ON paper_transfer (account_id, created_at DESC);
CREATE INDEX idx_paper_transfer_coin ON paper_transfer (coin_id);

-- Every change of a balance. The reference names what caused it; it is not a foreign key because it points at
-- one of several tables, and the rows it points at are never deleted.
CREATE TABLE paper_ledger_entry (
    entry_id             uuid           NOT NULL,
    account_id           uuid           NOT NULL,
    wallet_type          varchar(32)    NOT NULL,
    coin_id              uuid           NOT NULL,
    entry_type           varchar(32)    NOT NULL,
    amount               numeric(28, 8) NOT NULL,
    balance_after_amount numeric(28, 8) NOT NULL,
    ref_type             varchar(32),
    ref_id               uuid,
    created_at           timestamptz    NOT NULL,
    updated_at           timestamptz    NOT NULL,
    version              bigint         NOT NULL DEFAULT 0,
    CONSTRAINT pk_paper_ledger_entry PRIMARY KEY (entry_id),
    CONSTRAINT fk_paper_ledger_entry_account FOREIGN KEY (account_id) REFERENCES paper_account (account_id),
    CONSTRAINT fk_paper_ledger_entry_coin FOREIGN KEY (coin_id) REFERENCES coin (coin_id),
    CONSTRAINT ck_paper_ledger_entry_wallet_type CHECK (wallet_type IN ('SPOT', 'FUTURES')),
    CONSTRAINT ck_paper_ledger_entry_entry_type CHECK (entry_type IN (
        'INITIAL_GRANT', 'TRADE', 'FEE', 'REALIZED_PNL', 'FUNDING_FEE', 'TRANSFER',
        'LIQUIDATION_FEE')),
    CONSTRAINT ck_paper_ledger_entry_ref_type
        CHECK (ref_type IS NULL OR ref_type IN ('ORDER', 'FILL', 'POSITION', 'TRANSFER', 'FUNDING', 'ACCOUNT')),
    CONSTRAINT ck_paper_ledger_entry_amount CHECK (amount <> 0),
    CONSTRAINT ck_paper_ledger_entry_ref CHECK ((ref_type IS NULL) = (ref_id IS NULL))
);

CREATE INDEX idx_paper_ledger_entry_account ON paper_ledger_entry (account_id, created_at DESC);
CREATE INDEX idx_paper_ledger_entry_coin ON paper_ledger_entry (coin_id);

-- =============================================================================================
-- Orders and executions
-- =============================================================================================

CREATE TABLE paper_futures_setting (
    setting_id  uuid        NOT NULL,
    account_id  uuid        NOT NULL,
    pair_id     uuid        NOT NULL,
    leverage    integer     NOT NULL,
    margin_mode varchar(32) NOT NULL,
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL,
    version     bigint      NOT NULL DEFAULT 0,
    CONSTRAINT pk_paper_futures_setting PRIMARY KEY (setting_id),
    CONSTRAINT uq_paper_futures_setting_pair UNIQUE (account_id, pair_id),
    CONSTRAINT fk_paper_futures_setting_account FOREIGN KEY (account_id) REFERENCES paper_account (account_id),
    CONSTRAINT fk_paper_futures_setting_pair FOREIGN KEY (pair_id) REFERENCES crypto_pair (pair_id),
    -- Q-T3: up to the exchange's maximum; the pair's own bracket is checked by the application.
    CONSTRAINT ck_paper_futures_setting_leverage CHECK (leverage BETWEEN 1 AND 125),
    CONSTRAINT ck_paper_futures_setting_margin_mode CHECK (margin_mode IN ('CROSS', 'ISOLATED'))
);

CREATE INDEX idx_paper_futures_setting_pair ON paper_futures_setting (pair_id);

CREATE TABLE paper_order_list (
    list_id     uuid        NOT NULL,
    account_id  uuid        NOT NULL,
    pair_id     uuid        NOT NULL,
    market_type varchar(32) NOT NULL,
    list_type   varchar(32) NOT NULL,
    list_status varchar(32) NOT NULL,
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL,
    version     bigint      NOT NULL DEFAULT 0,
    CONSTRAINT pk_paper_order_list PRIMARY KEY (list_id),
    CONSTRAINT fk_paper_order_list_account FOREIGN KEY (account_id) REFERENCES paper_account (account_id),
    CONSTRAINT fk_paper_order_list_pair FOREIGN KEY (pair_id) REFERENCES crypto_pair (pair_id),
    CONSTRAINT ck_paper_order_list_market_type CHECK (market_type IN ('SPOT', 'FUTURES')),
    -- OCO: two orders, one cancels the other. OTO: a working order places a pending one when it fills.
    -- OTOCO: a working order places a pending OCO when it fills (a Limit with TP/SL attached).
    CONSTRAINT ck_paper_order_list_list_type CHECK (list_type IN ('OCO', 'OTO', 'OTOCO')),
    CONSTRAINT ck_paper_order_list_list_status CHECK (list_status IN ('EXECUTING', 'ALL_DONE', 'REJECTED'))
);

CREATE INDEX idx_paper_order_list_account ON paper_order_list (account_id);
CREATE INDEX idx_paper_order_list_pair ON paper_order_list (pair_id);

CREATE TABLE paper_order (
    order_id               uuid            NOT NULL,
    account_id             uuid            NOT NULL,
    pair_id                uuid            NOT NULL,
    market_type            varchar(32)     NOT NULL,
    client_order_id        varchar(64)     NOT NULL,
    list_id                uuid,
    side                   varchar(32)     NOT NULL,
    position_side          varchar(32),
    order_type             varchar(32)     NOT NULL,
    time_in_force          varchar(32),
    limit_price            numeric(28, 12),
    stop_price             numeric(28, 12),
    working_type           varchar(32),
    trigger_condition      varchar(32),
    activation_price       numeric(28, 12),
    callback_rate          numeric(12, 8),
    trailing_extreme_price numeric(28, 12),
    orig_quantity          numeric(28, 12),
    quote_order_amount     numeric(28, 8),
    executed_quantity      numeric(28, 12) NOT NULL,
    cum_quote_amount       numeric(28, 8)  NOT NULL,
    avg_price              numeric(28, 12),
    reduce_only            boolean         NOT NULL,
    close_position         boolean         NOT NULL,
    order_status           varchar(32)     NOT NULL,
    status_reason          varchar(64),
    triggered_at           timestamptz,
    closed_at              timestamptz,
    created_at             timestamptz     NOT NULL,
    updated_at             timestamptz     NOT NULL,
    version                bigint          NOT NULL DEFAULT 0,
    CONSTRAINT pk_paper_order PRIMARY KEY (order_id),
    -- The idempotency key of a placement: the same client id twice is the same order, not two.
    CONSTRAINT uq_paper_order_client_order_id UNIQUE (account_id, client_order_id),
    CONSTRAINT fk_paper_order_account FOREIGN KEY (account_id) REFERENCES paper_account (account_id),
    CONSTRAINT fk_paper_order_pair FOREIGN KEY (pair_id) REFERENCES crypto_pair (pair_id),
    CONSTRAINT fk_paper_order_list FOREIGN KEY (list_id) REFERENCES paper_order_list (list_id),
    CONSTRAINT ck_paper_order_market_type CHECK (market_type IN ('SPOT', 'FUTURES')),
    CONSTRAINT ck_paper_order_side CHECK (side IN ('BUY', 'SELL')),
    CONSTRAINT ck_paper_order_position_side
        CHECK (position_side IS NULL OR position_side IN ('BOTH', 'LONG', 'SHORT')),
    CONSTRAINT ck_paper_order_order_type CHECK (order_type IN (
        'MARKET', 'LIMIT', 'STOP_MARKET', 'STOP_LIMIT', 'TAKE_PROFIT_MARKET', 'TAKE_PROFIT_LIMIT',
        'TRAILING_STOP_MARKET', 'TRAILING_STOP_LIMIT')),
    -- GTX is post-only: refused when it would take liquidity on arrival.
    CONSTRAINT ck_paper_order_time_in_force
        CHECK (time_in_force IS NULL OR time_in_force IN ('GTC', 'IOC', 'FOK', 'GTX')),
    CONSTRAINT ck_paper_order_working_type
        CHECK (working_type IS NULL OR working_type IN ('LAST_PRICE', 'MARK_PRICE')),
    -- Fixed when the order is placed, from where the price stood, so a replay triggers it the same way.
    CONSTRAINT ck_paper_order_trigger_condition
        CHECK (trigger_condition IS NULL OR trigger_condition IN ('AT_OR_ABOVE', 'AT_OR_BELOW')),
    -- PENDING_NEW: the pending leg of an OTO or OTOCO, placed only when its working order fills.
    CONSTRAINT ck_paper_order_order_status CHECK (order_status IN (
        'NEW', 'PENDING_NEW', 'PARTIALLY_FILLED', 'FILLED', 'CANCELED', 'EXPIRED', 'REJECTED')),
    -- Spot owns what it sells, so it has no position side, nothing to reduce and no mark price (BR-21).
    CONSTRAINT ck_paper_order_spot_fields CHECK (
        market_type <> 'SPOT'
        OR (position_side IS NULL AND NOT reduce_only AND NOT close_position
            AND (working_type IS NULL OR working_type = 'LAST_PRICE'))),
    CONSTRAINT ck_paper_order_futures_position_side CHECK (market_type <> 'FUTURES' OR position_side IS NOT NULL),
    -- A limit leg waits at its price for as long as its time in force says.
    CONSTRAINT ck_paper_order_limit_price CHECK (
        (order_type IN ('LIMIT', 'STOP_LIMIT', 'TAKE_PROFIT_LIMIT', 'TRAILING_STOP_LIMIT'))
        = (limit_price IS NOT NULL AND time_in_force IS NOT NULL)),
    -- A stop or take profit waits for its trigger; a trailing stop for its callback, from an optional activation.
    CONSTRAINT ck_paper_order_stop_price CHECK (
        order_type NOT IN ('STOP_MARKET', 'STOP_LIMIT', 'TAKE_PROFIT_MARKET', 'TAKE_PROFIT_LIMIT')
        OR (stop_price IS NOT NULL AND trigger_condition IS NOT NULL)),
    CONSTRAINT ck_paper_order_trailing CHECK (
        (order_type IN ('TRAILING_STOP_MARKET', 'TRAILING_STOP_LIMIT'))
        = (callback_rate IS NOT NULL AND trigger_condition IS NOT NULL)),
    CONSTRAINT ck_paper_order_callback_rate CHECK (callback_rate IS NULL OR callback_rate > 0),
    -- The size is a quantity, a quote amount (a Spot market order "by total"), or the whole position.
    CONSTRAINT ck_paper_order_size CHECK (
        orig_quantity IS NOT NULL
        OR (quote_order_amount IS NOT NULL AND market_type = 'SPOT' AND order_type = 'MARKET')
        OR close_position),
    CONSTRAINT ck_paper_order_executed CHECK (
        executed_quantity >= 0 AND cum_quote_amount >= 0
        AND (orig_quantity IS NULL OR executed_quantity <= orig_quantity)),
    -- A finished order records when it finished, and only a finished one does.
    CONSTRAINT ck_paper_order_closed_at CHECK (
        (order_status IN ('FILLED', 'CANCELED', 'EXPIRED', 'REJECTED')) = (closed_at IS NOT NULL))
);

CREATE INDEX idx_paper_order_account ON paper_order (account_id, created_at DESC);
CREATE INDEX idx_paper_order_pair ON paper_order (pair_id);
CREATE INDEX idx_paper_order_list ON paper_order (list_id);
-- The matching engine rebuilds its books from the open orders of one pair.
CREATE INDEX idx_paper_order_open ON paper_order (pair_id, market_type)
    WHERE order_status IN ('NEW', 'PARTIALLY_FILLED');

CREATE TABLE paper_fill (
    fill_id             uuid            NOT NULL,
    order_id            uuid            NOT NULL,
    account_id          uuid            NOT NULL,
    pair_id             uuid            NOT NULL,
    market_type         varchar(32)     NOT NULL,
    side                varchar(32)     NOT NULL,
    position_side       varchar(32),
    fill_price          numeric(28, 12) NOT NULL,
    fill_quantity       numeric(28, 12) NOT NULL,
    quote_amount        numeric(28, 8)  NOT NULL,
    fee_amount          numeric(28, 8)  NOT NULL,
    fee_coin_id         uuid            NOT NULL,
    liquidity           varchar(32)     NOT NULL,
    realized_pnl_amount numeric(28, 8),
    fill_source         varchar(32)     NOT NULL,
    traded_at           timestamptz     NOT NULL,
    created_at          timestamptz     NOT NULL,
    updated_at          timestamptz     NOT NULL,
    version             bigint          NOT NULL DEFAULT 0,
    CONSTRAINT pk_paper_fill PRIMARY KEY (fill_id),
    CONSTRAINT fk_paper_fill_order FOREIGN KEY (order_id) REFERENCES paper_order (order_id),
    CONSTRAINT fk_paper_fill_account FOREIGN KEY (account_id) REFERENCES paper_account (account_id),
    CONSTRAINT fk_paper_fill_pair FOREIGN KEY (pair_id) REFERENCES crypto_pair (pair_id),
    CONSTRAINT fk_paper_fill_fee_coin FOREIGN KEY (fee_coin_id) REFERENCES coin (coin_id),
    CONSTRAINT ck_paper_fill_market_type CHECK (market_type IN ('SPOT', 'FUTURES')),
    CONSTRAINT ck_paper_fill_side CHECK (side IN ('BUY', 'SELL')),
    CONSTRAINT ck_paper_fill_position_side
        CHECK (position_side IS NULL OR position_side IN ('BOTH', 'LONG', 'SHORT')),
    CONSTRAINT ck_paper_fill_liquidity CHECK (liquidity IN ('MAKER', 'TAKER')),
    -- REPLAY: decided by the 1m candles replayed after a restart or a lost stream (Q-T6).
    CONSTRAINT ck_paper_fill_fill_source CHECK (fill_source IN ('LIVE', 'REPLAY')),
    CONSTRAINT ck_paper_fill_amounts CHECK (
        fill_price > 0 AND fill_quantity > 0 AND quote_amount > 0 AND fee_amount >= 0),
    -- Only a Futures fill has a position side and can realise a profit or loss.
    CONSTRAINT ck_paper_fill_market_fields CHECK (
        (market_type = 'SPOT' AND position_side IS NULL AND realized_pnl_amount IS NULL)
        OR (market_type = 'FUTURES' AND position_side IS NOT NULL))
);

CREATE INDEX idx_paper_fill_account ON paper_fill (account_id, traded_at DESC);
CREATE INDEX idx_paper_fill_order ON paper_fill (order_id);
CREATE INDEX idx_paper_fill_pair ON paper_fill (pair_id);
CREATE INDEX idx_paper_fill_fee_coin ON paper_fill (fee_coin_id);

-- =============================================================================================
-- Futures positions
-- =============================================================================================

CREATE TABLE paper_position (
    position_id            uuid            NOT NULL,
    account_id             uuid            NOT NULL,
    pair_id                uuid            NOT NULL,
    position_side          varchar(32)     NOT NULL,
    direction              varchar(32)     NOT NULL,
    margin_mode            varchar(32)     NOT NULL,
    leverage               integer         NOT NULL,
    position_quantity      numeric(28, 12) NOT NULL,
    max_quantity           numeric(28, 12) NOT NULL,
    entry_price            numeric(28, 12) NOT NULL,
    exit_price             numeric(28, 12),
    isolated_margin_amount numeric(28, 8),
    realized_pnl_amount    numeric(28, 8)  NOT NULL,
    fee_amount             numeric(28, 8)  NOT NULL,
    funding_amount         numeric(28, 8)  NOT NULL,
    position_status        varchar(32)     NOT NULL,
    close_reason           varchar(32),
    opened_at              timestamptz     NOT NULL,
    closed_at              timestamptz,
    created_at             timestamptz     NOT NULL,
    updated_at             timestamptz     NOT NULL,
    version                bigint          NOT NULL DEFAULT 0,
    CONSTRAINT pk_paper_position PRIMARY KEY (position_id),
    CONSTRAINT fk_paper_position_account FOREIGN KEY (account_id) REFERENCES paper_account (account_id),
    CONSTRAINT fk_paper_position_pair FOREIGN KEY (pair_id) REFERENCES crypto_pair (pair_id),
    CONSTRAINT ck_paper_position_position_side CHECK (position_side IN ('BOTH', 'LONG', 'SHORT')),
    CONSTRAINT ck_paper_position_direction CHECK (direction IN ('LONG', 'SHORT')),
    CONSTRAINT ck_paper_position_margin_mode CHECK (margin_mode IN ('CROSS', 'ISOLATED')),
    CONSTRAINT ck_paper_position_position_status CHECK (position_status IN ('OPEN', 'CLOSED')),
    CONSTRAINT ck_paper_position_close_reason CHECK (close_reason IS NULL OR close_reason IN (
        'MANUAL', 'TAKE_PROFIT', 'STOP_LOSS', 'LIQUIDATION')),
    -- In hedge mode the side is the direction; in one-way mode (BOTH) either direction is held.
    CONSTRAINT ck_paper_position_hedge_side CHECK (position_side = 'BOTH' OR position_side = direction),
    CONSTRAINT ck_paper_position_leverage CHECK (leverage BETWEEN 1 AND 125),
    -- An isolated position carries its own margin; a cross one draws on the wallet.
    CONSTRAINT ck_paper_position_isolated_margin
        CHECK ((margin_mode = 'ISOLATED') = (isolated_margin_amount IS NOT NULL)),
    CONSTRAINT ck_paper_position_quantity CHECK (
        entry_price > 0 AND position_quantity >= 0 AND position_quantity <= max_quantity AND max_quantity > 0),
    -- Open while it holds a quantity; closed with an instant, a reason and the average exit price.
    CONSTRAINT ck_paper_position_lifecycle CHECK (
        (position_status = 'OPEN' AND position_quantity > 0
            AND closed_at IS NULL AND close_reason IS NULL AND exit_price IS NULL)
        OR (position_status = 'CLOSED' AND position_quantity = 0
            AND closed_at IS NOT NULL AND close_reason IS NOT NULL AND exit_price IS NOT NULL))
);

CREATE INDEX idx_paper_position_account ON paper_position (account_id, opened_at DESC);
CREATE INDEX idx_paper_position_pair ON paper_position (pair_id);
-- One open position per pair and side; the closed ones are the position history.
CREATE UNIQUE INDEX uq_paper_position_open ON paper_position (account_id, pair_id, position_side)
    WHERE position_status = 'OPEN';

CREATE TABLE paper_funding_payment (
    payment_id        uuid            NOT NULL,
    account_id        uuid            NOT NULL,
    position_id       uuid            NOT NULL,
    pair_id           uuid            NOT NULL,
    funding_time      timestamptz     NOT NULL,
    funding_rate      numeric(12, 8)  NOT NULL,
    mark_price        numeric(28, 12) NOT NULL,
    position_quantity numeric(28, 12) NOT NULL,
    amount            numeric(28, 8)  NOT NULL,
    created_at        timestamptz     NOT NULL,
    updated_at        timestamptz     NOT NULL,
    version           bigint          NOT NULL DEFAULT 0,
    CONSTRAINT pk_paper_funding_payment PRIMARY KEY (payment_id),
    -- A settlement is paid once per position, however often the job runs (idempotent).
    CONSTRAINT uq_paper_funding_payment_settlement UNIQUE (position_id, funding_time),
    CONSTRAINT fk_paper_funding_payment_account FOREIGN KEY (account_id) REFERENCES paper_account (account_id),
    CONSTRAINT fk_paper_funding_payment_position FOREIGN KEY (position_id) REFERENCES paper_position (position_id),
    CONSTRAINT fk_paper_funding_payment_pair FOREIGN KEY (pair_id) REFERENCES crypto_pair (pair_id),
    CONSTRAINT ck_paper_funding_payment_values CHECK (mark_price > 0 AND position_quantity > 0)
);

CREATE INDEX idx_paper_funding_payment_account ON paper_funding_payment (account_id, funding_time DESC);
CREATE INDEX idx_paper_funding_payment_pair ON paper_funding_payment (pair_id);

-- =============================================================================================
-- Analysis and operations
-- =============================================================================================

CREATE TABLE paper_equity_snapshot (
    snapshot_id           uuid           NOT NULL,
    account_id            uuid           NOT NULL,
    snapshot_date         date           NOT NULL,
    spot_equity_amount    numeric(28, 8) NOT NULL,
    futures_equity_amount numeric(28, 8) NOT NULL,
    total_equity_amount   numeric(28, 8) NOT NULL,
    created_at            timestamptz    NOT NULL,
    updated_at            timestamptz    NOT NULL,
    version               bigint         NOT NULL DEFAULT 0,
    CONSTRAINT pk_paper_equity_snapshot PRIMARY KEY (snapshot_id),
    CONSTRAINT uq_paper_equity_snapshot_day UNIQUE (account_id, snapshot_date),
    CONSTRAINT fk_paper_equity_snapshot_account FOREIGN KEY (account_id) REFERENCES paper_account (account_id),
    CONSTRAINT ck_paper_equity_snapshot_total
        CHECK (total_equity_amount = spot_equity_amount + futures_equity_amount)
);

-- Technical, like matching_watermark (V19) and kept apart from it so the plan matching of T-043 is untouched
-- (Q-T1): the open time of the last closed 1-minute candle the paper engine fully matched, per market and pair.
CREATE TABLE paper_matching_watermark (
    market_type           varchar(32) NOT NULL,
    pair_id               uuid        NOT NULL,
    last_candle_open_time timestamptz NOT NULL,
    updated_at            timestamptz NOT NULL,
    CONSTRAINT pk_paper_matching_watermark PRIMARY KEY (market_type, pair_id),
    CONSTRAINT ck_paper_matching_watermark_market_type CHECK (market_type IN ('SPOT', 'FUTURES'))
);
