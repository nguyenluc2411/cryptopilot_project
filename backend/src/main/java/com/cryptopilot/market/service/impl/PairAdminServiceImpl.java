package com.cryptopilot.market.service.impl;

import com.cryptopilot.admin.AdminApi;
import com.cryptopilot.admin.AuditEntry;
import com.cryptopilot.admin.model.enums.AuditAction;
import com.cryptopilot.admin.model.enums.AuditedEntity;
import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.common.exception.FieldValidationException;
import com.cryptopilot.common.exception.ResourceNotFoundException;
import com.cryptopilot.market.LeverageBracketsReplaced;
import com.cryptopilot.market.LeverageTier;
import com.cryptopilot.market.OpenPositionCheck;
import com.cryptopilot.market.PairMarketDisabled;
import com.cryptopilot.market.client.BinanceRestClient;
import com.cryptopilot.market.client.ExchangeSymbol;
import com.cryptopilot.market.dto.request.LeverageBracketRequest;
import com.cryptopilot.market.dto.request.LeverageBracketsRequest;
import com.cryptopilot.market.dto.request.UpdatePairRequest;
import com.cryptopilot.market.dto.response.AdminPairResponse;
import com.cryptopilot.market.dto.response.ExchangeSymbolResponse;
import com.cryptopilot.market.dto.response.LeverageBracketsResponse;
import com.cryptopilot.market.entity.Coin;
import com.cryptopilot.market.entity.CryptoPair;
import com.cryptopilot.market.model.ExchangeListing;
import com.cryptopilot.market.model.enums.ExchangeStatus;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.model.enums.PairStatus;
import com.cryptopilot.market.repository.CoinRepository;
import com.cryptopilot.market.repository.CryptoPairRepository;
import com.cryptopilot.market.repository.LeverageBracketRepository;
import com.cryptopilot.market.service.PairAdminService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * SCR-37 and SCR-38. A pair's availability on a market is its status and the market's switch together (BR-07), so a
 * change of either that takes a market out of service is a disabling: refused while simulated positions are open there
 * (BR-59, asked of every {@link OpenPositionCheck}), and announced with {@link PairMarketDisabled} inside the same
 * transaction, whose synchronous listener cancels the market's ACTIVE plans. Streams and backfill follow the enabled
 * pairs on their own: NSF-03 reads them again every refresh interval (5 minutes).
 *
 * <p>Adding a pair reuses the synchronisation of NSF-01 with the symbol as its only seed, so a pair added here and one
 * seeded from configuration are created by the same code, with the same coins and filters.
 *
 * <p>The pair's leverage ceiling and its first bracket are kept consistent from either side (BR-26).
 *
 * <p>Rule: UC-45, UC-46; BR-07, BR-26, BR-27, BR-31, BR-57, BR-59; NSF-01.
 */
@Service
@RequiredArgsConstructor
public class PairAdminServiceImpl implements PairAdminService {

    /** The most symbols {@link #exchangeSymbols} lists. */
    static final int EXCHANGE_SYMBOLS_LIMIT = 50;

    /** The scale of {@code leverage_bracket}'s notional, rate and amount columns. */
    private static final int BRACKET_SCALE = 8;

    private final CryptoPairRepository pairs;
    private final CoinRepository coins;
    private final LeverageBracketRepository brackets;
    private final List<OpenPositionCheck> openPositions;
    private final BinanceRestClient exchange;
    private final SymbolSyncWriter syncWriter;
    private final AdminApi admin;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transactions;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public List<AdminPairResponse> list(String query, PairStatus status) {
        String needle = query == null || query.isBlank() ? null : query.strip().toUpperCase(Locale.ROOT);
        List<CryptoPair> found = pairs.findAllForSync().stream()
                .filter(pair -> needle == null || pair.getSymbol().contains(needle))
                .filter(pair -> status == null || pair.getPairStatus() == status)
                .sorted(Comparator.comparingInt(CryptoPair::getDisplayOrder).thenComparing(CryptoPair::getSymbol))
                .toList();
        return responses(found);
    }

    @Override
    @Transactional(readOnly = true)
    public AdminPairResponse get(UUID pairId) {
        return responses(List.of(pair(pairId))).getFirst();
    }

