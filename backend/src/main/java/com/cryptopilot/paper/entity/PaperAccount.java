package com.cryptopilot.paper.entity;

import com.cryptopilot.common.entity.BaseEntity;
import com.cryptopilot.paper.model.enums.PositionMode;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;
import lombok.Getter;

/**
 * A Trader's paper trading account: the root that owns the wallets, orders and positions of the simulated exchange.
 * One per Trader ({@code uq_paper_account_user}), opened when the Trader first enters the trading screens, with the
 * virtual funds granted once. Nothing grants them again: there is no reset (Q-T5 as revised on 2026-10-06).
 *
 * <p>Rule: TR-04; Q-T4, Q-T5.
 */
@Getter
@Entity
@Table(name = "paper_account")
@AttributeOverride(name = "id", column = @Column(name = "account_id", nullable = false, updatable = false))
public class PaperAccount extends BaseEntity {

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "position_mode", nullable = false, length = 32)
    private PositionMode positionMode;

    /** For JPA only. */
    protected PaperAccount() {}

    private PaperAccount(UUID userId) {
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.positionMode = PositionMode.ONE_WAY;
    }

    /** A new account, in one-way mode as Binance opens one (Q-T4). */
    public static PaperAccount open(UUID userId) {
        return new PaperAccount(userId);
    }
}
