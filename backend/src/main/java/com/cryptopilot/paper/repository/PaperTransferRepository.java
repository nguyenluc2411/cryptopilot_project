package com.cryptopilot.paper.repository;

import com.cryptopilot.paper.entity.PaperTransfer;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The gateway to the transfers between paper wallets.
 *
 * <p>Rule: TR-04.
 */
public interface PaperTransferRepository extends Repository<PaperTransfer, UUID> {

    /** One page of an account's transfers, as the pageable orders them. */
    @Transactional(readOnly = true)
    Page<PaperTransfer> findByAccountId(UUID accountId, Pageable page);

    /** The account's transfer with this idempotency key, or empty. */
    @Transactional(readOnly = true)
    Optional<PaperTransfer> findByAccountIdAndClientTransferId(UUID accountId, String clientTransferId);

    /** Writes a transfer. Not transactional here; the unit of work is the calling service's. */
    PaperTransfer save(PaperTransfer transfer);
}
