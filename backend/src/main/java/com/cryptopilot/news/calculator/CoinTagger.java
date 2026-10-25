package com.cryptopilot.news.calculator;

import com.cryptopilot.news.model.CoinTag;
import com.cryptopilot.news.model.TaggableCoin;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Finds the coins an article mentions (NSF-10), with two expressions compiled once per crawl from the coins in the
 * system:
 *
 * <ul>
 *   <li>Symbols match in upper case only, as whole words, so {@code BTC} tags Bitcoin but {@code one} or {@code near}
 *       in a sentence tags nothing. A symbol shorter than three characters, which is too often a word or an
 *       abbreviation, counts only with a {@code $} before it ({@code $OP}).
 *   <li>Names match in any case, as whole words. A name that spells a symbol of any coin is not used as a name, or the
 *       lower-case false positives the symbol rule removes would come back through it; this matters because a coin the
 *       exchange synchronisation creates is named after its symbol.
 * </ul>
 *
 * Longer alternatives are tried first, so {@code Bitcoin Cash} is one mention of BCH and not also one of BTC. The
 * primary coin is the one the title names first; when the title names none, no coin is primary.
 *
 * <p>Rule: NSF-10; TECHNICAL_DESIGN 7.13.
 *
 * <p>Reference: Friedl, J. (2006). <i>Mastering Regular Expressions</i>, 3rd ed. O'Reilly, ch. 4 (an alternation is
 * tried left to right, so the longer alternative must come first).
 */
public final class CoinTagger {

    /** Symbols shorter than this need a {@code $} before them. */
    static final int MIN_BARE_SYMBOL_LENGTH = 3;

    private static final String BEFORE = "(?<![\\p{L}\\p{N}])";
    private static final String AFTER = "(?![\\p{L}\\p{N}])";

    private final Pattern symbols;
    private final Pattern names;
    private final Map<String, UUID> bySymbol;
    private final Map<String, UUID> byName;

    private CoinTagger(Pattern symbols, Pattern names, Map<String, UUID> bySymbol, Map<String, UUID> byName) {
        this.symbols = symbols;
        this.names = names;
        this.bySymbol = bySymbol;
        this.byName = byName;
    }

    /** A tagger for these coins; a name two coins share is left out, since it cannot say which one is meant. */
    public static CoinTagger of(Collection<TaggableCoin> coins) {
        Map<String, UUID> bySymbol = new HashMap<>();
        for (TaggableCoin coin : coins) {
            bySymbol.put(coin.symbol(), coin.coinId());
        }
        Set<String> symbolsAnyCase = bySymbol.keySet().stream()
                .map(symbol -> symbol.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        Map<String, UUID> byName = new HashMap<>();
        Set<String> shared = new HashSet<>();
        for (TaggableCoin coin : coins) {
            for (String name : coin.names()) {
                String key = name.strip().toLowerCase(Locale.ROOT);
                if (key.isEmpty() || symbolsAnyCase.contains(key)) {
                    continue;
                }
                UUID previous = byName.putIfAbsent(key, coin.coinId());
                if (previous != null && !previous.equals(coin.coinId())) {
                    shared.add(key);
                }
            }
        }
        shared.forEach(byName::remove);
        return new CoinTagger(
                alternation("(\\$?)", bySymbol.keySet(), 0),
                alternation("", byName.keySet(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE),
                Map.copyOf(bySymbol),
                Map.copyOf(byName));
    }

    /**
     * The coins {@code title} and {@code text} mention, each once: the primary one first, then the others in the order
     * they first appear, the title before the text.
     */
    public List<CoinTag> tag(String title, String text) {
        Map<UUID, Integer> inTitle = mentions(title);
        Map<UUID, Integer> order = new LinkedHashMap<>();
        inTitle.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .forEach(entry -> order.putIfAbsent(entry.getKey(), order.size()));
        mentions(text).entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .forEach(entry -> order.putIfAbsent(entry.getKey(), order.size()));
        UUID primary = inTitle.entrySet().stream()
                .min(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
        List<CoinTag> tags = new ArrayList<>();
        for (UUID coin : order.keySet()) {
            tags.add(new CoinTag(coin, coin.equals(primary)));
        }
        return tags;
    }

    /** Each coin {@code text} mentions, with the position of its first mention. */
    private Map<UUID, Integer> mentions(String text) {
        Map<UUID, Integer> found = new HashMap<>();
        if (text == null || text.isEmpty()) {
            return found;
        }
        if (symbols != null) {
            Matcher matcher = symbols.matcher(text);
            while (matcher.find()) {
                String symbol = matcher.group(2);
                boolean dollar = !matcher.group(1).isEmpty();
                if (dollar || symbol.length() >= MIN_BARE_SYMBOL_LENGTH) {
                    found.putIfAbsent(bySymbol.get(symbol), matcher.start());
                }
            }
        }
        if (names != null) {
            Matcher matcher = names.matcher(text);
            while (matcher.find()) {
                found.merge(byName.get(matcher.group(1).toLowerCase(Locale.ROOT)), matcher.start(), Math::min);
            }
        }
        return found;
    }

    /** {@code prefix} then one of the words as group 1 or 2, longest first, as a whole word; null when none. */
    private static Pattern alternation(String prefix, Collection<String> words, int flags) {
        if (words.isEmpty()) {
            return null;
        }
        String alternatives = words.stream()
                .sorted(Comparator.comparingInt(String::length).reversed().thenComparing(Comparator.naturalOrder()))
                .map(Pattern::quote)
                .collect(Collectors.joining("|"));
        return Pattern.compile(BEFORE + prefix + "(" + alternatives + ")" + AFTER, flags);
    }
}
