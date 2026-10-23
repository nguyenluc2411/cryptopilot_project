package com.cryptopilot.paper.repository;

import com.cryptopilot.paper.entity.PaperOrderList;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The gateway to the groups of paper orders placed together.
 *
 * <p>Rule: TR-10.
 */
public interface PaperOrderListRepository extends Repository<PaperOrderList, UUID> {

    /** The account's group with this id, or empty. */
    @Transactional(readOnly = true)
    Optional<PaperOrderList> findByIdAndAccountId(UUID id, UUID accountId);

    /** The group with this id, or empty. */
    @Transactional(readOnly = true)
    Optional<PaperOrderList> findById(UUID id);

    /** The account's group with this idempotency key, or empty. */
    Optional<PaperOrderList> findByAccountIdAndListClientOrderId(UUID accountId, String listClientOrderId);

    /** Writes a group. Not transactional here; the unit of work is the calling service's. */
    PaperOrderList save(PaperOrderList list);
}
