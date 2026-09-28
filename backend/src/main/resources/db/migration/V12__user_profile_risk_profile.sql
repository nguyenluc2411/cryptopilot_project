-- The risk profile on the user profile (T-103; SRS v1.1 3.2.5, BR-66; D-53).
--
-- CONSERVATIVE, BALANCED or AGGRESSIVE. It sizes positions and sets warning thresholds only; it is never an input of
-- the setup score (BR-13). New Traders start on BALANCED, and so does every profile that exists when this runs:
-- the default fills them, and NOT NULL keeps a profile from ever having none.
--
-- The parameters of each profile (risk per trade, maximum leverage, maximum total open risk) are configuration, not
-- columns: every user of a profile shares them and nobody can customise them in this release (BR-66).
--
-- Written so that running it again changes nothing: the column and the constraint are only added when missing.

ALTER TABLE user_profile ADD COLUMN IF NOT EXISTS risk_profile varchar(32) NOT NULL DEFAULT 'BALANCED';

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_user_profile_risk_profile') THEN
        ALTER TABLE user_profile
            ADD CONSTRAINT ck_user_profile_risk_profile
                CHECK (risk_profile IN ('CONSERVATIVE', 'BALANCED', 'AGGRESSIVE'));
    END IF;
END
$$;
