package com.cryptopilot.community.service.impl;

import com.cryptopilot.common.exception.ResourceNotFoundException;
import com.cryptopilot.common.util.UuidV7;
import com.cryptopilot.community.dto.response.CoinSentimentResponse;
import com.cryptopilot.community.entity.CoinSentimentVote;
import com.cryptopilot.community.model.enums.Sentiment;
import com.cryptopilot.community.repository.CoinSentimentVoteRepository;
import com.cryptopilot.community.repository.PostFeedRepository;
import com.cryptopilot.community.service.SentimentService;
import com.cryptopilot.market.CoinListing;
import com.cryptopilot.market.MarketApi;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The sentiment of a coin (D-99). A day is the calendar day in UTC+7 (CR-01), the day the Trader sees, so the vote of
 * the day starts again at local midnight. The bullish share is rounded half up to 2 decimals (CR-02, CR-03).
 *
 * <p>Rule: BR-60; CR-01, CR-02, CR-03; D-99.
 */
@Service
@RequiredArgsConstructor
public class SentimentServiceImpl implements SentimentService {

    /** The zone a vote's day is counted in (CR-01). */
    static final ZoneOffset DAY_ZONE = ZoneOffset.ofHours(7);

    private final CoinSentimentVoteRepository votes;
    private final PostFeedRepository feeds;
    private final MarketApi market;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public CoinSentimentResponse coin(UUID caller, String symbol) {
        CoinListing coin = market.coinsBySymbol(List.of(normalized(symbol))).stream()
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Coin", symbol));
        return summary(coin, caller, clock.instant());
    }

    @Override
    @Transactional
    public CoinSentimentResponse vote(UUID trader, String symbol, Sentiment sentiment) {
        CoinListing coin = market.listedCoinsBySymbol(List.of(normalized(symbol))).stream()
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Coin", symbol));
        Instant now = clock.instant();
        votes.vote(UuidV7.next(), trader, coin.coinId(), dayOf(now), sentiment.name(), now);
        return summary(coin, trader, now);
    }

    private CoinSentimentResponse summary(CoinListing coin, UUID caller, Instant now) {
        LocalDate day = dayOf(now);
        Map<Sentiment, Long> counted = new EnumMap<>(Sentiment.class);
        for (Object[] row : votes.countByDay(coin.coinId(), day)) {
            counted.put(Sentiment.valueOf((String) row[0]), ((Number) row[1]).longValue());
        }
        long bullish = counted.getOrDefault(Sentiment.BULLISH, 0L);
        long bearish = counted.getOrDefault(Sentiment.BEARISH, 0L);
        BigDecimal percent = bullish + bearish == 0
                ? null
                : BigDecimal.valueOf(bullish * 100)
                        .divide(BigDecimal.valueOf(bullish + bearish), 2, RoundingMode.HALF_UP);
        Sentiment mine = caller == null
                ? null
                : votes.findByUserIdAndCoinIdAndVoteDate(caller, coin.coinId(), day)
                        .map(CoinSentimentVote::getSentiment)
                        .orElse(null);
        Map<Sentiment, Long> posts = feeds.sentimentOfPosts(coin.coinId(), now.minusSeconds(24 * 3600));
        return new CoinSentimentResponse(
                coin.symbol(),
                day,
                bullish,
                bearish,
                percent,
                mine,
                posts.getOrDefault(Sentiment.BULLISH, 0L),
                posts.getOrDefault(Sentiment.BEARISH, 0L));
    }

    static LocalDate dayOf(Instant instant) {
        return instant.atOffset(DAY_ZONE).toLocalDate();
    }

    private static String normalized(String symbol) {
        return symbol.strip().toUpperCase(Locale.ROOT);
    }
}
