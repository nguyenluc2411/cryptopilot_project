package com.cryptopilot.watchlist.entity;

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
 * One pair a Trader watches, with the label it is grouped under, its place in the list and a note (SRS 3.4.1). A pair
 * appears once per Trader, which {@code uq_watchlist_user_pair} enforces. The Trader and the pair are held by id: both
 * belong to other modules. Deleting the row deletes its alerts through {@code fk_alert_watchlist} (BR-16).
 *
 * <p>Rule: BR-15, BR-16; UC-12.
 */
@Getter
@Entity
@Table(name = "watchlist")
@AttributeOverride(name = "id", column = @Column(name = "watchlist_id", nullable = false, updatable = false))
public class Watchlist extends BaseEntity {

    /** Longest label the column holds. */
    public static final int LABEL_MAX_LENGTH = 100;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "pair_id", nullable = false, updatable = false)
    private UUID pairId;

    /** The group the row is shown under, or {@code null} for none. */
    @Column(name = "label", length = LABEL_MAX_LENGTH)
    private String label;

    /** Position in the Trader's list, smallest first. */
    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "note")
    private String note;

    @Column(name = "added_at", nullable = false, updatable = false)
    private Instant addedAt;

    /** For JPA only. */
    protected Watchlist() {}

    private Watchlist(UUID userId, UUID pairId, String label, String note, int sortOrder, Instant addedAt) {
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.pairId = Objects.requireNonNull(pairId, "pairId must not be null");
        this.addedAt = Objects.requireNonNull(addedAt, "addedAt must not be null");
        this.label = blankToNull(label);
        this.note = blankToNull(note);
        this.sortOrder = requireSortOrder(sortOrder);
    }

    /** Adds a pair to a Trader's watchlist at the given position. */
    public static Watchlist add(UUID userId, UUID pairId, String label, String note, int sortOrder, Instant addedAt) {
        return new Watchlist(userId, pairId, label, note, sortOrder, addedAt);
    }

    /** Sets the label; a blank one removes it. */
    public void relabel(String label) {
        this.label = blankToNull(label);
    }

    /** Sets the note; a blank one removes it. */
    public void annotate(String note) {
        this.note = blankToNull(note);
    }

    /** Moves the row to another position in the list. */
    public void moveTo(int sortOrder) {
        this.sortOrder = requireSortOrder(sortOrder);
    }

    private static int requireSortOrder(int sortOrder) {
        if (sortOrder < 0) {
            throw new IllegalArgumentException("sortOrder must not be negative, was " + sortOrder);
        }
        return sortOrder;
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }
}
