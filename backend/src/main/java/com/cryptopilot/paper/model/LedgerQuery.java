package com.cryptopilot.paper.model;

import com.cryptopilot.paper.model.enums.LedgerEntryType;
import com.cryptopilot.paper.model.enums.WalletType;
import java.time.Instant;

/**
 * The filters and the page of the transaction history. Every filter is optional.
 *
 * @param wallet the wallet, or {@code null} for both
 * @param type the kind of change, or {@code null} for every kind
 * @param from the earliest instant, inclusive, or {@code null}
 * @param to the latest instant, exclusive, or {@code null}
 * @param page the page, from 1, or {@code null} for 1
 * @param pageSize 1 to 100, or {@code null} for 20
 */
public record LedgerQuery(
        WalletType wallet, LedgerEntryType type, Instant from, Instant to, Integer page, Integer pageSize) {}
