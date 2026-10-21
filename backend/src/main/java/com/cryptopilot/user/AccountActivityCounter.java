package com.cryptopilot.user;

import java.util.Map;
import java.util.UUID;

/**
 * Counts what a user did in a module, for the account detail of SCR-35 ("counts of plans, trades and posts"). The
 * plans, trades and posts belong to modules that depend on this one, so each implements this interface as a bean and
 * the account administration asks every one of them; the dependency points from them to here, never back.
 *
 * <p>Rule: SRS 3.11.2; UC-42.
 */
public interface AccountActivityCounter {

    /** Named counts of the user's records in this module, e.g. {@code plans → 12}. */
    Map<String, Long> count(UUID userId);
}
