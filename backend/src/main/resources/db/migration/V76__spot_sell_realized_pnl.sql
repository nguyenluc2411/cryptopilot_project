-- TR-28: a Spot sale records what it realised, so the PnL analysis can count the Spot sales won and lost as it counts
-- the Futures positions. realized_pnl_amount of a Spot fill is the USDT it netted (proceeds less the fee) less the
-- share of the coin's average cost it took (BR-73); NULL on a buy, and on a sale whose cost was not known (D-96).
-- Until now only a Futures fill could carry it; a Futures fill still must have its position side.
ALTER TABLE order_fill DROP CONSTRAINT ck_order_fill_market_fields;
ALTER TABLE order_fill
    ADD CONSTRAINT ck_order_fill_market_fields CHECK (
        (market_type = 'SPOT' AND position_side IS NULL)
        OR (market_type = 'FUTURES' AND position_side IS NOT NULL));
