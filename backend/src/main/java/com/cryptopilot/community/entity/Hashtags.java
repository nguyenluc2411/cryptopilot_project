package com.cryptopilot.community.entity;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The hashtags of a text, as CoinMarketCap Community reads them (D-99): a {@code #} not preceded by a letter, digit or
 * underscore, followed by 2 to 50 letters, digits or underscores of any script, so {@code #BitcoinHalving} and
 * {@code #tiềnảo} are hashtags and {@code a#b} or {@code #1} are not. A hashtag is kept in lower case without the
 * {@code #}, so {@code #BTC} and {@code #btc} are one topic; at most {@link #MAX_PER_POST}, the first ones written.
 *
 * <p>Rule: D-99.
 */
public final class Hashtags {

    /** Most hashtags one post is filed under. */
    public static final int MAX_PER_POST = 10;

    private static final Pattern HASHTAG =
            Pattern.compile("(?<![\\p{L}\\p{N}_])#([\\p{L}\\p{N}_]{2,50})(?![\\p{L}\\p{N}_])");

    private Hashtags() {}

    /** The hashtags of these texts, in the order they first appear. */
    public static Set<String> of(String... texts) {
        Set<String> found = new LinkedHashSet<>();
        for (String text : texts) {
            if (text == null) {
                continue;
            }
            Matcher matcher = HASHTAG.matcher(text);
            while (matcher.find() && found.size() < MAX_PER_POST) {
                String tag = matcher.group(1).toLowerCase(Locale.ROOT);
                if (tag.chars().anyMatch(Character::isLetter)) {
                    found.add(tag);
                }
            }
        }
        return found;
    }

    /** A hashtag as a filter names it: with or without its {@code #}, in any case. */
    public static String normalized(String hashtag) {
        String tag = hashtag.strip();
        return (tag.startsWith("#") ? tag.substring(1) : tag).toLowerCase(Locale.ROOT);
    }
}
