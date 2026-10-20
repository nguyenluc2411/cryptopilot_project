package com.cryptopilot.community.repository;

import com.cryptopilot.community.entity.CoinSentimentVote;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * The daily sentiment votes on coins (D-99). A vote is one upsert on the Trader, coin and day, so a second vote the
 * same day replaces the first and two at once leave one row.
 *
 * <p>Rule: D-99.
 */
public interface CoinSentimentVoteRepository extends Repository<CoinSentimentVote, UUID> {

    /** Records the Trader's vote of the day, replacing an earlier one of the same day. */
    @Modifying
    @Query(value = """
                    insert into coin_sentiment_vote (vote_id, user_id, coin_id, vote_date, sentiment, created_at,
                                                     updated_at)
                    values (:id, :userId, :coinId, :day, :sentiment, :now, :now)
                    on conflict (user_id, coin_id, vote_date)
                    do update set sentiment = excluded.sentiment, updated_at = excluded.updated_at,
                                  version = coin_sentiment_vote.version + 1""", nativeQuery = true)
    int vote(
            @Param("id") UUID id,
            @Param("userId") UUID userId,
            @Param("coinId") UUID coinId,
            @Param("day") LocalDate day,
            @Param("sentiment") String sentiment,
            @Param("now") Instant now);

    /** The votes of a coin on a day, as (sentiment, count) pairs. */
    @Transactional(readOnly = true)
    @Query(value = """
                    select sentiment, count(*) from coin_sentiment_vote
                     where coin_id = :coinId and vote_date = :day
                     group by sentiment""", nativeQuery = true)
    List<Object[]> countByDay(@Param("coinId") UUID coinId, @Param("day") LocalDate day);

    /** The Trader's vote on a coin on a day, or empty. */
    @Transactional(readOnly = true)
    Optional<CoinSentimentVote> findByUserIdAndCoinIdAndVoteDate(UUID userId, UUID coinId, LocalDate voteDate);
}
