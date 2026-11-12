package com.cryptopilot.journal.service.impl;

import com.cryptopilot.journal.FillLearning;
import com.cryptopilot.journal.FillLearningReader;
import com.cryptopilot.journal.model.enums.TradeSource;
import com.cryptopilot.journal.repository.TradingJournalRepository;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;

/**
 * Reads the learning data of a Trader's simulated trades for other modules, from the same records and with the same
 * figures as the detail of SCR-21.
 *
 * <p>Rule: BR-41, BR-70; D-111 (M4).
 *
 * <p>Reference: Fowler, M. (2002). <i>Patterns of Enterprise Application Architecture</i>. Addison-Wesley, "Data
 * Transfer Object".
 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class FillLearningReaderImpl implements FillLearningReader {

    static final int MAX_LIMIT = 100;

    private final TradingJournalRepository journal;

    @Override
    public Optional<FillLearning> ofTrade(UUID userId, UUID journalId) {
        Objects.requireNonNull(userId, "userId");
        return journal.findByIdAndUserId(journalId, userId)
                .filter(record -> record.getSource() == TradeSource.SIMULATED)
                .map(LearningData::of);
    }

    @Override
    public List<FillLearning> latestClosed(UUID userId, int limit) {
        Objects.requireNonNull(userId, "userId");
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be 1.." + MAX_LIMIT + ", not " + limit);
        }
        return journal.findLatestClosedSimulated(userId, Limit.of(limit)).stream()
                .map(LearningData::of)
                .toList();
    }
}
