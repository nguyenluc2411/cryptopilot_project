package com.cryptopilot.market;

/**
 * How far a module has caught up with a recovering market (D-158): of the pairs it runs on the market's prices, how
 * many have replayed the missed data up to now.
 *
 * @param pairsDone the pairs caught up
 * @param pairsTotal the pairs it runs on the market's prices
 */
public record RecoveryProgress(int pairsDone, int pairsTotal) {

    /** Nothing to catch up on. */
    public static final RecoveryProgress NOTHING = new RecoveryProgress(0, 0);

    /** No answer came: counted as one pair not caught up. */
    public static final RecoveryProgress NOT_ANSWERED = new RecoveryProgress(0, 1);

    public RecoveryProgress {
        if (pairsDone < 0 || pairsDone > pairsTotal) {
            throw new IllegalArgumentException(pairsDone + " of " + pairsTotal + " pairs");
        }
    }

    /** Whether every pair has caught up. */
    public boolean done() {
        return pairsDone == pairsTotal;
    }

    /** This progress and another's, added up. */
    public RecoveryProgress plus(RecoveryProgress other) {
        return new RecoveryProgress(pairsDone + other.pairsDone, pairsTotal + other.pairsTotal);
    }
}
