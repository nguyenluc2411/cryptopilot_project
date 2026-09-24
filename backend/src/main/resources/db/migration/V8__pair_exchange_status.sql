-- The exchange's trading status of each market of a pair, and when it was last read (T-019, Q-15).
--
-- NSF-01 flags "a pair whose status is no longer TRADING" on SCR-37, and crypto_pair had nowhere to keep
-- that status: pair_status is the administrator's switch, not the exchange's. Added here, beside the
-- filters the same synchronisation writes, once a day at most — so this is reference data, not a hot
-- column (last prices stay in Redis and the snapshot tables).
--
-- Two columns per market, kept apart on purpose. The normalized status is the system's own vocabulary —
-- TRADING, NOT_TRADING, DELISTED — and it is the only one the CHECK constraint and the code decide on. The
-- raw status is Binance's text exactly as sent (TRADING, BREAK, HALT, END_OF_DAY, ...), stored for the
-- administrator and for diagnosis and never interpreted: a status Binance adds tomorrow lands in the raw
-- column and normalizes to NOT_TRADING without a migration. Keeping the vendor's words out of the
-- constraint is what keeps the vendor's model out of ours (Evans, Domain-Driven Design, 2003, ch. 14,
-- "Anticorruption Layer").
--
-- All nullable. A market the pair has never been listed on has no status at all, which is different from
-- DELISTED (listed once, gone now). last_synced_at is null until the first synchronisation reaches the pair.

ALTER TABLE crypto_pair
    ADD COLUMN spot_exchange_status        varchar(32),
    ADD COLUMN spot_exchange_status_raw    text,
    ADD COLUMN futures_exchange_status     varchar(32),
    ADD COLUMN futures_exchange_status_raw text,
    ADD COLUMN last_synced_at              timestamptz;

ALTER TABLE crypto_pair
    ADD CONSTRAINT ck_crypto_pair_spot_exchange_status
        CHECK (spot_exchange_status IS NULL OR spot_exchange_status IN ('TRADING', 'NOT_TRADING', 'DELISTED')),
    ADD CONSTRAINT ck_crypto_pair_futures_exchange_status
        CHECK (futures_exchange_status IS NULL OR futures_exchange_status IN ('TRADING', 'NOT_TRADING', 'DELISTED'));
