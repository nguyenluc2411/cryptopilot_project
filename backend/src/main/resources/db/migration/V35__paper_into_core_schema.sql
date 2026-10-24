-- Paper trading joins the core model (PCI; D-117).
--
-- V21 kept the simulated exchange apart, owned by a paper_account row per Trader (Q-T1). This migration folds it into
-- the core model:
--
--   paper_account          gone: every row is owned by the Trader (user_id) directly; an account is open once its
--                          INITIAL_GRANT entries exist, and their instant is the opening
--   paper_transfer         gone: a transfer is the pair of TRANSFER entries it always wrote; the debit carries its
--                          idempotency key (client_transfer_id), the two entries share its id (ref_id)
--   paper_position, paper_funding_payment, paper_futures_setting
--                          gone: never written; a simulated Futures position is a plan and its journal record, its
--                          funding comes from funding_rate_history
--   paper_balance          -> wallet_balance
--   paper_ledger_entry     -> wallet_ledger_entry
--   paper_order_list       -> order_list
--   paper_order            -> trade_order, with the plan an order carries out (plan_id, optional: an order placed on
--                             the trading screen has none)
--   paper_fill             -> order_fill
--   paper_equity_snapshot  -> equity_snapshot
--   paper_matching_watermark -> order_matching_watermark (technical, kept apart from matching_watermark until the two
--                             matching engines are one)
--
-- The rows of paper_account and paper_transfer are kept, untouched, as legacy_paper_account and legacy_paper_transfer,
-- so a defect found later can be repaired from them by a forward migration; a later migration drops them.
--
-- Everything runs in the one transaction of this migration. The checks at the start and at the end raise an error
-- when the data is not what this migration expects or was not carried over whole; the transaction then rolls back
-- and the schema stays as it was. Earlier migrations are not edited.

-- =============================================================================================
-- 1. Preconditions, and the figures the end compares against
-- =============================================================================================

DO $$
DECLARE
    found bigint;
BEGIN
    SELECT (SELECT count(*) FROM paper_position)
         + (SELECT count(*) FROM paper_funding_payment)
         + (SELECT count(*) FROM paper_futures_setting)
      INTO found;
    IF found > 0 THEN
        RAISE EXCEPTION 'V35: % Futures paper rows exist; this migration drops their tables and expects none', found;
    END IF;

    SELECT count(*) INTO found
      FROM paper_ledger_entry
     WHERE ref_type IN ('POSITION', 'FUNDING');
    IF found > 0 THEN
        RAISE EXCEPTION 'V35: % ledger entries point at a position or a funding payment; expected none', found;
    END IF;

    -- A transfer is carried over in its two entries: one debit in from_wallet, one credit in to_wallet.
    SELECT count(*) INTO found
      FROM paper_transfer t
     WHERE (SELECT count(*) FROM paper_ledger_entry e
             WHERE e.ref_type = 'TRANSFER' AND e.ref_id = t.transfer_id AND e.entry_type = 'TRANSFER'
               AND e.account_id = t.account_id AND e.coin_id = t.coin_id
               AND ((e.wallet_type = t.from_wallet AND e.amount = -t.amount)
                    OR (e.wallet_type = t.to_wallet AND e.amount = t.amount))) <> 2;
    IF found > 0 THEN
        RAISE EXCEPTION 'V35: % transfers do not have exactly their debit and credit entry', found;
    END IF;

    SELECT count(*) INTO found
      FROM paper_ledger_entry e
     WHERE e.entry_type = 'TRANSFER'
       AND NOT EXISTS (SELECT 1 FROM paper_transfer t WHERE t.transfer_id = e.ref_id);
    IF found > 0 THEN
        RAISE EXCEPTION 'V35: % TRANSFER entries point at no transfer', found;
    END IF;

    SELECT count(*) INTO found
      FROM (SELECT account_id, wallet_type, coin_id FROM paper_ledger_entry
             WHERE entry_type = 'INITIAL_GRANT' GROUP BY 1, 2, 3 HAVING count(*) > 1) duplicated;
    IF found > 0 THEN
        RAISE EXCEPTION 'V35: % wallet coins were granted more than once', found;
    END IF;
END $$;

