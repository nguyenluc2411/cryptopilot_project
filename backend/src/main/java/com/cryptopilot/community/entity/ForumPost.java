package com.cryptopilot.community.entity;

import com.cryptopilot.common.entity.BaseEntity;
import com.cryptopilot.community.model.enums.PostStatus;
import com.cryptopilot.community.model.enums.PostType;
import com.cryptopilot.market.model.enums.MarketType;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.Getter;

/**
 * A community post: a title, a text, the coins it is tagged with and the market it is about (UC-27). An official post
 * is an announcement of an administrator and is pinned above the feed (BR-61); only the announcement use case of the
 * administration console creates one, so the flag is fixed at creation.
 *
 * <p>A post is never deleted from the table. Its author deletes it by setting {@link PostStatus#DELETED} and a
 * moderator hides it with {@link PostStatus#HIDDEN}; who may still see it is {@link #isVisibleTo(UUID)} (BR-47).
 *
 * <p>The coin tags are the rows of {@code post_coin}, held as coin keys: the coins belong to the {@code market} module,
 * so they are referenced by id and validated by the caller, never mapped as an association.
 *
 * <p>Rule: UC-27, UC-29, UC-44; BR-47, BR-61; CR-06.
 */
@Getter
@Entity
@Table(name = "forum_post")
@AttributeOverride(name = "id", column = @Column(name = "post_id", nullable = false, updatable = false))
public class ForumPost extends BaseEntity {

    /** Shortest title, in characters (SRS 3.7.2). */
    public static final int TITLE_MIN_LENGTH = 5;

    /** Longest title, in characters (SRS 3.7.2); the column is wider. */
    public static final int TITLE_MAX_LENGTH = 150;

    /** Longest text, in characters (SRS 3.7.2). */
    public static final int CONTENT_MAX_LENGTH = 10_000;

    /** Most coins one post is tagged with (SRS 3.7.2). */
    public static final int MAX_COIN_TAGS = 5;

    @Column(name = "author_id", nullable = false, updatable = false)
    private UUID authorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "post_type", nullable = false, length = 32)
    private PostType postType;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "content", nullable = false)
    private String content;

    /** The market the post is about, or {@code null} for none. */
    @Enumerated(EnumType.STRING)
    @Column(name = "market_type", length = 32)
    private MarketType marketType;

    @Column(name = "is_official", nullable = false, updatable = false)
    private boolean official;

    @Enumerated(EnumType.STRING)
    @Column(name = "post_status", nullable = false, length = 32)
    private PostStatus status;

    @ElementCollection
    @CollectionTable(name = "post_coin", joinColumns = @JoinColumn(name = "post_id"))
    @Column(name = "coin_id", nullable = false)
    private Set<UUID> coinIds = new HashSet<>();

    /** For JPA only. */
    protected ForumPost() {}

    private ForumPost(UUID authorId, boolean official) {
        this.authorId = Objects.requireNonNull(authorId, "authorId must not be null");
        this.official = official;
        this.postType = PostType.TEXT;
        this.status = PostStatus.PUBLISHED;
    }

    /** A Trader's post, published at once (UC-27). */
    public static ForumPost publish(
            UUID authorId, String title, String content, MarketType marketType, Collection<UUID> coinIds) {
        ForumPost post = new ForumPost(authorId, false);
        post.revise(title, content, marketType, coinIds);
        return post;
    }

    /** An administrator's announcement, published at once and pinned (UC-44, BR-61). */
    public static ForumPost announce(
            UUID adminId, String title, String content, MarketType marketType, Collection<UUID> coinIds) {
        ForumPost post = new ForumPost(adminId, true);
        post.revise(title, content, marketType, coinIds);
        return post;
    }

    /**
     * Replaces the title, text, market and coin tags (UC-29). Allowed at any status but {@link PostStatus#DELETED}: a
     * deleted post is gone for its author.
     */
    public void revise(String title, String content, MarketType marketType, Collection<UUID> coinIds) {
        if (status == PostStatus.DELETED) {
            throw new IllegalStateException("post " + getId() + " is deleted");
        }
        this.title = requireTitle(title);
        this.content = requireContent(content);
        this.marketType = marketType;
        Set<UUID> tags = Set.copyOf(Objects.requireNonNull(coinIds, "coinIds must not be null"));
        if (tags.size() > MAX_COIN_TAGS) {
            throw new IllegalArgumentException("a post is tagged with at most " + MAX_COIN_TAGS + " coins");
        }
        this.coinIds.retainAll(tags);
        this.coinIds.addAll(tags);
    }

    /** Records what the post carries besides its text: a video makes it a VIDEO post (BR-43, BR-44). */
    public void carry(PostType type) {
        this.postType = Objects.requireNonNull(type, "type must not be null");
    }

    /** Hides the post from everyone but its author and Admin (UC-43, BR-47); a deleted post stays deleted. */
    public void hide() {
        if (status == PostStatus.PUBLISHED) {
            this.status = PostStatus.HIDDEN;
        }
    }

    /**
     * Shows a hidden post again (UC-43).
     *
     * @throws IllegalStateException when the post is not hidden
     */
    public void restore() {
        if (status != PostStatus.HIDDEN) {
            throw new IllegalStateException("post " + getId() + " is " + status + ", not hidden");
        }
        this.status = PostStatus.PUBLISHED;
    }

    /** Deletes the post for everyone but Admin (BR-47); deleting it again changes nothing. */
    public void delete() {
        this.status = PostStatus.DELETED;
    }

    /** Whether the post was deleted; a deleted post can no longer be edited or deleted (BR-47). */
    public boolean isDeleted() {
        return status == PostStatus.DELETED;
    }

    /**
     * Whether a Trader may see the post: a published one everyone may, a hidden one only its author, a deleted one
     * nobody (BR-47).
     */
    public boolean isVisibleTo(UUID userId) {
        return switch (status) {
            case PUBLISHED -> true;
            case HIDDEN -> authorId.equals(userId);
            case DELETED -> false;
        };
    }

    private static String requireTitle(String title) {
        String text = Objects.requireNonNull(title, "title must not be null").strip();
        if (text.length() < TITLE_MIN_LENGTH || text.length() > TITLE_MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "a title has " + TITLE_MIN_LENGTH + " to " + TITLE_MAX_LENGTH + " characters");
        }
        return text;
    }

    private static String requireContent(String content) {
        String text =
                Objects.requireNonNull(content, "content must not be null").strip();
        if (text.isEmpty() || text.length() > CONTENT_MAX_LENGTH) {
            throw new IllegalArgumentException("a text has 1 to " + CONTENT_MAX_LENGTH + " characters");
        }
        return text;
    }
}
