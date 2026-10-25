package com.cryptopilot.news.calculator;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The form of a headline that the same story keeps across outlets that punctuate or capitalise it differently: Unicode
 * NFKC, lower case, punctuation and symbols replaced by a space, runs of white space collapsed, ends trimmed. Two
 * articles whose normalized titles are equal and whose publish times are within 48 hours are one story (BR-48).
 *
 * <p>Rule: BR-48; TECHNICAL_DESIGN 7.13.
 *
 * <p>Reference: Davis, M. &amp; Whistler, K. (2024). Unicode Standard Annex #15, <i>Unicode Normalization Forms</i>
 * (NFKC folds compatibility characters such as full-width letters and ligatures).
 */
public final class TitleNormalizer {

    private static final Pattern PUNCTUATION = Pattern.compile("[\\p{P}\\p{S}]+");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private TitleNormalizer() {}

    public static String normalize(String title) {
        Objects.requireNonNull(title, "title must not be null");
        String folded = Normalizer.normalize(title, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        String spaced = PUNCTUATION.matcher(folded).replaceAll(" ");
        return SPACES.matcher(spaced).replaceAll(" ").strip();
    }
}