    @Override
    public List<ExchangeSymbolResponse> exchangeSymbols(String query) {
        String needle = query == null || query.isBlank() ? null : query.strip().toUpperCase(Locale.ROOT);
        Map<String, ExchangeListing> spot = trading(MarketType.SPOT, exchange.spotExchangeInfo());
        Map<String, ExchangeListing> futures = trading(MarketType.FUTURES, exchange.futuresExchangeInfo());
        Map<String, ExchangeListing> either = new TreeMap<>(futures);
        either.putAll(spot);
        List<String> registered =
                pairs.findAllForSync().stream().map(CryptoPair::getSymbol).toList();
        return either.values().stream()
                .filter(listing -> !registered.contains(listing.symbol()))
                .filter(listing -> needle == null || listing.symbol().contains(needle))
                .limit(EXCHANGE_SYMBOLS_LIMIT)
                .map(listing -> new ExchangeSymbolResponse(
                        listing.symbol(),
                        listing.baseAsset(),
                        listing.quoteAsset(),
                        spot.containsKey(listing.symbol()),
                        futures.containsKey(listing.symbol())))
                .toList();
    }

    @Override
    public AdminPairResponse register(UUID adminId, String symbol) {
        String wanted = symbol.strip().toUpperCase(Locale.ROOT);
        if (pairs.findBySymbol(wanted).isPresent()) {
            throw new BusinessException(ErrorCode.PAIR_ALREADY_REGISTERED, wanted + " is already a pair");
        }
        List<ExchangeSymbol> spot = exchange.spotExchangeInfo();
        List<ExchangeSymbol> futures = exchange.futuresExchangeInfo();
        if (!trading(MarketType.SPOT, spot).containsKey(wanted)
                && !trading(MarketType.FUTURES, futures).containsKey(wanted)) {
            throw new BusinessException(ErrorCode.PAIR_NOT_TRADING, "the exchange trades " + wanted + " nowhere");
        }
        Instant now = clock.instant();
        try {
            return transactions.execute(status -> created(adminId, wanted, spot, futures, now));
        } catch (DataIntegrityViolationException refused) {
            // The whole registration rolled back, so no CREATED audit row was written for it (BR-57). The pair being
            // there now means another administrator registered it meanwhile. Anything else, such as two pairs of one
            // new coin (SOLUSDT and SOLUSDC) racing on uq_coin_symbol, is a conflict the client may simply retry.
            if (pairs.findBySymbol(wanted).isPresent()) {
                throw new BusinessException(ErrorCode.PAIR_ALREADY_REGISTERED, wanted + " is already a pair");
            }
            throw refused;
        }
    }

    /**
     * Creates the pair from the exchange information, places it last and audits it, in the caller's transaction: the
     * pair never exists without its place and its CREATED audit row (BR-57). The exchange is read before, so no
     * transaction waits on it.
     */
    private AdminPairResponse created(
            UUID adminId, String wanted, List<ExchangeSymbol> spot, List<ExchangeSymbol> futures, Instant now) {
        syncWriter.apply(MarketType.SPOT, spot, List.of(wanted), now);
        syncWriter.apply(MarketType.FUTURES, futures, List.of(wanted), now);
        CryptoPair pair = pairs.findBySymbol(wanted)
                .orElseThrow(() -> new IllegalStateException(wanted + " was not created by the synchronisation"));
        int last = pairs.findAllForSync().stream()
                .filter(other -> !other.getId().equals(pair.getId()))
                .mapToInt(CryptoPair::getDisplayOrder)
                .max()
                .orElse(0);
        pair.placeAt(last + 1);
        pairs.save(pair);
        admin.audit(new AuditEntry(
                adminId,
                AuditAction.CONFIGURATION_CREATED,
                AuditedEntity.CRYPTO_PAIR,
                pair.getId(),
                null,
                values(pair)));
        return responses(List.of(pair)).getFirst();
    }

