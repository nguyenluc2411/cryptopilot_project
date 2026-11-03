package com.cryptopilot.admin;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Who the accounts named in the audit trail are, for SCR-44. The accounts live in {@code user}, which already depends
 * on this module to write its audit entries, so this module cannot call it; {@code user} implements this interface
 * instead, as the metric sources of the dashboard do ({@link AdminMetricSource}), and the dependency keeps pointing
 * from it to here.
 *
 * <p>Rule: SRS 3.11.9 (each entry shows the actor; the log is filtered by actor).
 */
public interface ActorDirectory {

    /** The accounts among these ids that exist; an id with no account is left out. */
    Map<UUID, ActorSummary> describe(Collection<UUID> accountIds);

    /**
     * The accounts whose email or display name contains this text, case ignored, {@code %} and {@code _} taken
     * literally; at most {@code limit} of them, newest first.
     */
    List<UUID> matching(String text, int limit);

    /**
     * One account named in the audit trail.
     *
     * @param role {@code TRADER} or {@code ADMIN}
     * @param displayName {@code null} when the account has no profile name
     */
    record ActorSummary(UUID accountId, String email, String displayName, String role) {}
}
