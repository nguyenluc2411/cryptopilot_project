package com.cryptopilot.paper.entity;

import com.cryptopilot.common.entity.BaseEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import lombok.Getter;

/**
 * What a paper account was worth at the start of a day, in USDT (TR-13): the base of the day's PnL and a point of the
 * asset chart. One per account and day ({@code uq_equity_snapshot_day}); never changed once taken.
 *
 * <p>Rule: TR-13.
 */
@Getter
@Entity
@Table(name = "equity_snapshot")
@AttributeOverride(name = "id", column = @Column(name = "snapshot_id", nullable = false, updatable = false))
public class PaperEquitySnapshot extends BaseEntity {

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "snapshot_date", nullable = false, updatable = false)
    private LocalDate snapshotDate;

    @Column(name = "spot_equity_amount", nullable = false, precision = 28, scale = 8, updatable = false)
    private BigDecimal spotEquityAmount;

    @Column(name = "futures_equity_amount", nullable = false, precision = 28, scale = 8, updatable = false)
    private BigDecimal futuresEquityAmount;

    @Column(name = "total_equity_amount", nullable = false, precision = 28, scale = 8, updatable = false)
    private BigDecimal totalEquityAmount;

    /** For JPA only. */
    protected PaperEquitySnapshot() {}

    private PaperEquitySnapshot(UUID userId, LocalDate day, BigDecimal spot, BigDecimal futures) {
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.snapshotDate = Objects.requireNonNull(day, "day must not be null");
        this.spotEquityAmount = Objects.requireNonNull(spot, "spot must not be null");
        this.futuresEquityAmount = Objects.requireNonNull(futures, "futures must not be null");
        this.totalEquityAmount = spot.add(futures);
    }

    /** The worth of an account's two wallets at the start of {@code day}. */
    public static PaperEquitySnapshot of(UUID userId, LocalDate day, BigDecimal spot, BigDecimal futures) {
        return new PaperEquitySnapshot(userId, day, spot, futures);
    }
}
