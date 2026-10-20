package com.cryptopilot.community.entity;

import com.cryptopilot.common.entity.BaseEntity;
import com.cryptopilot.community.model.enums.Sentiment;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;

/**
 * A Trader's vote on how they feel about a coin on one day (UTC+7): bullish or bearish, once a day, changeable until
 * the day ends ({@code uq_coin_sentiment_vote_user_coin_day}). Written by
 * {@link com.cryptopilot.community.repository.CoinSentimentVoteRepository} with one upsert, so this entity is read only.
 *
 * <p>Rule: D-99 (CoinMarketCap Community); CR-01.
 */
@Getter
@Entity
@Table(name = "coin_sentiment_vote")
@AttributeOverride(name = "id", column = @Column(name = "vote_id", nullable = false, updatable = false))
public class CoinSentimentVote extends BaseEntity {

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "coin_id", nullable = false, updatable = false)
    private UUID coinId;

    @Column(name = "vote_date", nullable = false, updatable = false)
    private LocalDate voteDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "sentiment", nullable = false, length = 32)
    private Sentiment sentiment;

    /** For JPA only. */
    protected CoinSentimentVote() {}
}
