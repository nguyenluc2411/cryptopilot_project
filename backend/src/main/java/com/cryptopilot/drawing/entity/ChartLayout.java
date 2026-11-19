package com.cryptopilot.drawing.entity;

import com.cryptopilot.common.entity.BaseEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;
import lombok.Getter;

/**
 * One of the Trader's layouts on the chart of a pair: a set of drawings the Trader switches between. Its number never
 * changes; its name is optional, and the chart shows "Copy n" while there is none.
 *
 * <p>Rule: D-149; T-119.
 */
@Getter
@Entity
@Table(name = "chart_layout")
@AttributeOverride(name = "id", column = @Column(name = "layout_id", nullable = false, updatable = false))
public class ChartLayout extends BaseEntity {

    /** Longest name the column holds. */
    public static final int NAME_MAX_LENGTH = 60;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "pair_id", nullable = false, updatable = false)
    private UUID pairId;

    /** The layout's number among the Trader's layouts on the pair, from 1. */
    @Column(name = "seq_no", nullable = false, updatable = false)
    private int seqNo;

    /** The name the Trader gave it, or {@code null} for none. */
    @Column(name = "layout_name", length = NAME_MAX_LENGTH)
    private String name;

    /** For JPA only. */
    protected ChartLayout() {}

    private ChartLayout(UUID userId, UUID pairId, int seqNo) {
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.pairId = Objects.requireNonNull(pairId, "pairId must not be null");
        if (seqNo < 1) {
            throw new IllegalArgumentException("seqNo must be at least 1, was " + seqNo);
        }
        this.seqNo = seqNo;
    }

    /** A new layout of the Trader on the pair, numbered {@code seqNo}. */
    public static ChartLayout open(UUID userId, UUID pairId, int seqNo, String name) {
        ChartLayout layout = new ChartLayout(userId, pairId, seqNo);
        layout.rename(name);
        return layout;
    }

    /** Gives the layout a name; a blank name removes it, so the chart shows the number again. */
    public void rename(String name) {
        this.name = name == null || name.isBlank() ? null : name.strip();
    }
}
