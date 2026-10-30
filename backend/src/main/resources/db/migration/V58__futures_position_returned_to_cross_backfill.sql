-- TR-24 (plan 4.3 rule (B)): returned_to_cross_amount (V57) for the isolated positions that existed before it, worked
-- out from what their reductions, closes and liquidations recorded, as FuturesSettlement now adds it at each of them:
-- the cross wallet got their realised profit or loss, less the liquidation fee, with the insurance covers of their
-- losses beyond the wallet, and the share the isolated wallet gave up. The covers of the position are its insurance
-- covers but those of funding paid while it was open, which its FUNDING adjustments name. A row the code of V57 already
-- set is kept. Late funding fees paid before this migration are not counted: which ones the cross wallet paid was not
-- recorded.
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
                       AND NOT EXISTS (SELECT 1
                                         FROM futures_margin_adjustment f
                                        WHERE f.position_id = p.position_id
                                          AND f.adjustment_type = 'FUNDING'
                                          AND f.ref_type = 'LEDGER'
                                          AND f.ref_id = l.entry_id)), 0)
WHERE p.margin_type = 'ISOLATED'
  AND p.returned_to_cross_amount = 0;
