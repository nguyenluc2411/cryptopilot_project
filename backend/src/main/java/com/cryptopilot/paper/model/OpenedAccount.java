package com.cryptopilot.paper.model;

import com.cryptopilot.paper.dto.response.PaperAccountResponse;
import java.util.Objects;

/**
 * The result of opening a paper account: the account, and whether this call opened it or found it open already.
 *
 * @param account the caller's account
 * @param created whether this call opened it and granted its funds
 */
public record OpenedAccount(PaperAccountResponse account, boolean created) {

    public OpenedAccount {
        Objects.requireNonNull(account, "account");
    }
}
