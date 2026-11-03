-- The registered topic: every Trader views the Futures data (mark price, funding rate, open interest, Long/Short
-- context); a package unlocks only the AI Assistant and video posts. FREE joins PRO and PREMIUM on FUTURES_ANALYSIS,
-- so the Futures analysis, its plans and its alerts are no longer refused with MSG29 on the FREE plan.
-- The entitlement is a value of the tier (BR-62), maintained on every package of it.
UPDATE subscription_package SET futures_analysis = true WHERE tier = 'FREE';
