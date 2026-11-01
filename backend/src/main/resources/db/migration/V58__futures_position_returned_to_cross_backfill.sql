-- TR-24 (plan 4.3 rule (B)): returned_to_cross_amount (V57) of every isolated position, worked out from what its
-- reductions, closes and liquidation recorded, as FuturesSettlement adds it at each of them: the cross wallet got their
-- realised profit or loss, less the liquidation fee, with the insurance covers their adjustments name, and the share
-- the isolated wallet gave up. A cover written when the wallet had nothing left to change has no adjustment and is not
-- counted, which leaves the amount lower; late funding fees paid since V57 are not subtracted, which leaves it higher.
-- On a shared database (V48 to V58 in one run) there is no Futures row yet and this changes nothing.
UPDATE futures_position p
-- GREATEST: a cover with no adjustment is not counted, so a loss it paid would leave the sum below zero.
SET returned_to_cross_amount = GREATEST(p.realized_pnl_amount - p.liquidation_fee_amount
        + COALESCE((SELECT SUM(-a.amount)
                      FROM futures_margin_adjustment a
                     WHERE a.position_id = p.position_id
                       AND a.adjustment_type IN ('RELEASE', 'LOSS_ABSORB', 'CLOSE', 'LIQUIDATION')), 0)
        + COALESCE((SELECT SUM(l.amount)
                      FROM futures_margin_adjustment c
                      JOIN wallet_ledger_entry l ON l.entry_id = c.ref_id
                     WHERE c.position_id = p.position_id
                       AND c.adjustment_type IN ('RELEASE', 'LOSS_ABSORB', 'CLOSE', 'LIQUIDATION')
                       AND c.ref_type = 'LEDGER'
                       AND l.entry_type = 'INSURANCE_COVER'), 0), 0)
WHERE p.margin_type = 'ISOLATED';

ALTER TABLE futures_position ADD CONSTRAINT ck_futures_position_returned_to_cross
    CHECK (returned_to_cross_amount >= 0
           AND (margin_type = 'ISOLATED' OR returned_to_cross_amount = 0));
