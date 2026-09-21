-- Exactly the refresh tokens carry a family.
--
-- Why this is not in V4, where the column arrived: a constraint is only additive if the code that
-- writes the table already satisfies it. Until the mapping carried a family, every refresh token the
-- application could build had none, so this rule would have refused the one kind of row it exists to
-- describe -- the same mistake as an entity that runs ahead of its column, with the two ends swapped.
-- V4 adds the column, the mapping learns to fill it, and then this says what filling it means.
--
-- Written as an equality between two predicates rather than as two separate checks, so it fails in
-- both directions: a refresh token with no family cannot be revoked together with its siblings, and
-- a verification or reset link that was given one would be swept away by a revocation that has
-- nothing to do with it. Neither is a state any use case should be able to reach.
--
-- Rule: TECHNICAL_DESIGN 7.15; A-10.

ALTER TABLE user_token
    ADD CONSTRAINT ck_user_token_family
        CHECK ((token_type = 'REFRESH') = (token_family_id IS NOT NULL));
