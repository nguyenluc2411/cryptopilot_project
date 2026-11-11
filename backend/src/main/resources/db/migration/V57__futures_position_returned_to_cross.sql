-- TR-24 (plan 4.3 rule (B)): what an isolated position's closing fills and liquidation gave back to the cross wallet,
-- less the late funding fees paid from it since. A fee settled after the position closed is paid from the cross wallet
-- only up to this amount, as if the isolated wallet had paid it before giving the rest back; the rest is an insurance
-- cover. Always zero for a cross position.
ALTER TABLE futures_position
    ADD COLUMN returned_to_cross_amount numeric(28, 8) NOT NULL DEFAULT 0;
