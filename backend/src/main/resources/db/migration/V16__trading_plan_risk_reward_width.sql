-- risk_reward_ratio was numeric(12,8), so a ratio of 10000 or more could not be stored: a far take profit with a
-- near stop loss is a valid plan (BR-22, BR-24) and its save failed. numeric(20,8) holds ratios below 10^12, far
-- beyond any plan a pair's tick size allows in practice. Forward-only; V1 is not edited (T-039).

ALTER TABLE trading_plan ALTER COLUMN risk_reward_ratio TYPE numeric(20, 8);