CREATE TEMPORARY TABLE pci_expected ON COMMIT DROP AS
SELECT 'balance' AS figure, count(*)::numeric AS value FROM paper_balance
UNION ALL SELECT 'ledger', count(*) FROM paper_ledger_entry
UNION ALL SELECT 'order_list', count(*) FROM paper_order_list
UNION ALL SELECT 'order', count(*) FROM paper_order
UNION ALL SELECT 'fill', count(*) FROM paper_fill
UNION ALL SELECT 'snapshot', count(*) FROM paper_equity_snapshot
UNION ALL SELECT 'watermark', count(*) FROM paper_matching_watermark
UNION ALL SELECT 'transfer', count(*) FROM paper_transfer
UNION ALL SELECT 'opened', count(*) FROM paper_account a
           WHERE EXISTS (SELECT 1 FROM paper_ledger_entry e
                          WHERE e.account_id = a.account_id AND e.entry_type = 'INITIAL_GRANT')
UNION ALL SELECT 'balance_total', coalesce(sum(free_amount + locked_amount), 0) FROM paper_balance
UNION ALL SELECT 'ledger_total', coalesce(sum(amount), 0) FROM paper_ledger_entry;

-- Per Trader, wallet and coin: the balance and the sum of its entries, compared one by one at the end.
CREATE TEMPORARY TABLE pci_expected_wallets ON COMMIT DROP AS
SELECT a.user_id, b.wallet_type, b.coin_id, b.free_amount, b.locked_amount,
       (SELECT coalesce(sum(e.amount), 0) FROM paper_ledger_entry e
         WHERE e.account_id = b.account_id AND e.wallet_type = b.wallet_type AND e.coin_id = b.coin_id) AS ledger_sum
  FROM paper_balance b
  JOIN paper_account a ON a.account_id = b.account_id;

-- =============================================================================================
-- 2. The owner: user_id instead of account_id
-- =============================================================================================

ALTER TABLE paper_balance ADD COLUMN user_id uuid;
ALTER TABLE paper_ledger_entry ADD COLUMN user_id uuid;
ALTER TABLE paper_order_list ADD COLUMN user_id uuid;
ALTER TABLE paper_order ADD COLUMN user_id uuid;
ALTER TABLE paper_fill ADD COLUMN user_id uuid;
ALTER TABLE paper_equity_snapshot ADD COLUMN user_id uuid;

UPDATE paper_balance t SET user_id = a.user_id FROM paper_account a WHERE a.account_id = t.account_id;
UPDATE paper_ledger_entry t SET user_id = a.user_id FROM paper_account a WHERE a.account_id = t.account_id;
UPDATE paper_order_list t SET user_id = a.user_id FROM paper_account a WHERE a.account_id = t.account_id;
UPDATE paper_order t SET user_id = a.user_id FROM paper_account a WHERE a.account_id = t.account_id;
UPDATE paper_fill t SET user_id = a.user_id FROM paper_account a WHERE a.account_id = t.account_id;
UPDATE paper_equity_snapshot t SET user_id = a.user_id FROM paper_account a WHERE a.account_id = t.account_id;

-- fk_*_account kept every account_id pointing at an account, so no row is left without its owner.
ALTER TABLE paper_balance ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE paper_ledger_entry ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE paper_order_list ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE paper_order ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE paper_fill ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE paper_equity_snapshot ALTER COLUMN user_id SET NOT NULL;

-- =============================================================================================
-- 3. The ledger takes over the transfers and the opening
-- =============================================================================================

ALTER TABLE paper_ledger_entry ADD COLUMN client_transfer_id varchar(64);

UPDATE paper_ledger_entry e
   SET client_transfer_id = t.client_transfer_id
  FROM paper_transfer t
 WHERE e.entry_type = 'TRANSFER'
   AND e.ref_type = 'TRANSFER'
   AND e.ref_id = t.transfer_id;

-- The grant pointed at the account; it now points at its Trader.
ALTER TABLE paper_ledger_entry DROP CONSTRAINT ck_paper_ledger_entry_ref_type;

UPDATE paper_ledger_entry SET ref_type = 'USER', ref_id = user_id WHERE ref_type = 'ACCOUNT';

ALTER TABLE paper_ledger_entry
    ADD CONSTRAINT ck_paper_ledger_entry_ref_type
        CHECK (ref_type IS NULL OR ref_type IN ('ORDER', 'FILL', 'TRANSFER', 'USER')),
    -- Both entries of a transfer carry its key, and no other entry does.
    ADD CONSTRAINT ck_paper_ledger_entry_client_transfer_id
        CHECK ((entry_type = 'TRANSFER') = (client_transfer_id IS NOT NULL)),
    ADD CONSTRAINT ck_paper_ledger_entry_transfer_ref
        CHECK (entry_type <> 'TRANSFER' OR ref_type = 'TRANSFER');

