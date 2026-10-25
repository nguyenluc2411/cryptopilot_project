package com.cryptopilot.news.calculator;

import org.jsoup.Jsoup;

/**
 * The excerpt BR-48 allows an article to keep: the text of the feed's description with its HTML removed and its
 * entities decoded, at most 500 characters, cut on a word boundary and marked with an ellipsis when it was cut. Only
 * this is kept of an article's content; the full text never is.
 *
 * <p>Rule: BR-48; TECHNICAL_DESIGN 7.13.
 */
public final class Excerpt {

    /** The longest excerpt stored, the ellipsis included (BR-48). */
    public static final int MAX_LENGTH = 500;

    private static final String ELLIPSIS = "…";

    private Excerpt() {}

    /** The excerpt of {@code html}, or {@code null} when it holds no text. */
    public static String of(String html) {
        if (html == null || html.isBlank()) {
            return null;
        }
        String text = Jsoup.parse(html).text().strip();
        if (text.isEmpty()) {
            return null;
        }
        if (text.length() <= MAX_LENGTH) {
            return text;
        }
        int room = MAX_LENGTH - ELLIPSIS.length();
        int cut = text.lastIndexOf(' ', room);
        // One word longer than half the excerpt is cut where it must be rather than leaving almost nothing.
        if (cut < room / 2) {
            cut = room;
        }
        return text.substring(0, cut).stripTrailing() + ELLIPSIS;
    }
}
