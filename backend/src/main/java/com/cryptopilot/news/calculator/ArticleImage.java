package com.cryptopilot.news.calculator;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * The cover image of an article (D-129), by its address only: the image is shown from the outlet and never copied.
 * The feed's image is preferred; an item without one takes the page's {@code og:image}, else its
 * {@code twitter:image}, which the outlets set for link previews.
 *
 * <p>An address is kept only when it is an absolute {@code http} or {@code https} address that fits the column, so a
 * page cannot make the feed load a {@code data:} or {@code javascript:} address.
 *
 * <p>Rule: D-129; BR-48.
 */
public final class ArticleImage {

    /** The length of {@code news_article.image_url}. */
    static final int MAX_URL_LENGTH = 2048;

    /** The preview tags, in the order they are preferred. */
    private static final List<String> META = List.of(
            "meta[property=og:image]",
            "meta[property=og:image:url]",
            "meta[name=twitter:image]",
            "meta[property=twitter:image]");

    private ArticleImage() {}

    /** {@code address} made absolute against {@code base} when it is a usable image address, else empty. */
    public static Optional<String> of(String address, String base) {
        if (address == null || address.isBlank()) {
            return Optional.empty();
        }
        try {
            URI uri = new URI(address.strip().replace(" ", "%20"));
            if (!uri.isAbsolute() && base != null) {
                uri = new URI(base).resolve(uri);
            }
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https") || uri.getHost() == null) {
                return Optional.empty();
            }
            String absolute = uri.toString();
            return absolute.length() <= MAX_URL_LENGTH ? Optional.of(absolute) : Optional.empty();
        } catch (URISyntaxException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** The page's preview image, made absolute against the page's address, or empty when it names none. */
    public static Optional<String> fromPage(byte[] page, String pageUrl) {
        Document document;
        try {
            document = Jsoup.parse(new ByteArrayInputStream(page), null, pageUrl);
        } catch (IOException e) {
            throw new UncheckedIOException("an in-memory page cannot fail to read", e);
        }
        for (String selector : META) {
            for (Element meta : document.select(selector)) {
                Optional<String> image = of(meta.attr("content"), pageUrl);
                if (image.isPresent()) {
                    return image;
                }
            }
        }
        return Optional.empty();
    }
}
