package com.cryptopilot.admin;

/**
 * What the {@code admin} module offers other modules for the audit trail: one call that records an administrative or
 * security-relevant action in {@code audit_log}. This module owns the table and is the only one that writes it; the
 * module whose command is audited calls this from inside that command.
 *
 * <p>Rule: BR-57, NSF-18; TECHNICAL_DESIGN sections 2 and 10 (the audit entry is written in the command transaction).
 */
public interface AdminApi {

    /**
     * Records one audit entry in the caller's transaction. The entry is committed with the command and rolled back
     * with it, and a failure to write it fails the command. There is no transaction of its own to fall back on.
     *
     * @throws org.springframework.transaction.IllegalTransactionStateException when called outside a transaction
     */
    void audit(AuditEntry entry);
}
