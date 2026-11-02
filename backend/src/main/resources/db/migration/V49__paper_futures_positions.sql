-- D-127, paper Futures USD-M with positions of its own, apart from trading plans. Four tables: the account settings,
-- the per-pair settings, the positions and the audit of every change of an isolated wallet. The Futures wallet keeps
-- its balance and ledger in wallet_balance and wallet_ledger_entry (D-117); an isolated wallet is a column of its
-- position, so moving margin between the cross wallet and a position writes no ledger entry.

-- One row per Trader, created before the first Futures action. version is the Futures account version: every change
-- of the Futures wallet, a position or an isolated wallet raises it, so a cached snapshot older than the row is
-- dropped. liquidating_since is set while a cross liquidation runs, and orders are refused until it is cleared.
CREATE TABLE futures_account_config (
    user_id            uuid        NOT NULL,
    dual_side_position boolean     NOT NULL DEFAULT FALSE,
    liquidating_since  timestamptz,
    created_at         timestamptz NOT NULL,
    updated_at         timestamptz NOT NULL,
    version            bigint      NOT NULL DEFAULT 0,
    CONSTRAINT pk_futures_account_config PRIMARY KEY (user_id),
    CONSTRAINT fk_futures_account_config_user FOREIGN KEY (user_id) REFERENCES user_account (user_id)
);

