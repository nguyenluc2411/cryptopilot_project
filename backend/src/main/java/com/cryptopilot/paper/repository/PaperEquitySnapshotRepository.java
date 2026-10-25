package com.cryptopilot.paper.repository;

import com.cryptopilot.paper.entity.PaperEquitySnapshot;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The gateway to the daily equity snapshots of the paper accounts.
 *
 * <p>Rule: TR-13.
 */
public interface PaperEquitySnapshotRepository extends Repository<PaperEquitySnapshot, UUID> {

    /** The account's snapshot of a day, or empty. */
    @Transactional(readOnly = true)
    Optional<PaperEquitySnapshot> findByUserIdAndSnapshotDate(UUID userId, LocalDate day);

    /** The account's snapshots from a day on, oldest first. */
    @Transactional(readOnly = true)
    List<PaperEquitySnapshot> findByUserIdAndSnapshotDateGreaterThanEqualOrderBySnapshotDateAsc(
            UUID userId, LocalDate from);

    /** Writes a snapshot. Not transactional here; the unit of work is the calling service's. */
    PaperEquitySnapshot save(PaperEquitySnapshot snapshot);
}