    @Override
    @Transactional
    public AdminPairResponse update(UUID adminId, UUID pairId, UpdatePairRequest request) {
        CryptoPair pair = lockedPair(pairId);
        if (request.futuresEnabled() && request.maxLeverage() == null) {
            throw new FieldValidationException(
                    "a futures market needs its maximum leverage", Map.of("maxLeverage", "MSG01"));
        }
        if (request.futuresEnabled()) {
            requireWithinFirstBracket(request.maxLeverage(), brackets.findByPair(pair.getId()), "maxLeverage");
        }
        Map<String, Object> before = values(pair);
        Instant now = clock.instant();
        List<MarketType> disabled = new ArrayList<>();
        for (MarketType market : MarketType.values()) {
            boolean switchOn = market == MarketType.SPOT ? request.spotEnabled() : request.futuresEnabled();
            boolean available = request.status() == PairStatus.ACTIVE && switchOn;
            // Whichever switch brings the market into service, the exchange must trade it there.
            if (available && !pair.isEnabledOn(market) && !tradable(pair, market)) {
                throw new BusinessException(
                        ErrorCode.PAIR_NOT_TRADING,
                        "the exchange does not trade " + pair.getSymbol() + " on " + market + " or sent no filters");
            }
            if (pair.isEnabledOn(market) && !available) {
                requireNoOpenPositions(pair, market);
                disabled.add(market);
            }
        }
        for (MarketType market : MarketType.values()) {
            boolean switchOn = market == MarketType.SPOT ? request.spotEnabled() : request.futuresEnabled();
            if (switchOn) {
                pair.enable(market);
            } else {
                pair.disable(market);
            }
        }
        pair.changeStatus(request.status());
        if (request.futuresEnabled()) {
            pair.limitLeverage(request.maxLeverage());
        }
        pair.placeAt(request.displayOrder());
        pairs.save(pair);
        pairs.flush();
        for (MarketType market : disabled) {
            events.publishEvent(new PairMarketDisabled(pair.getId(), pair.getSymbol(), market, now));
        }
        // Again, now the listeners hold the market's ACTIVE plans under their row locks: a fill that was committing
        // when the first check ran has finished, so a position it opened is seen here and the change rolls back.
        // The trading check reads the journal, which the matching engine writes from T-048 on; until then only the
        // paper positions refuse.
        for (MarketType market : disabled) {
            requireNoOpenPositions(pair, market);
        }
        Map<String, Object> after = values(pair);
        if (!before.equals(after)) {
            admin.audit(new AuditEntry(
                    adminId,
                    AuditAction.CONFIGURATION_CHANGED,
                    AuditedEntity.CRYPTO_PAIR,
                    pair.getId(),
                    changed(before, after),
                    changed(after, before)));
        }
        return responses(List.of(pair)).getFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public LeverageBracketsResponse brackets(UUID pairId) {
        CryptoPair pair = pair(pairId);
        return new LeverageBracketsResponse(pair.getId(), pair.getSymbol(), brackets.findByPair(pair.getId()));
    }

    @Override
    @Transactional
    public LeverageBracketsResponse replaceBrackets(UUID adminId, UUID pairId, LeverageBracketsRequest request) {
        CryptoPair pair = lockedPair(pairId);
        if (pair.exchangeStatus(MarketType.FUTURES) == null) {
            throw new BusinessException(
                    ErrorCode.PAIR_NOT_TRADING, "the exchange has never listed " + pair.getSymbol() + " on futures");
        }
        List<LeverageTier> tiers = checkedBrackets(request.brackets());
        if (pair.getMaxLeverage() != null) {
            requireWithinFirstBracket(pair.getMaxLeverage(), tiers, "brackets[0].maxLeverage");
        }
        List<LeverageTier> before = brackets.findByPair(pair.getId());
        Instant now = clock.instant();
        brackets.replace(pair.getId(), tiers, adminId, now);
        // Open paper Futures positions keep trading on the new brackets: their liquidation price and maintenance
        // margin follow at once (plan 9.7).
        events.publishEvent(new LeverageBracketsReplaced(pair.getId(), pair.getSymbol(), now));
        admin.audit(new AuditEntry(
                adminId,
                before.isEmpty() ? AuditAction.CONFIGURATION_CREATED : AuditAction.CONFIGURATION_CHANGED,
                AuditedEntity.LEVERAGE_BRACKET,
                pair.getId(),
                before.isEmpty() ? null : Map.of("symbol", pair.getSymbol(), "brackets", bracketValues(before)),
                Map.of("symbol", pair.getSymbol(), "brackets", bracketValues(tiers))));
        return new LeverageBracketsResponse(pair.getId(), pair.getSymbol(), tiers);
    }

    /**
     * The rows as brackets, numbered from 1, each checked against the one before it. The maintenance amount follows
     * from BR-27: the maintenance margin {@code notional × MMR − cum} must not jump where a bracket begins, so
     * {@code cum(n) = cum(n−1) + floor(n) × (MMR(n) − MMR(n−1))}, 0 for the first bracket — Binance's own values.
     */
    static List<LeverageTier> checkedBrackets(List<LeverageBracketRequest> rows) {
        Map<String, String> errors = new LinkedHashMap<>();
        List<LeverageTier> tiers = new ArrayList<>();
        LeverageTier previous = null;
        for (int index = 0; index < rows.size(); index++) {
            LeverageBracketRequest row = rows.get(index);
            String field = "brackets[" + index + "].";
            BigDecimal floor = row.notionalFloor().setScale(BRACKET_SCALE, RoundingMode.UNNECESSARY);
            BigDecimal cap = row.notionalCap().setScale(BRACKET_SCALE, RoundingMode.UNNECESSARY);
            BigDecimal rate = row.maintenanceMarginRate().setScale(BRACKET_SCALE, RoundingMode.UNNECESSARY);
            BigDecimal expectedFloor = previous == null ? BigDecimal.ZERO : previous.notionalCap();
            if (floor.compareTo(expectedFloor) != 0) {
                errors.put(field + "notionalFloor", "MSG15");
            }
            if (cap.compareTo(floor) <= 0) {
                errors.put(field + "notionalCap", "MSG15");
            }
            if (previous != null && row.maxLeverage() > previous.maxLeverage()) {
                errors.put(field + "maxLeverage", "MSG15");
            }
            BigDecimal amount = previous == null
                    ? BigDecimal.ZERO.setScale(BRACKET_SCALE)
                    : previous.maintenanceAmount()
                            .add(floor.multiply(rate.subtract(previous.maintenanceMarginRate())))
                            .setScale(BRACKET_SCALE, RoundingMode.HALF_UP);
            if (row.maintenanceAmount() != null && row.maintenanceAmount().compareTo(amount) != 0) {
                errors.put(field + "maintenanceAmount", "MSG15");
            }
            LeverageTier tier = new LeverageTier(index + 1, floor, cap, row.maxLeverage(), rate, amount);
            tiers.add(tier);
            previous = tier;
        }
        if (!errors.isEmpty()) {
            throw new FieldValidationException("the brackets break " + errors.keySet(), errors);
        }
        return tiers;
    }

    /**
     * The pair's leverage ceiling never exceeds the first bracket's maximum, the highest any position can use (BR-26):
     * a higher one would offer the Trader a leverage every calculation refuses. Brackets not entered yet bound nothing.
     */
    private static void requireWithinFirstBracket(int maxLeverage, List<LeverageTier> tiers, String field) {
        if (!tiers.isEmpty() && maxLeverage > tiers.getFirst().maxLeverage()) {
            throw new FieldValidationException(
                    "the pair's maximum leverage " + maxLeverage + " is above the first bracket's "
                            + tiers.getFirst().maxLeverage(),
                    Map.of(field, "MSG15"));
        }
    }

    private void requireNoOpenPositions(CryptoPair pair, MarketType market) {
        for (OpenPositionCheck check : openPositions) {
            if (check.anyOpen(pair.getId(), market)) {
                throw new BusinessException(
                        ErrorCode.PAIR_HAS_OPEN_POSITIONS,
                        pair.getSymbol() + " " + market + " has open simulated positions");
            }
        }
    }

    /** Whether the exchange trades the pair on the market and has sent its filters there. */
    private static boolean tradable(CryptoPair pair, MarketType market) {
        return pair.exchangeStatus(market) == ExchangeStatus.TRADING
                && pair.filters(market).isPresent();
    }

    /** The listings of the market the system can use and the exchange trades, by symbol. */
    private static Map<String, ExchangeListing> trading(MarketType market, List<ExchangeSymbol> listed) {
        return listed.stream()
                .map(symbol -> ExchangeSymbolMapper.toListing(market, symbol))
                .flatMap(Optional::stream)
                .filter(ExchangeListing::trading)
                .collect(Collectors.toMap(ExchangeListing::symbol, Function.identity(), (a, b) -> a));
    }

    private CryptoPair pair(UUID pairId) {
        return pairs.findById(pairId).orElseThrow(() -> new ResourceNotFoundException("Pair", pairId));
    }

    /**
     * The pair under its row lock: a change of the leverage ceiling and a change of the brackets each check against
     * the other's stored value, so two of them on one pair run one after the other and BR-26 holds after both.
     */
    private CryptoPair lockedPair(UUID pairId) {
        return pairs.findByIdForUpdate(pairId).orElseThrow(() -> new ResourceNotFoundException("Pair", pairId));
    }

    private List<AdminPairResponse> responses(List<CryptoPair> found) {
        Map<UUID, String> symbols = coins
                .findAllByIdIn(found.stream()
                        .flatMap(pair -> Stream.of(pair.getBaseCoinId(), pair.getQuoteCoinId()))
                        .collect(Collectors.toSet()))
                .stream()
                .collect(Collectors.toMap(Coin::getId, Coin::getSymbol));
        Map<UUID, Integer> counts =
                brackets.countByPairs(found.stream().map(CryptoPair::getId).toList());
        return found.stream()
                .map(pair -> new AdminPairResponse(
                        pair.getId(),
                        pair.getSymbol(),
                        symbols.get(pair.getBaseCoinId()),
                        symbols.get(pair.getQuoteCoinId()),
                        pair.getPairStatus(),
                        pair.isSpotEnabled(),
                        pair.isFuturesEnabled(),
                        pair.getMaxLeverage(),
                        pair.getDisplayOrder(),
                        market(pair, MarketType.SPOT),
                        market(pair, MarketType.FUTURES),
                        counts.getOrDefault(pair.getId(), 0),
                        pair.getLastSyncedAt(),
                        pair.getUpdatedAt()))
                .toList();
    }

    private static AdminPairResponse.ExchangeMarket market(CryptoPair pair, MarketType market) {
        return new AdminPairResponse.ExchangeMarket(
                pair.exchangeStatus(market),
                pair.exchangeStatusRaw(market),
                market == MarketType.SPOT ? pair.getSpotTickSize() : pair.getFuturesTickSize(),
                market == MarketType.SPOT ? pair.getSpotStepSize() : pair.getFuturesStepSize(),
                market == MarketType.SPOT ? pair.getSpotMinNotional() : pair.getFuturesMinNotional());
    }

    /** What an administrator sets on a pair, for the audit entry. */
    private static Map<String, Object> values(CryptoPair pair) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("symbol", pair.getSymbol());
        values.put("status", pair.getPairStatus().name());
        values.put("spotEnabled", pair.isSpotEnabled());
        values.put("futuresEnabled", pair.isFuturesEnabled());
        values.put("maxLeverage", pair.getMaxLeverage());
        values.put("displayOrder", pair.getDisplayOrder());
        return values;
    }

    /** The entries of {@code values} that differ in {@code other}, the symbol always kept to name the pair. */
    private static Map<String, Object> changed(Map<String, Object> values, Map<String, Object> other) {
        Map<String, Object> result = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (key.equals("symbol") || !Objects.equals(value, other.get(key))) {
                result.put(key, value);
            }
        });
        return result;
    }

    private static List<Map<String, Object>> bracketValues(List<LeverageTier> tiers) {
        return tiers.stream()
                .map(tier -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("bracketNo", tier.bracketNo());
                    row.put("notionalFloor", tier.notionalFloor());
                    row.put("notionalCap", tier.notionalCap());
                    row.put("maxLeverage", tier.maxLeverage());
                    row.put("maintenanceMarginRate", tier.maintenanceMarginRate());
                    row.put("maintenanceAmount", tier.maintenanceAmount());
                    return row;
                })
                .toList();
    }
}
