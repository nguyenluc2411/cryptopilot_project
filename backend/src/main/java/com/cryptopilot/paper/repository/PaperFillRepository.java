package com.cryptopilot.paper.repository;

import com.cryptopilot.paper.entity.PaperFill;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The gateway to the fills of paper orders: written once per execution, read as the trade history.
 *
 * <p>Rule: TR-02.
 */
public interface PaperFillRepository extends Repository<PaperFill, UUID> {

    /** One page of an account's fills traded in {@code [from, to)}, newest first. No parameter is null. */
    @Transactional(readOnly = true)
    @Query(
            value = "select f from PaperFill f where f.accountId = :accountId"
                    + " and f.tradedAt >= :from and f.tradedAt < :to order by f.tradedAt desc, f.id desc",
            countQuery = "select count(f) from PaperFill f where f.accountId = :accountId"
                    + " and f.tradedAt >= :from and f.tradedAt < :to")
    Page<PaperFill> search(UUID accountId, Instant from, Instant to, Pageable pageable);

    /** As {@link #search}, for one pair. */
    @Transactional(readOnly = true)
    @Query(
            value = "select f from PaperFill f where f.accountId = :accountId and f.pairId = :pairId"
                    + " and f.tradedAt >= :from and f.tradedAt < :to order by f.tradedAt desc, f.id desc",
            countQuery = "select count(f) from PaperFill f where f.accountId = :accountId and f.pairId = :pairId"
                    + " and f.tradedAt >= :from and f.tradedAt < :to")
    Page<PaperFill> searchPair(UUID accountId, UUID pairId, Instant from, Instant to, Pageable pageable);

    /** Writes a fill. Not transactional here; the unit of work is the calling service's. */
    PaperFill save(PaperFill fill);
}
