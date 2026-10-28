package com.cryptopilot.community.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The influence and analytical depth scores of a member, computed from fixed numbers rather than from the formula
 * under test.
 *
 * <p>Rule: D-125.
 */
class AuthorStandingTest {

    @Test
    void D125_aMemberWithNothing_scoresZero() {
        assertThat(AuthorStanding.NONE.influence()).isZero();
        assertThat(AuthorStanding.NONE.depth()).isZero();
    }

    @Test
    void D125_oneOfEach_scores90And80TimesLn2() {
        AuthorStanding standing = new AuthorStanding(1, 1, 1, 1, 1, 0, 0, false);

        assertThat(standing.influence()).isEqualTo(62.38);
        assertThat(standing.depth()).isEqualTo(55.45);
    }

    @Test
    void D125_theScoresAreRoundedToTwoDecimals_halfUp() {
        // 40 * ln 3 = 43.9445 and 25 * ln 4 = 34.6574.
        assertThat(new AuthorStanding(2, 0, 0, 0, 0, 0, 0, false).influence()).isEqualTo(43.94);
        assertThat(new AuthorStanding(0, 3, 0, 0, 0, 0, 0, false).influence()).isEqualTo(34.66);
        // 50 * ln 4 = 69.3147.
        assertThat(new AuthorStanding(0, 0, 0, 0, 3, 0, 0, false).depth()).isEqualTo(69.31);
    }

    @Test
    void D125_theVotesAndTheFollowOfTheCaller_doNotCount() {
        AuthorStanding plain = new AuthorStanding(1, 1, 1, 1, 1, 0, 0, false);
        AuthorStanding voted = new AuthorStanding(1, 1, 1, 1, 1, 7, 3, true);

        assertThat(voted.influence()).isEqualTo(plain.influence());
        assertThat(voted.depth()).isEqualTo(plain.depth());
    }
}
