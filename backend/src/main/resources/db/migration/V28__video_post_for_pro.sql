-- D-99 (the registered topic): video posts are for Traders with an active paid package, so PRO joins PREMIUM.
-- The entitlement is a value of the tier (BR-62), maintained on every package of it.
UPDATE subscription_package SET can_post_video = true WHERE tier = 'PRO';
