package com.cryptopilot.news.calculator;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * The SHA-256 of a normalized address or title, as the 64 lower-case hex characters {@code news_article.url_hash} and
 * {@code title_hash} hold. Equal normalized texts give equal hashes, which is all deduplication asks of it.
 *
 * <p>Rule: BR-48; TECHNICAL_DESIGN 7.13.
 *
 * <p>Reference: NIST (2015). FIPS 180-4, <i>Secure Hash Standard</i>.
 */
public final class ContentHash {

    private ContentHash() {}

    public static String sha256(String text) {
        Objects.requireNonNull(text, "text must not be null");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every Java platform provides SHA-256", e);
        }
    }
}