-- Leverage and margin type are chosen per pair, as on the exchange. A pair without a row trades at the default
-- (20x, or the pair's maximum when lower, and CROSSED).
CREATE TABLE futures_symbol_config (
    symbol_config_id uuid        NOT NULL,
    user_id          uuid        NOT NULL,
    pair_id          uuid        NOT NULL,
    leverage         integer     NOT NULL,
    margin_type      varchar(32) NOT NULL,
    created_at       timestamptz NOT NULL,
    updated_at       timestamptz NOT NULL,
    version          bigint      NOT NULL DEFAULT 0,
    CONSTRAINT pk_futures_symbol_config PRIMARY KEY (symbol_config_id),
    CONSTRAINT uq_futures_symbol_config_pair UNIQUE (user_id, pair_id),
    CONSTRAINT fk_futures_symbol_config_user FOREIGN KEY (user_id) REFERENCES user_account (user_id),
    CONSTRAINT fk_futures_symbol_config_pair FOREIGN KEY (pair_id) REFERENCES crypto_pair (pair_id),
    CONSTRAINT ck_futures_symbol_config_leverage CHECK (leverage BETWEEN 1 AND 125),
    CONSTRAINT ck_futures_symbol_config_margin_type CHECK (margin_type IN ('CROSSED', 'ISOLATED'))
);

CREATE INDEX idx_futures_symbol_config_pair ON futures_symbol_config (pair_id);

-- One row is one life of a position, from its first fill until its size is back to zero; a one-way reversal closes
-- the row and opens the next one. Only one OPEN row per Trader, pair and position side.
CREATE TABLE futures_position (
    position_id            uuid            NOT NULL,
    user_id                uuid            NOT NULL,
    pair_id                uuid            NOT NULL,
    position_side          varchar(32)     NOT NULL,
    direction              varchar(32)     NOT NULL,
    margin_type            varchar(32)     NOT NULL,
    leverage               integer         NOT NULL,
    quantity               numeric(28, 12) NOT NULL,
    max_quantity           numeric(28, 12) NOT NULL,
    entry_price            numeric(28, 12) NOT NULL,
    close_avg_price        numeric(28, 12),
    closed_quantity        numeric(28, 12) NOT NULL DEFAULT 0,
    isolated_wallet_amount numeric(28, 8),
    realized_pnl_amount    numeric(28, 8)  NOT NULL DEFAULT 0,
    commission_amount      numeric(28, 8)  NOT NULL DEFAULT 0,
    funding_amount         numeric(28, 8)  NOT NULL DEFAULT 0,
    liquidation_fee_amount numeric(28, 8)  NOT NULL DEFAULT 0,
    position_status        varchar(32)     NOT NULL,
    close_reason           varchar(32),
    opened_at              timestamptz     NOT NULL,
    closed_at              timestamptz,
    created_at             timestamptz     NOT NULL,
    updated_at             timestamptz     NOT NULL,
    version                bigint          NOT NULL DEFAULT 0,
    CONSTRAINT pk_futures_position PRIMARY KEY (position_id),
    CONSTRAINT fk_futures_position_user FOREIGN KEY (user_id) REFERENCES user_account (user_id),
    CONSTRAINT fk_futures_position_pair FOREIGN KEY (pair_id) REFERENCES crypto_pair (pair_id),
    CONSTRAINT ck_futures_position_position_side CHECK (position_side IN ('BOTH', 'LONG', 'SHORT')),
    CONSTRAINT ck_futures_position_direction CHECK (direction IN ('LONG', 'SHORT')),
    -- In hedge mode the side is the direction.
    CONSTRAINT ck_futures_position_hedge_side CHECK (position_side = 'BOTH' OR position_side = direction),
    CONSTRAINT ck_futures_position_margin_type CHECK (margin_type IN ('CROSSED', 'ISOLATED')),
    CONSTRAINT ck_futures_position_leverage CHECK (leverage BETWEEN 1 AND 125),
    CONSTRAINT ck_futures_position_amounts CHECK (
        quantity >= 0 AND max_quantity > 0 AND quantity <= max_quantity AND entry_price > 0
        AND closed_quantity >= 0 AND (close_avg_price IS NULL OR close_avg_price > 0)
        AND commission_amount >= 0 AND liquidation_fee_amount >= 0),
    -- An isolated position has its own wallet, never negative; a cross one has none.
    CONSTRAINT ck_futures_position_isolated_wallet CHECK (
        (margin_type = 'ISOLATED' AND isolated_wallet_amount IS NOT NULL AND isolated_wallet_amount >= 0)
        OR (margin_type = 'CROSSED' AND isolated_wallet_amount IS NULL)),
    CONSTRAINT ck_futures_position_status CHECK (position_status IN ('OPEN', 'CLOSED')),
    CONSTRAINT ck_futures_position_close_reason CHECK (
        close_reason IS NULL
        OR close_reason IN ('MANUAL', 'TAKE_PROFIT', 'STOP_LOSS', 'LIQUIDATION', 'REDUCE', 'SYSTEM')),
    -- An open position has a size and no close; a closed one has none left, its wallet released, and why it closed.
    CONSTRAINT ck_futures_position_lifecycle CHECK (
        (position_status = 'OPEN' AND quantity > 0 AND closed_at IS NULL AND close_reason IS NULL)
        OR (position_status = 'CLOSED' AND quantity = 0 AND closed_at IS NOT NULL AND close_reason IS NOT NULL
            AND COALESCE(isolated_wallet_amount, 0) = 0))
);

CREATE UNIQUE INDEX uq_futures_position_open ON futures_position (user_id, pair_id, position_side)
    WHERE position_status = 'OPEN';
CREATE INDEX idx_futures_position_user_opened ON futures_position (user_id, opened_at DESC);
CREATE INDEX idx_futures_position_pair ON futures_position (pair_id, position_status);

-- Append-only: one row per change of an isolated wallet, amount the signed change, so the wallet of a position is
-- always the sum of its rows. The insurance cover of a loss beyond the wallet is a ledger entry the row points to.
CREATE TABLE futures_margin_adjustment (
    adjustment_id   uuid           NOT NULL,
    position_id     uuid           NOT NULL,
    user_id         uuid           NOT NULL,
    pair_id         uuid           NOT NULL,
    adjustment_type varchar(32)    NOT NULL,
    amount          numeric(28, 8) NOT NULL,
    iw_after_amount numeric(28, 8) NOT NULL,
    ref_type        varchar(32),
    ref_id          uuid,
    created_at      timestamptz    NOT NULL,
    updated_at      timestamptz    NOT NULL,
    version         bigint         NOT NULL DEFAULT 0,
    CONSTRAINT pk_futures_margin_adjustment PRIMARY KEY (adjustment_id),
    CONSTRAINT fk_futures_margin_adjustment_position FOREIGN KEY (position_id) REFERENCES futures_position (position_id),
    CONSTRAINT fk_futures_margin_adjustment_user FOREIGN KEY (user_id) REFERENCES user_account (user_id),
    CONSTRAINT fk_futures_margin_adjustment_pair FOREIGN KEY (pair_id) REFERENCES crypto_pair (pair_id),
    CONSTRAINT ck_futures_margin_adjustment_type CHECK (adjustment_type IN (
        'OPEN', 'INCREASE', 'MANUAL_ADD', 'MANUAL_REDUCE', 'RELEASE', 'LOSS_ABSORB', 'CLOSE', 'LIQUIDATION',
        'FUNDING')),
    CONSTRAINT ck_futures_margin_adjustment_amount CHECK (amount <> 0 AND iw_after_amount >= 0),
    CONSTRAINT ck_futures_margin_adjustment_ref_type
        CHECK (ref_type IS NULL OR ref_type IN ('FILL', 'LEDGER', 'FUNDING')),
    CONSTRAINT ck_futures_margin_adjustment_ref CHECK ((ref_type IS NULL) = (ref_id IS NULL))
);

CREATE INDEX idx_futures_margin_adjustment_position ON futures_margin_adjustment (position_id, created_at);
CREATE INDEX idx_futures_margin_adjustment_user ON futures_margin_adjustment (user_id, created_at DESC);
CREATE INDEX idx_futures_margin_adjustment_pair ON futures_margin_adjustment (pair_id);

-- The Futures wallet locks nothing: order margin is derived. Its balance may go below zero while cross positions are
-- open (one closed at a loss while another is in profit); Spot keeps both amounts at zero or above.
ALTER TABLE wallet_balance DROP CONSTRAINT ck_wallet_balance_free;
ALTER TABLE wallet_balance DROP CONSTRAINT ck_wallet_balance_locked;
ALTER TABLE wallet_balance
    ADD CONSTRAINT ck_wallet_balance_free CHECK (wallet_type = 'FUTURES' OR free_amount >= 0),
    ADD CONSTRAINT ck_wallet_balance_locked
        CHECK (locked_amount >= 0 AND (wallet_type <> 'FUTURES' OR locked_amount = 0));

-- INSURANCE_COVER: the loss beyond a position's margin, which the insurance fund takes. A funding fee names its
-- position and settlement; the partial unique index makes a funding run idempotent.
ALTER TABLE wallet_ledger_entry DROP CONSTRAINT ck_wallet_ledger_entry_entry_type;
ALTER TABLE wallet_ledger_entry
    ADD CONSTRAINT ck_wallet_ledger_entry_entry_type CHECK (entry_type IN (
        'INITIAL_GRANT', 'TRADE', 'FEE', 'REALIZED_PNL', 'FUNDING_FEE', 'TRANSFER', 'LIQUIDATION_FEE',
        'INSURANCE_COVER'));
ALTER TABLE wallet_ledger_entry DROP CONSTRAINT ck_wallet_ledger_entry_ref_type;
ALTER TABLE wallet_ledger_entry
    ADD CONSTRAINT ck_wallet_ledger_entry_ref_type
        CHECK (ref_type IS NULL OR ref_type IN ('ORDER', 'FILL', 'TRANSFER', 'USER', 'POSITION', 'FUNDING'));
CREATE UNIQUE INDEX uq_wallet_ledger_entry_funding ON wallet_ledger_entry (ref_type, ref_id)
    WHERE entry_type = 'FUNDING_FEE';

-- Every Futures fill belongs to exactly one position (a reversal is stored as two fills); a Spot fill to none.
-- source_time is the candle a REPLAY fill was decided on, shown to the Trader; traded_at is when it was applied.
ALTER TABLE order_fill ADD COLUMN position_id uuid;
ALTER TABLE order_fill ADD COLUMN source_time timestamptz;
ALTER TABLE order_fill
    ADD CONSTRAINT fk_order_fill_position FOREIGN KEY (position_id) REFERENCES futures_position (position_id),
    ADD CONSTRAINT ck_order_fill_position CHECK ((market_type = 'FUTURES') = (position_id IS NOT NULL));
CREATE INDEX idx_order_fill_position ON order_fill (position_id, traded_at);

-- GTD waits until goodTillDate. A Futures client order id is at most 36 characters, as on the exchange.
-- self_trade_prevention_mode is accepted and stored; demo orders never meet each other.
ALTER TABLE trade_order ADD COLUMN good_till_date timestamptz;
ALTER TABLE trade_order ADD COLUMN self_trade_prevention_mode varchar(32);
ALTER TABLE trade_order DROP CONSTRAINT ck_trade_order_time_in_force;
ALTER TABLE trade_order
    ADD CONSTRAINT ck_trade_order_time_in_force
        CHECK (time_in_force IS NULL OR time_in_force IN ('GTC', 'IOC', 'FOK', 'GTX', 'GTD')),
    ADD CONSTRAINT ck_trade_order_good_till_date CHECK ((COALESCE(time_in_force, '') = 'GTD') = (good_till_date IS NOT NULL)),
    ADD CONSTRAINT ck_trade_order_futures_client_order_id
        CHECK (market_type <> 'FUTURES' OR length(client_order_id) <= 36),
    ADD CONSTRAINT ck_trade_order_self_trade_prevention_mode CHECK (
        self_trade_prevention_mode IS NULL
        OR self_trade_prevention_mode IN ('NONE', 'EXPIRE_TAKER', 'EXPIRE_MAKER', 'EXPIRE_BOTH'));
