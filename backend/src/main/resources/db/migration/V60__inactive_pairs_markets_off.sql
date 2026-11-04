-- UC-45: an INACTIVE pair has both market switches off and no Futures ceiling, as CryptoPair.changeStatus now keeps
-- it. Before, the switches were kept while the pair was inactive, so the console showed a market as on that no
-- Trader could see. The pairs inactive today are brought in line; they were offered on no market already, so nothing
-- a Trader sees changes.
UPDATE crypto_pair
SET is_spot_enabled = false,
    is_futures_enabled = false,
    max_leverage = NULL,
    updated_at = now()
WHERE pair_status = 'INACTIVE'
  AND (is_spot_enabled OR is_futures_enabled OR max_leverage IS NOT NULL);
