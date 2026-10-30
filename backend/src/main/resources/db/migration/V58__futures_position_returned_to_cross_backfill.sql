-- TR-24 (plan 4.3 rule (B)): returned_to_cross_amount (V57) of every isolated position, worked out from what its
-- reductions, closes and liquidation recorded, as FuturesSettlement adds it at each of them: the cross wallet got their
-- realised profit or loss, less the liquidation fee, with the insurance covers of their losses beyond the wallet, and
-- the share the isolated wallet gave up. A cover of a reduction or close is the one its adjustment names or, when the
-- wallet had nothing left to change, the one written with a losing fill of the position; covers of funding are neither.
-- Late funding fees paid before this migration are not counted: which ones the cross wallet paid was not recorded.
UPDATE futures_position p
SET returned_to_cross_amount = p.realized_pnl_amount - p.liquidation_fee_amount
        + COALESCE((SELECT SUM(-a.amount)
                      FROM futures_margin_adjustment a
                     WHERE a.position_id = p.position_id
                       AND a.adjustment_type IN ('RELEASE', 'LOSS_ABSORB', 'CLOSE', 'LIQUIDATION')), 0)
        + COALESCE((SELECT SUM(l.amount)
                      FROM wallet_ledger_entry l
                     WHERE l.entry_type = 'INSURANCE_COVER'
                       AND l.ref_type = 'POSITION'
                       AND l.ref_id = p.position_id
                       AND (EXISTS (SELECT 1
                                      FROM futures_margin_adjustment c
                                     WHERE c.position_id = p.position_id
                                       AND c.adjustment_type IN ('RELEASE', 'LOSS_ABSORB', 'CLOSE', 'LIQUIDATION')
                                       AND c.ref_type = 'LEDGER'
                                       AND c.ref_id = l.entry_id)
                            OR (NOT EXISTS (SELECT 1
                                              FROM futures_margin_adjustment n
                                             WHERE n.ref_type = 'LEDGER'
                                               AND n.ref_id = l.entry_id)
                                AND EXISTS (SELECT 1
                                              FROM order_fill f
                                             WHERE f.position_id = p.position_id
                                               AND f.realized_pnl_amount < 0
                                               AND f.created_at BETWEEN l.created_at - interval '1 second'
                                                                    AND l.created_at + interval '1 second')))), 0)
WHERE p.margin_type = 'ISOLATED';
