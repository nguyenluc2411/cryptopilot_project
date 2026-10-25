package com.cryptopilot.academy.client;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Cover photos on Cloudinary. An upload is signed for a new random key in the cover folder, images only; an address is
 * accepted when it names a photo of that folder on this account, whatever transformation it carries, and is stored
 * with the cover transformation instead.
 *
 * <p>Rule: T-114.
 *
 * <p>Reference: Cloudinary, "Upload API reference" and "Authentication signatures" (signed upload parameters are sorted,
 * joined with {@code &} and hashed with SHA-1 together with the API secret).
 */
public class CloudinaryCoverStorage implements CoverStorage {

    /** The formats a cover may have; Cloudinary refuses any other file of the signed upload. */
    static final List<String> FORMATS = List.of("jpg", "jpeg", "png", "webp");

    private static final Pattern VERSION = Pattern.compile("v\\d+");

    private final CoverStorageProperties properties;
    private final Clock clock;
    private final Pattern photo;

    public CloudinaryCoverStorage(CoverStorageProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        this.photo = Pattern.compile(
                "(v\\d+/)?" + Pattern.quote(properties.folder()) + "/[A-Za-z0-9_-]+\\.(jpe?g|png|webp)");
    }

    @Override
    public CoverUploadTicket sign() {
        String publicId = properties.folder() + "/" + UUID.randomUUID();
        long timestamp = clock.instant().getEpochSecond();
        String formats = String.join(",", FORMATS);
        String signature = signature(
                Map.of(
                        "public_id",
                        publicId,
                        "timestamp",
                        Long.toString(timestamp),
                        "allowed_formats",
                        formats,
                        "overwrite",
                        "false"),
                properties.apiSecret());
        return new CoverUploadTicket(
                properties
                        .apiBaseUrl()
                        .resolve("/v1_1/" + properties.cloudName() + "/image/upload")
                        .toString(),
                properties.apiKey(),
                timestamp,
                signature,
                publicId,
                FORMATS);
    }

    @Override
    public Optional<String> accept(String coverImageUrl) {
        String prefix = properties
                .deliveryBaseUrl()
                .resolve("/" + properties.cloudName() + "/image/upload/")
                .toString();
        if (coverImageUrl == null || !coverImageUrl.startsWith(prefix)) {
            return Optional.empty();
        }
        // Drop the transformation segments the address carries: what follows them is the version and the key.
        String[] segments = coverImageUrl.substring(prefix.length()).split("/");
        String firstFolder = properties.folder().split("/")[0];
        int start = 0;
        while (start < segments.length
                && !VERSION.matcher(segments[start]).matches()
                && !segments[start].equals(firstFolder)) {
            start++;
        }
        String rest = String.join("/", List.of(segments).subList(start, segments.length));
        if (!photo.matcher(rest).matches()) {
            return Optional.empty();
        }
        return Optional.of(prefix + properties.transformation() + "/" + rest);
    }

    static String signature(Map<String, String> params, String apiSecret) {
        String toSign = new TreeMap<>(params)
                .entrySet().stream()
                        .map(param -> param.getKey() + "=" + param.getValue())
                        .collect(Collectors.joining("&"));
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-1").digest((toSign + apiSecret).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException unavailable) {
            // Every Java platform must provide SHA-1 (MessageDigest specification).
            throw new IllegalStateException("SHA-1 is not available", unavailable);
        }
    }
}
