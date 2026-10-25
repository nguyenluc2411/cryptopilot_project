package com.cryptopilot.news.calculator;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks that every figure a summary states is a figure of the article (NSF-11): a summary must not invent numbers, and
 * a number is the claim a reader acts on. Values are compared, not strings, so a model that writes a figure another way
 * is not refused for it:
 *
 * <ul>
 *   <li>Both separator conventions are read: {@code 81,700} and {@code 3.5} as English writes them, {@code 81.700} and
 *       {@code 3,5} as Vietnamese does. Where a figure could be either, it is supported when either reading is.
 *   <li>Scale words and suffixes are expanded: {@code $120 million}, {@code 120M} and {@code 120 triệu} are one value.
 *   <li>A figure equal to an article's figure, or equal to two significant figures, is supported: {@code $83.3K} may
 *       be written {@code $83,000}, but not {@code $88,000}.
 *   <li>Dates, times, years and ordinals are not claims about the market and are not checked.
 * </ul>
 *
 * Exact decimal arithmetic throughout: the figures are prices and amounts.
 *
 * <p>Rule: NSF-11; BR-51 (the assistant explains, it does not invent).
 */
public final class NumericGuard {

    private static final Pattern NUMBER = Pattern.compile("\\d[\\d,.]*\\d|\\d");
    private static final Pattern YEAR = Pattern.compile("(?:19|20)\\d{2}");
    private static final Pattern WORD_BEFORE = Pattern.compile("([\\p{L}.]+)\\s*$");
    private static final Pattern WORD_AFTER = Pattern.compile("^\\s*([\\p{L}.]+)");
    private static final Pattern GROUPED_BY_COMMA = Pattern.compile("\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?");
    private static final Pattern GROUPED_BY_DOT = Pattern.compile("\\d{1,3}(?:\\.\\d{3})+(?:,\\d+)?");

    private static final Set<String> MONTHS = Set.of(
            "jan",
            "feb",
            "mar",
            "apr",
            "may",
            "jun",
            "jul",
            "aug",
            "sep",
            "sept",
            "oct",
            "nov",
            "dec",
            "january",
            "february",
            "march",
            "april",
            "june",
            "july",
            "august",
            "september",
            "october",
            "november",
            "december",
            "tháng");
    private static final Set<String> DATE_WORDS_BEFORE = Set.of("ngày", "năm", "lúc", "quý", "q");
    private static final Set<String> TIME_OR_ORDINAL_AFTER =
            Set.of("am", "pm", "a.m.", "p.m.", "giờ", "h", "st", "nd", "rd", "th", "utc", "gmt");

    private static final Map<String, BigDecimal> SCALE_WORDS = Map.ofEntries(
            Map.entry("thousand", BigDecimal.valueOf(1_000L)),
            Map.entry("nghìn", BigDecimal.valueOf(1_000L)),
            Map.entry("ngàn", BigDecimal.valueOf(1_000L)),
            Map.entry("million", BigDecimal.valueOf(1_000_000L)),
            Map.entry("millions", BigDecimal.valueOf(1_000_000L)),
            Map.entry("triệu", BigDecimal.valueOf(1_000_000L)),
            Map.entry("billion", BigDecimal.valueOf(1_000_000_000L)),
            Map.entry("billions", BigDecimal.valueOf(1_000_000_000L)),
            Map.entry("tỷ", BigDecimal.valueOf(1_000_000_000L)),
            Map.entry("tỉ", BigDecimal.valueOf(1_000_000_000L)),
            Map.entry("trillion", BigDecimal.valueOf(1_000_000_000_000L)),
            Map.entry("nghìn tỷ", BigDecimal.valueOf(1_000_000_000_000L)));

    private static final Map<Character, BigDecimal> SCALE_SUFFIXES = Map.of(
            'k', BigDecimal.valueOf(1_000L),
            'm', BigDecimal.valueOf(1_000_000L),
            'b', BigDecimal.valueOf(1_000_000_000L),
            't', BigDecimal.valueOf(1_000_000_000_000L));

    private static final MathContext TWO_FIGURES = new MathContext(2);

    private NumericGuard() {}

    /** The figures of {@code summary} that no reading of any figure of {@code article} supports; empty when clean. */
    public static List<String> unsupported(String summary, String article) {
        List<BigDecimal> known = new ArrayList<>();
        for (Figure figure : figures(article, false)) {
            known.addAll(figure.readings());
        }
        List<String> offending = new ArrayList<>();
        for (Figure figure : figures(summary, true)) {
            boolean supported = figure.readings().stream().anyMatch(value -> supports(known, value));
            if (!supported && !offending.contains(figure.text())) {
                offending.add(figure.text());
            }
        }
        return offending;
    }

    private static boolean supports(List<BigDecimal> known, BigDecimal value) {
        BigDecimal rounded = value.round(TWO_FIGURES);
        for (BigDecimal candidate : known) {
            if (candidate.compareTo(value) == 0 || candidate.round(TWO_FIGURES).compareTo(rounded) == 0) {
                return true;
            }
        }
        return false;
    }

