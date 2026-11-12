package com.cryptopilot.journal.client;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Chart images on Cloudinary, over its REST API: a signed upload form for a new random key in the owner's folder,
 * images only and never overwriting; the Admin API to read back a stored image; and the signed {@code destroy} call.
 *
 * <p>Rule: BR-40; SRS 3.6.1; D-98.
 *
 * <p>Reference: Cloudinary, "Upload API reference", "Admin API" (details of a single resource) and "Authentication
 * signatures" (signed parameters sorted, joined with {@code &} and hashed with SHA-1 together with the secret).
 */
public class CloudinaryChartImageStorage implements ChartImageStorage {

    /** The formats a chart image may have (SRS 3.6.1); Cloudinary refuses any other file of the signed upload. */
    public static final List<String> FORMATS = List.of("jpg", "jpeg", "png", "webp");

    private static final Pattern RANDOM_KEY =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT =
            new ParameterizedTypeReference<>() {};

    private final ChartImageStorageProperties properties;
    private final Clock clock;
    private final RestClient rest;
    private final String basicAuth;

    public CloudinaryChartImageStorage(ChartImageStorageProperties properties, Clock clock) {
        if (!properties.configured()) {
            throw new IllegalArgumentException("no Cloudinary account is configured");
        }
        this.properties = properties;
        this.clock = clock;
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(http);
        requests.setReadTimeout(properties.readTimeout());
        this.rest = RestClient.builder()
                .baseUrl(properties.apiBaseUrl().toString())
                .requestFactory(requests)
                .build();
        this.basicAuth = "Basic "
                + Base64.getEncoder()
                        .encodeToString(
                                (properties.apiKey() + ":" + properties.apiSecret()).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public ChartImageUploadTicket sign(UUID owner) {
        String publicId = folderOf(owner) + UUID.randomUUID();
        long timestamp = clock.instant().getEpochSecond();
        String signature = signature(
                Map.of(
                        "public_id",
                        publicId,
                        "timestamp",
                        Long.toString(timestamp),
                        "allowed_formats",
                        String.join(",", FORMATS),
                        "overwrite",
                        "false"),
                properties.apiSecret());
        return new ChartImageUploadTicket(
                properties.apiBaseUrl() + "/v1_1/" + properties.cloudName() + "/image/upload",
                properties.apiKey(),
                timestamp,
                signature,
                publicId,
                FORMATS);
    }

    @Override
    public boolean issuedTo(UUID owner, String publicId) {
        String folder = folderOf(owner);
        return publicId != null
                && publicId.startsWith(folder)
                && RANDOM_KEY.matcher(publicId.substring(folder.length())).matches();
    }

    @Override
    public Optional<StoredChartImage> describe(String publicId) {
        Map<String, Object> body;
        try {
            body = rest.get()
                    .uri(URI.create(properties.apiBaseUrl() + "/v1_1/" + properties.cloudName()
                            + "/resources/image/upload/" + publicId))
                    .header(HttpHeaders.AUTHORIZATION, basicAuth)
                    .retrieve()
                    .body(JSON_OBJECT);
        } catch (HttpClientErrorException.NotFound nothingStored) {
            return Optional.empty();
        } catch (RestClientException failed) {
            throw new ChartImageStorageException(
                    "Cloudinary did not describe " + publicId + ": " + failed.getMessage(), failed);
        }
        if (body == null
                || !(body.get("secure_url") instanceof String url)
                || !(body.get("format") instanceof String format)
                || !(body.get("bytes") instanceof Number bytes)) {
            throw new ChartImageStorageException("Cloudinary described " + publicId + " without url, format or bytes");
        }
        return Optional.of(new StoredChartImage(publicId, format.toLowerCase(Locale.ROOT), bytes.longValue(), url));
    }

    @Override
    public void destroy(String publicId) {
        String timestamp = Long.toString(clock.instant().getEpochSecond());
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("public_id", publicId);
        form.add("timestamp", timestamp);
        form.add("api_key", properties.apiKey());
        form.add("signature", signature(Map.of("public_id", publicId, "timestamp", timestamp), properties.apiSecret()));
        Map<String, Object> body;
        try {
            body = rest.post()
                    .uri("/v1_1/" + properties.cloudName() + "/image/destroy")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(JSON_OBJECT);
        } catch (RestClientException failed) {
            throw new ChartImageStorageException(
                    "Cloudinary did not destroy " + publicId + ": " + failed.getMessage(), failed);
        }
        Object result = body == null ? null : body.get("result");
        // A key with no image left is what a delete wants.
        if (!"ok".equals(result) && !"not found".equals(result)) {
            throw new ChartImageStorageException("Cloudinary answered " + result + " to destroying " + publicId);
        }
    }

    private String folderOf(UUID owner) {
        return properties.folder() + "/" + owner + "/";
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
