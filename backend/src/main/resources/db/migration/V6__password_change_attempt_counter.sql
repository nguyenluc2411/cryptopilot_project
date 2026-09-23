-- A counter of consecutive wrong current passwords on the Security tab (SRS 3.2.5, UC-07).
--
-- Additive, in its own version, for the reason V4 gives: an applied migration is never edited.
--
-- Why a second counter rather than the BR-03 one: BR-03 counts failed sign-ins and locks the account
-- for fifteen minutes, and a wrong current password is not a failed sign-in - the caller already holds
-- a session. Counting it there would let anybody holding a stolen access token lock the real owner
-- out of signing in. Not counting it at all left the endpoint open to guessing for as long as the
-- token lives. So it is counted here, against the same threshold, and what the fifth wrong answer
-- does is end every session of the account rather than lock the sign-in.
--
-- State of the account, not of a session: it has to survive the request that incremented it, which
-- ends in a refusal, and it has to survive a restart - so a column, like failed_login_count.

ALTER TABLE user_account
    ADD COLUMN failed_password_change_count integer NOT NULL DEFAULT 0;

-- Never negative, stated by the database as well as the entity, as for failed_login_count.
ALTER TABLE user_account
    ADD CONSTRAINT ck_user_account_failed_password_change_count CHECK (failed_password_change_count >= 0);