    /** The figures of a text with every value each may stand for; dates and times left out when asked. */
    private static List<Figure> figures(String text, boolean skipDates) {
        List<Figure> figures = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return figures;
        }
        Matcher matcher = NUMBER.matcher(text);
        while (matcher.find()) {
            String digits = matcher.group();
            int start = matcher.start();
            int end = matcher.end();
            if (skipDates && isDateOrTime(text, start, end, digits)) {
                continue;
            }
            BigDecimal scale = scale(text, end);
            Set<BigDecimal> readings = new LinkedHashSet<>();
            for (BigDecimal reading : readings(digits)) {
                readings.add(reading.multiply(scale));
            }
            if (!readings.isEmpty()) {
                figures.add(new Figure(digits, List.copyOf(readings)));
            }
        }
        return figures;
    }

    /**
     * The values a run of digits and separators may stand for: as English writes numbers (comma groups, dot decimal)
     * and as Vietnamese does (dot groups, comma decimal). A run neither convention can read stands for nothing.
     */
    static List<BigDecimal> readings(String digits) {
        Set<BigDecimal> values = new LinkedHashSet<>();
        boolean hasComma = digits.indexOf(',') >= 0;
        boolean hasDot = digits.indexOf('.') >= 0;
        if (!hasComma && !hasDot) {
            values.add(new BigDecimal(digits));
        }
        if (GROUPED_BY_COMMA.matcher(digits).matches()) {
            values.add(new BigDecimal(digits.replace(",", "")));
        }
        if (GROUPED_BY_DOT.matcher(digits).matches()) {
            values.add(new BigDecimal(digits.replace(".", "").replace(',', '.')));
        }
        if (hasDot && !hasComma && digits.indexOf('.') == digits.lastIndexOf('.')) {
            values.add(new BigDecimal(digits));
        }
        if (hasComma && !hasDot && digits.indexOf(',') == digits.lastIndexOf(',')) {
            values.add(new BigDecimal(digits.replace(',', '.')));
        }
        return List.copyOf(values);
    }

    private static boolean isDateOrTime(String text, int start, int end, String digits) {
        if ((start > 0 && text.charAt(start - 1) == ':') || (end < text.length() && text.charAt(end) == ':')) {
            return true;
        }
        if ((start > 0 && text.charAt(start - 1) == '/') || (end < text.length() && text.charAt(end) == '/')) {
            return true;
        }
        if (YEAR.matcher(digits).matches()
                && !dollarBefore(text, start)
                && scale(text, end).compareTo(BigDecimal.ONE) == 0
                && !percentAfter(text, end)) {
            return true;
        }
        String before = lastWord(text.substring(0, start));
        if (MONTHS.contains(before) || DATE_WORDS_BEFORE.contains(before)) {
            return true;
        }
        String after = firstWord(text.substring(end));
        return MONTHS.contains(after) || TIME_OR_ORDINAL_AFTER.contains(after);
    }

    /** The multiplier a scale word or suffix right after the figure implies; one when there is none. */
    private static BigDecimal scale(String text, int end) {
        if (end < text.length()) {
            char suffix = Character.toLowerCase(text.charAt(end));
            boolean alone = end + 1 >= text.length() || !Character.isLetter(text.charAt(end + 1));
            if (alone && SCALE_SUFFIXES.containsKey(suffix)) {
                return SCALE_SUFFIXES.get(suffix);
            }
        }
        String rest = text.substring(end).stripLeading().toLowerCase(Locale.ROOT);
        BigDecimal best = BigDecimal.ONE;
        int longest = 0;
        for (Map.Entry<String, BigDecimal> word : SCALE_WORDS.entrySet()) {
            String name = word.getKey();
            boolean whole = rest.startsWith(name)
                    && (rest.length() == name.length() || !Character.isLetter(rest.charAt(name.length())));
            if (whole && name.length() > longest) {
                best = word.getValue();
                longest = name.length();
            }
        }
        return best;
    }

    private static boolean percentAfter(String text, int end) {
        String rest = text.substring(end).stripLeading();
        return rest.startsWith("%");
    }

    private static boolean dollarBefore(String text, int start) {
        String before = text.substring(0, start).stripTrailing();
        return before.endsWith("$");
    }

    private static String lastWord(String text) {
        Matcher matcher = WORD_BEFORE.matcher(text);
        return matcher.find() ? strip(matcher.group(1)) : "";
    }

    private static String firstWord(String text) {
        Matcher matcher = WORD_AFTER.matcher(text);
        return matcher.find() ? strip(matcher.group(1)) : "";
    }

    /** Lower case, dots dropped except in the dotted {@code a.m.} and {@code p.m.}. */
    private static String strip(String word) {
        String lower = word.toLowerCase(Locale.ROOT);
        return lower.equals("a.m.") || lower.equals("p.m.") ? lower : lower.replace(".", "");
    }

    /** A figure as written, and the values it may stand for. */
    private record Figure(String text, List<BigDecimal> readings) {}
}