-- =============================================================================================
-- 4. Away with the account and the Futures tables nothing wrote
-- =============================================================================================

-- Dropping the column drops every constraint and index built on it: fk_*_account, uq_paper_balance_coin,
-- uq_paper_order_client_order_id, uq_paper_order_list_client_order_id, uq_paper_equity_snapshot_day and the
-- idx_*_account indexes. Each is created again on user_id in section 6.
ALTER TABLE paper_balance DROP COLUMN account_id;
ALTER TABLE paper_ledger_entry DROP COLUMN account_id;
ALTER TABLE paper_order_list DROP COLUMN account_id;
ALTER TABLE paper_order DROP COLUMN account_id;
ALTER TABLE paper_fill DROP COLUMN account_id;
ALTER TABLE paper_equity_snapshot DROP COLUMN account_id;

DROP TABLE paper_funding_payment;
DROP TABLE paper_position;
DROP TABLE paper_futures_setting;

ALTER TABLE paper_account RENAME TO legacy_paper_account;
ALTER TABLE paper_transfer RENAME TO legacy_paper_transfer;

-- =============================================================================================
-- 5. The core names, for the tables and everything named after them
-- =============================================================================================

DO $$
DECLARE
    renamed record;
    item record;
BEGIN
    FOR renamed IN
        SELECT * FROM (VALUES
            ('paper_balance', 'wallet_balance'),
            ('paper_ledger_entry', 'wallet_ledger_entry'),
            ('paper_order_list', 'order_list'),
            ('paper_order', 'trade_order'),
            ('paper_fill', 'order_fill'),
            ('paper_equity_snapshot', 'equity_snapshot'),
            ('paper_matching_watermark', 'order_matching_watermark')) AS names(old_name, new_name)
    LOOP
        EXECUTE format('ALTER TABLE %I RENAME TO %I', renamed.old_name, renamed.new_name);
        -- Renaming a primary key or unique constraint renames its index with it.
        FOR item IN
            SELECT c.conname
              FROM pg_constraint c
             WHERE c.conrelid = renamed.new_name::regclass
               AND position(renamed.old_name IN c.conname) > 0
        LOOP
            EXECUTE format('ALTER TABLE %I RENAME CONSTRAINT %I TO %I', renamed.new_name, item.conname,
                           replace(item.conname, renamed.old_name, renamed.new_name));
        END LOOP;
        FOR item IN
            SELECT i.relname
              FROM pg_index x
              JOIN pg_class i ON i.oid = x.indexrelid
             WHERE x.indrelid = renamed.new_name::regclass
               AND position(renamed.old_name IN i.relname) > 0
               AND NOT EXISTS (SELECT 1 FROM pg_constraint c WHERE c.conindid = x.indexrelid)
        LOOP
            EXECUTE format('ALTER INDEX %I RENAME TO %I', item.relname,
                           replace(item.relname, renamed.old_name, renamed.new_name));
        END LOOP;
    END LOOP;
END $$;

-- =============================================================================================
-- 6. The owner's keys, uniques and indexes again; the plan of an order
-- =============================================================================================

ALTER TABLE wallet_balance
    ADD CONSTRAINT fk_wallet_balance_user FOREIGN KEY (user_id) REFERENCES user_account (user_id),
    ADD CONSTRAINT uq_wallet_balance_coin UNIQUE (user_id, wallet_type, coin_id);

ALTER TABLE wallet_ledger_entry
    ADD CONSTRAINT fk_wallet_ledger_entry_user FOREIGN KEY (user_id) REFERENCES user_account (user_id);
CREATE INDEX idx_wallet_ledger_entry_user ON wallet_ledger_entry (user_id, created_at DESC);
-- A coin of a wallet is granted once: the opening, written once whatever two requests race for it.
CREATE UNIQUE INDEX uq_wallet_ledger_entry_grant ON wallet_ledger_entry (user_id, wallet_type, coin_id)
    WHERE entry_type = 'INITIAL_GRANT';
-- A transfer's key names one transfer of the Trader; the debit is the entry that stands for it.
CREATE UNIQUE INDEX uq_wallet_ledger_entry_client_transfer_id ON wallet_ledger_entry (user_id, client_transfer_id)
    WHERE entry_type = 'TRANSFER' AND amount < 0;

