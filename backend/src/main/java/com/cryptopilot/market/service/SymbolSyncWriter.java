package com.cryptopilot.market.service;

import com.cryptopilot.market.MarketType;
import com.cryptopilot.market.PairFilters;
import com.cryptopilot.market.client.ExchangeSymbol;
import com.cryptopilot.market.entity.Coin;
import com.cryptopilot.market.entity.CryptoPair;
import com.cryptopilot.market.entity.ExchangeStatus;
import com.cryptopilot.market.repository.CoinRepository;
import com.cryptopilot.market.repository.CryptoPairRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reconciles one market's exchange information with the pair table, in one transaction (NSF-01).
 *
 * <h2>The universe</h2>
 *
 * <p>The pairs already in {@code crypto_pair}, plus the configured seed symbols. NSF-01 updates "the filters
 * of enabled pairs"; it does not ask for the exchange's whole list — some five hundred symbols per market —
 * to be copied in, so a symbol neither registered nor seeded is ignored. Every registered pair is updated,
 * enabled or not, so that an administrator enabling one later starts from current filters.
 *
 * <h2>The rules, per pair and market</h2>
 *
 * <ul>
 *   <li><b>Listed:</b> the filters are replaced by the exchange's (a change logged at INFO, old and new), the
 *       status is normalized — {@code TRADING}, or {@code NOT_TRADING} for any other status — with Binance's
 *       text kept beside it (Q-15).
 *   <li><b>Not listed, but listed before or switched on:</b> {@code DELISTED}, raw text cleared. The row is
 *       never deleted: plans, journals and watchlists reference it.
 *   <li><b>Not listed and never was:</b> left without a status. This is a pair of one market only — the
 *       other market spells the symbol differently, e.g. {@code SHIBUSDT} on Spot and {@code 1000SHIBUSDT} on
 *       futures — and the two spellings are two symbols, never linked (Q-14).
 *   <li><b>Seed symbol missing from the table and listed and trading here:</b> created with its coins,
 *       INACTIVE with both markets off (BR-07). Activation is an administrator's decision.
 * </ul>
 *
 * <p>A pair that stops trading or disappears is flagged at WARN for an administrator (NSF-01: "flagged on
 * SCR-37 and Admin is notified"); the notification itself arrives with the notification module.
 *
 * <h2>Idempotent</h2>
 *
 * <p>Reprocessing the same exchange information leaves every filter, status and raw text as it was and
 * flags nothing again, because each rule writes the state the input implies rather than a change relative to
 * the previous run. Only {@code last_synced_at} moves, deliberately: it says when the exchange was last read
 * for the pair, which is what an administrator needs to see stale data, and it is not derived from the input.
 *
 * <p>Rule: NSF-01; BR-07; Q-14, Q-15; TECHNICAL_DESIGN 7.1.
 *
 * <p>Reference: Kleppmann, M. (2017). <i>Designing Data-Intensive Applications</i>. O'Reilly, ch. 11
 * (idempotent processing: applying the same input again yields the same state, so reprocessing is safe).
 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class SymbolSyncWriter {

    private static final Logger log = LoggerFactory.getLogger(SymbolSyncWriter.class);

    private final CryptoPairRepository pairs;
    private final CoinRepository coins;

    /** Applies one market's exchange information to the table, all of it or none of it. */
    @Transactional
    public SyncReport apply(
            MarketType market, List<ExchangeSymbol> listed, Collection<String> seedSymbols, Instant now) {
        Map<String, ExchangeSymbol> bySymbol =
                listed.stream().collect(Collectors.toMap(ExchangeSymbol::symbol, Function.identity(), (a, b) -> a));
        List<CryptoPair> universe = new ArrayList<>(pairs.findAllForSync());
        List<String> created = createMissingSeeds(market, bySymbol, seedSymbols, universe);
        List<String> filterChanges = new ArrayList<>();
        List<String> flagged = new ArrayList<>();

        for (CryptoPair pair : universe) {
            ExchangeSymbol symbol = bySymbol.get(pair.getSymbol());
            ExchangeStatus before = pair.exchangeStatus(market);
            if (symbol == null) {
                reconcileAbsent(market, pair, before, flagged);
            } else {
                reconcileListed(market, pair, symbol, before, filterChanges, flagged);
            }
            pair.markSynced(now);
            pairs.save(pair);
        }
        SyncReport report = new SyncReport(market, universe.size(), created, filterChanges, flagged);
        log.info(
                "NSF-01 {}: {} pairs reconciled, created {}, filters changed {}, flagged {}",
                market,
                report.reconciled(),
                created,
                filterChanges,
                flagged);
        return report;
    }

    private void reconcileListed(
            MarketType market,
            CryptoPair pair,
            ExchangeSymbol symbol,
            ExchangeStatus before,
            List<String> filterChanges,
            List<String> flagged) {
        Optional<ExchangeListing> listing = ExchangeSymbolMapper.toListing(market, symbol);
        if (listing.isPresent()) {
            PairFilters next = listing.get().filters();
            Optional<PairFilters> previous = pair.filters(market);
            if (previous.isEmpty() || !sameFilters(previous.get(), next)) {
                if (previous.isPresent()) {
                    log.info(
                            "NSF-01 {} {} filters changed: tick {} -> {}, step {} -> {}, minNotional {} -> {}",
                            market,
                            pair.getSymbol(),
                            previous.get().tickSize(),
                            next.tickSize(),
                            previous.get().stepSize(),
                            next.stepSize(),
                            previous.get().minNotional(),
                            next.minNotional());
                    filterChanges.add(pair.getSymbol());
                }
                pair.applyFilters(market, next);
            }
        } else {
            log.warn(
                    "NSF-01 {} {} is listed but cannot be used (contract type {} or unusable filters); "
                            + "its filters are left as they were",
                    market,
                    pair.getSymbol(),
                    symbol.contractType());
        }
        ExchangeStatus now = symbol.isTrading() ? ExchangeStatus.TRADING : ExchangeStatus.NOT_TRADING;
        if (now != ExchangeStatus.TRADING && before != now) {
            flag(market, pair, "is no longer trading (" + symbol.status() + ")", flagged);
        }
        pair.recordExchangeStatus(market, now, symbol.status());
    }

    private void reconcileAbsent(MarketType market, CryptoPair pair, ExchangeStatus before, List<String> flagged) {
        boolean marketSwitchOn = market == MarketType.SPOT ? pair.isSpotEnabled() : pair.isFuturesEnabled();
        if (before == null && !marketSwitchOn) {
            return;
        }
        if (before != ExchangeStatus.DELISTED) {
            flag(market, pair, "no longer appears in the exchange information", flagged);
        }
        pair.recordExchangeStatus(market, ExchangeStatus.DELISTED, null);
    }

    private List<String> createMissingSeeds(
            MarketType market,
            Map<String, ExchangeSymbol> bySymbol,
            Collection<String> seeds,
            List<CryptoPair> universe) {
        List<String> created = new ArrayList<>();
        for (String seed : seeds) {
            boolean known = universe.stream().anyMatch(pair -> pair.getSymbol().equals(seed));
            ExchangeSymbol symbol = bySymbol.get(seed);
            if (known || symbol == null) {
                continue;
            }
            Optional<ExchangeListing> listing = ExchangeSymbolMapper.toListing(market, symbol);
            if (listing.isEmpty() || !listing.get().trading()) {
                log.info("NSF-01 {} seed symbol {} is listed but not usable or not trading; not created", market, seed);
                continue;
            }
            Coin base = coinFor(listing.get().baseAsset());
            Coin quote = coinFor(listing.get().quoteAsset());
            CryptoPair pair = pairs.save(CryptoPair.register(base.getId(), quote.getId(), seed));
            universe.add(pair);
            created.add(seed);
        }
        return created;
    }

    private Coin coinFor(String asset) {
        return coins.findBySymbol(asset).orElseGet(() -> coins.save(Coin.fromExchange(asset)));
    }

    private static void flag(MarketType market, CryptoPair pair, String reason, List<String> flagged) {
        log.warn("NSF-01 FLAG {} {} {}; an administrator should review it on SCR-37", market, pair.getSymbol(), reason);
        flagged.add(pair.getSymbol());
    }

    /** Filters compared by value, so {@code 0.10} from the exchange equals {@code 0.100000000000} read back. */
    private static boolean sameFilters(PairFilters a, PairFilters b) {
        return a.tickSize().compareTo(b.tickSize()) == 0
                && a.stepSize().compareTo(b.stepSize()) == 0
                && a.minNotional().compareTo(b.minNotional()) == 0;
    }
}
