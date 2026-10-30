-- TR-24 (plan 4.3 rule (B)): returned_to_cross_amount (V57) for the isolated positions that existed before it, worked
-- out from what their reductions, closes and liquidations recorded, as FuturesSettlement now adds it at each of them:
-- per event the cross wallet got the realised profit or loss, less the liquidation fee, with the insurance cover of a
-- loss beyond the wallet, and the share the isolated wallet gave up. Late funding fees paid before this migration are
-- not counted: it was not recorded which ones the cross wallet paid.
UPDATE futures_position p
SET returned_to_cross_amount = p.realized_pnl_amount - p.liquidation_fee_amount + COALESCE(
        (SELECT SUM(-a.amount) + COALESCE(SUM(l.amount), 0)
           FROM futures_margin_adjustment a
           LEFT JOIN wallet_ledger_entry l ON a.ref_type = 'LEDGER' AND l.entry_id = a.ref_id
          WHERE a.position_id = p.position_id
            AND a.adjustment_type IN ('RELEASE', 'LOSS_ABSORB', 'CLOSE', 'LIQUIDATION')),
        0)
WHERE p.margin_type = 'ISOLATED';