ALTER TABLE order_list
    ADD CONSTRAINT fk_order_list_user FOREIGN KEY (user_id) REFERENCES user_account (user_id),
    ADD CONSTRAINT uq_order_list_client_order_id UNIQUE (user_id, list_client_order_id);

ALTER TABLE trade_order ADD COLUMN plan_id uuid;
ALTER TABLE trade_order
    ADD CONSTRAINT fk_trade_order_user FOREIGN KEY (user_id) REFERENCES user_account (user_id),
    ADD CONSTRAINT uq_trade_order_client_order_id UNIQUE (user_id, client_order_id),
    ADD CONSTRAINT fk_trade_order_plan FOREIGN KEY (plan_id) REFERENCES trading_plan (plan_id);
CREATE INDEX idx_trade_order_user ON trade_order (user_id, created_at DESC);
CREATE INDEX idx_trade_order_plan ON trade_order (plan_id);

ALTER TABLE order_fill
    ADD CONSTRAINT fk_order_fill_user FOREIGN KEY (user_id) REFERENCES user_account (user_id);
CREATE INDEX idx_order_fill_user ON order_fill (user_id, traded_at DESC);

ALTER TABLE equity_snapshot
    ADD CONSTRAINT fk_equity_snapshot_user FOREIGN KEY (user_id) REFERENCES user_account (user_id),
    ADD CONSTRAINT uq_equity_snapshot_day UNIQUE (user_id, snapshot_date);

-- =============================================================================================
-- 7. Postconditions: every row and every amount carried over
-- =============================================================================================

DO $$
DECLARE
    expected record;
    actual numeric;
    found bigint;
BEGIN
    FOR expected IN SELECT figure, value FROM pci_expected LOOP
        actual := CASE expected.figure
            WHEN 'balance' THEN (SELECT count(*) FROM wallet_balance)
            WHEN 'ledger' THEN (SELECT count(*) FROM wallet_ledger_entry)
            WHEN 'order_list' THEN (SELECT count(*) FROM order_list)
            WHEN 'order' THEN (SELECT count(*) FROM trade_order)
            WHEN 'fill' THEN (SELECT count(*) FROM order_fill)
            WHEN 'snapshot' THEN (SELECT count(*) FROM equity_snapshot)
            WHEN 'watermark' THEN (SELECT count(*) FROM order_matching_watermark)
            WHEN 'transfer' THEN (SELECT count(*) FROM wallet_ledger_entry
                                   WHERE entry_type = 'TRANSFER' AND amount < 0)
            WHEN 'opened' THEN (SELECT count(DISTINCT user_id) FROM wallet_ledger_entry
                                 WHERE entry_type = 'INITIAL_GRANT')
            WHEN 'balance_total' THEN (SELECT coalesce(sum(free_amount + locked_amount), 0) FROM wallet_balance)
            WHEN 'ledger_total' THEN (SELECT coalesce(sum(amount), 0) FROM wallet_ledger_entry)
        END;
        IF actual IS DISTINCT FROM expected.value THEN
            RAISE EXCEPTION 'V35: % is % after the migration, % before', expected.figure, actual, expected.value;
        END IF;
    END LOOP;

    SELECT count(*) INTO found
      FROM pci_expected_wallets w
      FULL JOIN (
            SELECT b.user_id, b.wallet_type, b.coin_id, b.free_amount, b.locked_amount,
                   (SELECT coalesce(sum(e.amount), 0) FROM wallet_ledger_entry e
                     WHERE e.user_id = b.user_id AND e.wallet_type = b.wallet_type
                       AND e.coin_id = b.coin_id) AS ledger_sum
              FROM wallet_balance b) n
        ON n.user_id = w.user_id AND n.wallet_type = w.wallet_type AND n.coin_id = w.coin_id
     WHERE w.user_id IS NULL OR n.user_id IS NULL
        OR n.free_amount <> w.free_amount OR n.locked_amount <> w.locked_amount OR n.ledger_sum <> w.ledger_sum;
    IF found > 0 THEN
        RAISE EXCEPTION 'V35: % wallet coins differ from before the migration', found;
    END IF;

    SELECT count(*) INTO found
      FROM wallet_ledger_entry
     WHERE entry_type = 'TRANSFER' AND client_transfer_id IS NULL;
    IF found > 0 THEN
        RAISE EXCEPTION 'V35: % transfer entries lost their key', found;
    END IF;
END $$;
