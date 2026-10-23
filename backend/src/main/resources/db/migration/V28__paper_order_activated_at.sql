-- When a pending order of an OTO or OTOCO was placed: its working order's fill (TR-10; D-77).
--
-- Live, such an order waits from that fill, but the instant was not stored, so after a restart the matching engine
-- rebuilt it from its creation and a replayed candle before the fill could reach it. Only an order of a group is
-- ever activated. The orders activated before this migration take their working order's close time, when it filled;
-- the others stay null and keep their creation.

ALTER TABLE paper_order ADD COLUMN activated_at timestamptz;

ALTER TABLE paper_order
    ADD CONSTRAINT ck_paper_order_activated_in_list CHECK (activated_at IS NULL OR list_id IS NOT NULL);

UPDATE paper_order leg
   SET activated_at = working.closed_at
  FROM paper_order working
 WHERE leg.list_id IS NOT NULL
   AND working.list_id = leg.list_id
   AND working.client_order_id LIKE '%:w'
   AND working.order_status = 'FILLED'
   AND leg.order_id <> working.order_id
   -- Still pending, or refused when the fill came: never activated.
   AND leg.order_status NOT IN ('PENDING_NEW', 'REJECTED');
