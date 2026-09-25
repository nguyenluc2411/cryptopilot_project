package com.cryptopilot.market.service.impl;

import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.common.exception.ResourceNotFoundException;
import com.cryptopilot.market.MarketType;
import com.cryptopilot.market.entity.CryptoPair;
import com.cryptopilot.market.repository.CryptoPairRepository;
import java.time.Instant;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * What every market data request resolves first: the market it names and the pair it asks about.
 *
 * <p>A pair is found only when an administrator enabled it on that market (BR-07). A symbol that exists but is
 * disabled, or is enabled only on the other market, answers exactly like one that does not exist, so the API does
 * not tell which pairs are registered but hidden.
 *
 * <p>Rule: UC-09, BR-07; TECHNICAL_DESIGN 5.1.
 */
@Component
class MarketRequests {

    private final CryptoPairRepository pairs;

    MarketRequests(CryptoPairRepository pairs) {
        this.pairs = pairs;
    }

    /** The market named in a path or query, in any case. */
    static MarketType market(String market) {
        if (market != null) {
            for (MarketType type : MarketType.values()) {
                if (type.name().equals(market.toUpperCase(Locale.ROOT))) {
                    return type;
                }
            }
        }
        throw invalid("market must be spot or futures, was " + market);
    }

    /** The pair with this symbol, when it is enabled on this market (BR-07). */
    CryptoPair enabledPair(MarketType market, String symbol) {
        String normalized = symbol == null ? "" : symbol.toUpperCase(Locale.ROOT);
        return pairs.findBySymbol(normalized)
                .filter(pair -> pair.isEnabledOn(market))
                .orElseThrow(() -> new ResourceNotFoundException("Pair", market + " " + normalized));
    }

    /** Refuses a range that ends after {@code now} or does not start before it ends. */
    static void requireRange(Instant from, Instant to, Instant now) {
        if (to.isAfter(now)) {
            throw invalid("to must not be in the future, was " + to);
        }
        if (from != null && !from.isBefore(to)) {
            throw invalid("from must be before to, was " + from + " and " + to);
        }
    }

    static BusinessException invalid(String detail) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, detail);
    }
}
