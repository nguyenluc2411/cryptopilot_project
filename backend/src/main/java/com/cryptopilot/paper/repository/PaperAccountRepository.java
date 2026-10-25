package com.cryptopilot.paper.repository;

import com.cryptopilot.paper.model.PaperAccount;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The paper accounts, read from the INITIAL_GRANT entries of {@code wallet_ledger_entry}: a Trader's account is open
 * once its funds were granted, and the first of those entries is its opening. {@code uq_wallet_ledger_entry_grant}
 * leads with {@code user_id}, so every lookup here is an index read.
 *
 * <p>Plain SQL through {@link JdbcClient}: an account is not an entity of its own (D-117).
 *
 * <p>Rule: TR-04, TR-13; D-117.
 */
@Repository
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class PaperAccountRepository {

    private final JdbcClient sql;

    /** The Trader's account, or empty when it was never opened. */
    public Optional<PaperAccount> findByUserId(UUID userId) {
        return sql.sql("select created_at from wallet_ledger_entry where user_id = ? and entry_type = 'INITIAL_GRANT'"
                        + " order by created_at limit 1")
                .param(userId)
                .query(Instant.class)
                .optional()
                .map(openedAt -> new PaperAccount(userId, openedAt));
    }

    /** The Trader of every account without a snapshot of the day, for the daily equity snapshot (TR-13; PT-R02). */
    public List<UUID> findIdsWithoutSnapshotOn(LocalDate day) {
        return sql.sql("""
                        select distinct e.user_id from wallet_ledger_entry e
                         where e.entry_type = 'INITIAL_GRANT'
                           and not exists (select 1 from equity_snapshot s
                                            where s.user_id = e.user_id and s.snapshot_date = ?)
                         order by e.user_id""").param(day).query(UUID.class).list();
    }
}
