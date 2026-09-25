package com.cryptopilot.market.service.impl;

import com.cryptopilot.common.web.PageResponse;
import com.cryptopilot.market.MarketType;
import com.cryptopilot.market.PairFilters;
import com.cryptopilot.market.dto.response.PairResponse;
import com.cryptopilot.market.entity.Coin;
import com.cryptopilot.market.entity.CryptoPair;
import com.cryptopilot.market.repository.CoinRepository;
import com.cryptopilot.market.repository.CryptoPairRepository;
import com.cryptopilot.market.service.PairService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The market list of UC-09: the pairs enabled on one market, a page at a time.
 *
 * <p>The order is the administrator's display order, then the symbol, which is unique — so the order is total and a
 * pair appears on exactly one page for as long as the list does not change. The whole list is read and paged in
 * memory: it holds the 30 to 50 pairs an administrator enables (Q-05), which is one database page.
 *
 * <p>Rule: UC-09, BR-07; SRS 3.3.1; TECHNICAL_DESIGN 8 (pagination {@code page}, {@code pageSize}, at most 100).
 */
@Service
public class PairServiceImpl implements PairService {

    /** The page size when the request names none (TECHNICAL_DESIGN 8). */
    static final int DEFAULT_PAGE_SIZE = 20;

    private final CryptoPairRepository pairs;
    private final CoinRepository coins;

    public PairServiceImpl(CryptoPairRepository pairs, CoinRepository coins) {
        this.pairs = pairs;
        this.coins = coins;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<PairResponse> enabledPairs(String market, Integer page, Integer pageSize) {
        MarketType type = MarketRequests.market(market);
        int number = page == null ? 1 : page;
        int size = pageSize == null ? DEFAULT_PAGE_SIZE : pageSize;
        if (number < 1) {
            throw MarketRequests.invalid("page must be at least 1, was " + number);
        }
        if (size < 1 || size > PageResponse.MAX_PAGE_SIZE) {
            throw MarketRequests.invalid(
                    "pageSize must be between 1 and " + PageResponse.MAX_PAGE_SIZE + ", was " + size);
        }
        List<CryptoPair> enabled = pairs.findEnabledOn(type);
        long first = (long) (number - 1) * size;
        List<CryptoPair> slice = first >= enabled.size()
                ? List.of()
                : enabled.subList((int) first, (int) Math.min(first + size, enabled.size()));
        Map<UUID, Optional<Coin>> known = new HashMap<>();
        List<PairResponse> items =
                slice.stream().map(pair -> response(pair, type, known)).toList();
        return new PageResponse<>(items, number, size, enabled.size());
    }

    private PairResponse response(CryptoPair pair, MarketType market, Map<UUID, Optional<Coin>> known) {
        Optional<Coin> base = known.computeIfAbsent(pair.getBaseCoinId(), coins::findById);
        Optional<Coin> quote = known.computeIfAbsent(pair.getQuoteCoinId(), coins::findById);
        Optional<PairFilters> filters = pair.filters(market);
        return new PairResponse(
                pair.getSymbol(),
                market.name(),
                base.map(Coin::getSymbol).orElse(null),
                quote.map(Coin::getSymbol).orElse(null),
                base.map(Coin::getCoinName).orElse(null),
                base.map(Coin::getLogoUrl).orElse(null),
                filters.map(PairFilters::tickSize).orElse(null),
                filters.map(PairFilters::stepSize).orElse(null),
                filters.map(PairFilters::minNotional).orElse(null),
                pair.getDisplayOrder());
    }
}
