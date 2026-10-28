package com.cryptopilot.paper.entity;

import com.cryptopilot.common.entity.BaseEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.Getter;

/**
 * The Futures settings of one Trader, keyed by the Trader: the position mode, and whether a cross liquidation is
 * running. Created before the Trader's first Futures action. Its {@code version} is the Futures account version:
 * every change of the Futures wallet, a position or an isolated wallet touches the row, so a snapshot cached by the
 * engine knows when it is stale.
 *
 * <p>Rule: TR-14, TR-23; D-127.
 */
@Getter
@Entity
@Table(name = "futures_account_config")
@AttributeOverride(name = "id", column = @Column(name = "user_id", nullable = false, updatable = false))
public class FuturesAccountConfig extends BaseEntity {

    /** Hedge mode (a long and a short position per pair) when true, one-way mode when false. */
    @Column(name = "dual_side_position", nullable = false)
    private boolean dualSidePosition;

    /** When the running cross liquidation started, or {@code null} when none runs. */
    @Column(name = "liquidating_since")
    private Instant liquidatingSince;

    /** For JPA only. */
    protected FuturesAccountConfig() {}

    private FuturesAccountConfig(UUID userId) {
        super(userId);
    }

    /** The settings of a Trader who has none yet: one-way mode, no liquidation running. */
    public static FuturesAccountConfig defaults(UUID userId) {
        return new FuturesAccountConfig(Objects.requireNonNull(userId, "userId must not be null"));
    }

    /** The Trader this row belongs to. */
    public UUID userId() {
        return getId();
    }

    /** Whether a cross liquidation of the account is running, during which orders and changes are refused. */
    public boolean liquidating() {
        return liquidatingSince != null;
    }
}
