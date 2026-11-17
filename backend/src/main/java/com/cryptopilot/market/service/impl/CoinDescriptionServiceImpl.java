package com.cryptopilot.market.service.impl;

import com.cryptopilot.market.client.CoinPaprikaClient;
import com.cryptopilot.market.client.CoinPaprikaCoin;
import com.cryptopilot.market.client.CoinPaprikaException;
import com.cryptopilot.market.client.CoinPaprikaProperties;
import com.cryptopilot.market.entity.Coin;
import com.cryptopilot.market.model.CoinPaprikaDirectory;
import com.cryptopilot.market.repository.CoinRepository;
import com.cryptopilot.market.service.CoinDescriptionService;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Matches the coins Binance lists with CoinPaprika's coin list and fills in their names and logos.
 *
 * <h2>How a coin is matched</h2>
 *
 * <p>By ticker, the only thing the two share. A ticker is not unique (many tokens call themselves {@code BTC}), so of
 * the tracked, ranked coins with the ticker the one with the best rank by market cap wins: the coin a major exchange
 * lists is the large one. Where that is still wrong, because CoinPaprika spells the coin otherwise (Toncoin is
 * {@code GRAM} there) or a larger coin shares its ticker, configuration names the CoinPaprika id by symbol
 * ({@link CoinPaprikaProperties#overrides()}). A Binance Futures contract that counts in thousands ({@code 1000PEPE},
 * {@code 1MBABYDOGE}) takes the logo of its coin ({@code PEPE}) but keeps its own name, which is not the coin's.
 *
 * <h2>What is read</h2>
 *
 * <p>The list once per run, then one coin at a time for the logo, which the list lacks: only for a coin still without
 * a logo, each id at most once, with a pause between two calls. A coin is described once, so after the first run only
 * newly listed coins cost a call.
 *
 * <h2>What a failure does</h2>
 *
 * <p>Everything is read before anything is written, outside any transaction, so the database is never held while
 * CoinPaprika is waited for. A failed list writes nothing and is thrown. A coin CoinPaprika refuses (a 4xx) goes
 * without a logo this time; a rate limit or no answer stops the logo calls, and the names and logos read so far are
 * still written. A coin not found stays as it is, its symbol for a name and no logo, which the pages show with a
 * letter badge.
 *
 * <p>Rule: SRS 3.1.5; BR-09.
 */
public class CoinDescriptionServiceImpl implements CoinDescriptionService {

    private static final Logger log = LoggerFactory.getLogger(CoinDescriptionServiceImpl.class);

    /** A contract quoted per thousand or per million of its coin, and the coin. */
    private static final Pattern MULTIPLIED = Pattern.compile("^(?:1000000|1000|1M)([A-Z][A-Z0-9]*)$");

    private final CoinRepository coins;
    private final CoinPaprikaClient client;
    private final CoinPaprikaProperties properties;
    private final TransactionTemplate readWrite;

    /**
     * Symbols already tried in this process with nothing more CoinPaprika can give (no match, or a match without a
     * name or logo to add). While every undescribed coin is one of them, a run reads nothing.
     */
    private final Set<String> settled = ConcurrentHashMap.newKeySet();

    public CoinDescriptionServiceImpl(
            CoinRepository coins,
            CoinPaprikaClient client,
            CoinPaprikaProperties properties,
            PlatformTransactionManager transactions) {
        this.coins = coins;
        this.client = client;
        this.properties = properties;
        this.readWrite = new TransactionTemplate(transactions);
    }

    /** What one coin is to become. */
    private record Description(String name, String logo) {}

    @Override
    public int describeCoins() {
        List<Coin> undescribed = coins.findUndescribed();
        if (undescribed.isEmpty() || undescribed.stream().allMatch(coin -> settled.contains(coin.getSymbol()))) {
            return 0;
        }
        CoinPaprikaDirectory directory = new CoinPaprikaDirectory(client.coins(), properties.overrides());
        Logos logos = new Logos();
        Map<UUID, Description> planned = new HashMap<>();
        Set<String> tried = new HashSet<>();
        for (Coin coin : undescribed) {
            Optional<CoinPaprikaCoin> same = directory.find(coin.getSymbol());
            Optional<CoinPaprikaCoin> logoOf =
                    same.isPresent() ? same : underlying(coin.getSymbol()).flatMap(directory::find);
            if (logoOf.isEmpty()) {
                tried.add(coin.getSymbol());
                continue;
            }
            String name = same.map(CoinPaprikaCoin::name).orElse(null);
            String logo = coin.getLogoUrl() == null ? logos.of(logoOf.get().id()) : null;
            if (coin.getLogoUrl() != null || logos.answered(logoOf.get().id())) {
                tried.add(coin.getSymbol());
            }
            if (name != null || logo != null) {
                planned.put(coin.getId(), new Description(name, logo));
            }
        }
        if (planned.isEmpty()) {
            settled.addAll(tried);
            return 0;
        }
        Integer changed = readWrite.execute(status -> {
            int count = 0;
            for (Coin coin : coins.findAllByIdIn(planned.keySet())) {
                Description description = planned.get(coin.getId());
                if (description != null && coin.describe(description.name(), description.logo())) {
                    coins.save(coin);
                    count++;
                }
            }
            return count;
        });
        int result = changed == null ? 0 : changed;
        settled.addAll(tried);
        log.info(
                "Coin descriptions: {} of {} undescribed coins described from CoinPaprika", result, undescribed.size());
        return result;
    }

    /** {@code PEPE} for {@code 1000PEPE}, or empty for a symbol that is a coin itself. */
    static Optional<String> underlying(String symbol) {
        Matcher matcher = MULTIPLIED.matcher(symbol);
        return matcher.matches() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    /** The logo calls of one run: each id at most once, paced, stopped by a rate limit or an outage. */
    private final class Logos {

        private final Map<String, Optional<String>> read = new HashMap<>();
        private boolean stopped;
        private int calls;

        String of(String id) {
            Optional<String> known = read.get(id);
            if (known != null) {
                return known.orElse(null);
            }
            if (stopped) {
                return null;
            }
            if (calls++ > 0) {
                pause(properties.detailDelay());
            }
            Optional<String> logo;
            try {
                logo = client.logo(id);
            } catch (CoinPaprikaException failure) {
                if (failure.serviceAnswered() && !failure.rateLimited()) {
                    logo = Optional.empty();
                } else {
                    stopped = true;
                    log.warn(
                            "Coin descriptions: CoinPaprika logo calls stopped ({}); the next synchronisation goes on",
                            failure.getMessage());
                    return null;
                }
            }
            read.put(id, logo);
            return logo.orElse(null);
        }

        /** Whether CoinPaprika answered for this id in this run (a logo or none), rather than the calls stopping. */
        boolean answered(String id) {
            return read.containsKey(id);
        }
    }

    private static void pause(Duration delay) {
        if (delay.isZero() || delay.isNegative()) {
            return;
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CoinPaprikaException("interrupted between CoinPaprika calls", 0, e);
        }
    }
}
