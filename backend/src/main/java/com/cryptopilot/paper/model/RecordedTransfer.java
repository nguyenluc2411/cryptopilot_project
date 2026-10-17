package com.cryptopilot.paper.model;

import com.cryptopilot.paper.dto.response.TransferResponse;
import java.util.Objects;

/**
 * The result of a transfer request: the transfer, and whether this request made it or found it made already under
 * the same idempotency key.
 *
 * @param transfer the transfer
 * @param created whether this request moved the amount
 */
public record RecordedTransfer(TransferResponse transfer, boolean created) {

    public RecordedTransfer {
        Objects.requireNonNull(transfer, "transfer");
    }
}
