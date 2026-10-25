package com.cryptopilot.news.calculator;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * The readable text of an article page, for the summary only: it is never stored (BR-48). The source's body selectors
 * are tried in the order the administrator wrote them and the first that matches with text wins; the excluded
 * selectors (related links, widgets, FAQ blocks) are removed first, together with scripts and styles. Paragraphs are
 * kept apart by a blank line, so the model reads them as such.
 *
 * <p>Rule: BR-48; NSF-11; UC-49 (the per-source selectors).
 */
public final class ArticleText {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final String ALWAYS_EXCLUDED = "script, style, noscript, template, iframe, svg, form";
    private static final String BLOCKS = "p, h2, h3, h4, li, blockquote";

    private ArticleText() {}

    /**
     * The text of the page under the first body selector that holds any, cut to {@code maxWords} words; empty when no
     * selector matches.
     *
     * @param page the page as the server sent it; its charset is read from the page itself
     * @param bodySelector comma-separated CSS selectors, the first that matches wins
     * @param excludedSelectors CSS selectors removed before reading, or {@code null}
     */
    public static Optional<String> extract(
            byte[] page, String pageUrl, String bodySelector, String excludedSelectors, int maxWords) {
        if (bodySelector == null || bodySelector.isBlank()) {
            return Optional.empty();
        }
        Document document;
        try {
            document = Jsoup.parse(new ByteArrayInputStream(page), null, pageUrl);
        } catch (IOException e) {
            throw new UncheckedIOException("an in-memory page cannot fail to read", e);
        }
        document.select(ALWAYS_EXCLUDED).remove();
        if (excludedSelectors != null && !excludedSelectors.isBlank()) {
            document.select(excludedSelectors).remove();
        }
        for (String selector : alternatives(bodySelector)) {
            Element body = document.selectFirst(selector);
            if (body != null) {
                String text = text(body);
                if (!text.isBlank()) {
                    return Optional.of(firstWords(text, maxWords));
                }
            }
        }
        return Optional.empty();
    }

    /** How many words a text has, to tell a real article body from a cookie banner or a paywall stub. */
    public static int words(String text) {
        String trimmed = text == null ? "" : text.strip();
        return trimmed.isEmpty() ? 0 : WHITESPACE.split(trimmed).length;
    }

    /** The paragraphs of an element, or its whole text when it has no block children. */
    private static String text(Element body) {
        List<String> paragraphs = new ArrayList<>();
        for (Element block : body.select(BLOCKS)) {
            if (block.parents().stream().noneMatch(parent -> parent != body && parent.is(BLOCKS))) {
                String text = block.text().strip();
                if (!text.isEmpty()) {
                    paragraphs.add(text);
                }
            }
        }
        return paragraphs.isEmpty() ? body.text().strip() : String.join("\n\n", paragraphs);
    }

    /**
     * The comma-separated selectors of a list, split only at the top level, so a comma inside an attribute value or
     * a {@code :is(...)} group stays part of its selector.
     */
    static List<String> alternatives(String selectors) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        char quote = 0;
        StringBuilder current = new StringBuilder();
        for (char c : selectors.toCharArray()) {
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '(' || c == '[') {
                depth++;
            } else if ((c == ')' || c == ']') && depth > 0) {
                depth--;
            } else if (c == ',' && depth == 0) {
                addIfPresent(parts, current);
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        addIfPresent(parts, current);
        return parts;
    }

    private static void addIfPresent(List<String> parts, StringBuilder selector) {
        String part = selector.toString().strip();
        if (!part.isEmpty()) {
            parts.add(part);
        }
    }

    private static String firstWords(String text, int maxWords) {
        int seen = 0;
        int index = 0;
        int length = text.length();
        while (index < length && seen < maxWords) {
            while (index < length && Character.isWhitespace(text.charAt(index))) {
                index++;
            }
            if (index < length) {
                seen++;
            }
            while (index < length && !Character.isWhitespace(text.charAt(index))) {
                index++;
            }
        }
        return text.substring(0, index).strip();
    }
}
