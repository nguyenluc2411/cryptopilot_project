-- The three columns the login rules need and the schema does not have (A-10).
--
-- Additive only, and in its own version: V1, V2 and V3 are applied everywhere this project runs and
-- a migration that has been applied is never edited — Flyway records a checksum of it, so editing
-- one makes every existing database refuse to start.
--
-- What arrives here and why the logical model has none of it:
--   * user_account.failed_login_count and user_account.locked_until. BR-03 counts consecutive
--     failures and locks the account for fifteen minutes at the fifth, and neither the counter nor
--     the instant the lock ends has anywhere to live. Both are state of the account, not of a
--     session: the count has to survive the request that incremented it, and the lock has to
--     survive a restart, so neither can be held in memory or in a cache.
--   * user_token.token_family_id. Refresh rotation (TECHNICAL_DESIGN 7.15) issues a successor on
--     every use and revokes the whole family when a retired token is presented again. "The whole
--     family" needs a name for the chain, and a chain of parent pointers would have to be walked
--     row by row to revoke it; one identifier shared by every token a single sign-in ever produces
--     revokes it in one statement.
--
-- The family column is NULLABLE, and no backfill is defined, because nothing needs one. A family
-- belongs to a refresh token and to nothing else: an email verification link and a password reset
-- link are issued once, are never rotated and have no successor, so for those two kinds the column
-- is not "not yet filled in" but "does not apply". Every refresh token there will ever be is issued
-- by the code this migration precedes, so there is no historical row to fill either: on any database
-- this applies to, the REFRESH rows number zero.
--
-- Stating "does not apply" as a constraint is worth doing and is deliberately NOT done here. It
-- would refuse a refresh token that carries no family, and until the entity of the next step carries
-- one, every refresh token the application can build is such a token — so the constraint would be a
-- rule the code cannot satisfy, which is the same mistake as an entity ahead of its column with the
-- two ends swapped. It arrives in V5, immediately after the mapping that upholds it.

ALTER TABLE user_account
    ADD COLUMN failed_login_count integer NOT NULL DEFAULT 0,
    ADD COLUMN locked_until       timestamptz;

-- A count of attempts is never negative. The database says so as well as the entity, because a
-- decrement written by a later task would otherwise be discovered by a user who cannot be locked out.
ALTER TABLE user_account
    ADD CONSTRAINT ck_user_account_failed_login_count CHECK (failed_login_count >= 0);

ALTER TABLE user_token
    ADD COLUMN token_family_id uuid;

-- Revoking a family is one statement over this index. Without it, reuse detection -- the path that
-- runs exactly when somebody is holding a copied token -- would scan a table that grows with every
-- sign-in of every account.
CREATE INDEX idx_user_token_family ON user_token (token_family_id);
