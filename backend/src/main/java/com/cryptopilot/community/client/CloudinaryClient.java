package com.cryptopilot.community.client;

import com.cryptopilot.community.model.enums.PostMediaType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * The {@link MediaStorage} on a Cloudinary account, over its REST API: a signed upload form for the browser, the
 * Admin API to read back a stored file and to list a folder, and the signed {@code destroy} call. No SDK: four calls
 * do not justify a new dependency (TECHNICAL_DESIGN 1.3), and the signature is a documented hash.
 *
 * <p>Only the backend holds the secret. A form is signed for one {@code public_id}, the formats it may have, the issue
 * time and {@code overwrite=false}, and Cloudinary refuses a signed form whose time is more than an hour old. A leaked
 * form can store a file under its key only while the key is free: once a file is there, posting the form again cannot
 * replace it, so a file already checked and attached stays the one that was checked. The file is then read back with
 * the Admin API, authenticated with the key and secret, and only what Cloudinary reports is trusted.
 *
 * <p>A key is the folder, the uploader's account and a random part, all of {@code [A-Za-z0-9_-]}, so it is written in
 * an address as it is; any other key is refused before a request is made.
 *
 * <p>Rule: BR-44; D-98.
 *
 * <p>Reference: Cloudinary. <i>Upload API reference</i> (signed uploads, the {@code overwrite} parameter), <i>Admin
 * API</i> ("Get details of a single resource", "Get resources" by prefix), <i>Authentication signatures</i>
 * (Cloudinary documentation, 2025). OWASP. <i>File Upload Cheat Sheet</i> (OWASP Cheat Sheet Series, 2024).
 */
public class CloudinaryClient implements MediaStorage {

    private static final Pattern SAFE_PUBLIC_ID = Pattern.compile("[A-Za-z0-9_-]+(/[A-Za-z0-9_-]+)*");

    /** Files per page of a listing: the most the Admin API returns in one call. */
    static final int LIST_PAGE_SIZE = 500;

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT =
            new ParameterizedTypeReference<>() {};

    private final CloudinaryProperties properties;
    private final Clock clock;
    private final RestClient rest;
    private final String basicAuth;

    public CloudinaryClient(CloudinaryProperties properties, Clock clock) {
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
    public UploadTicket sign(PostMediaType type, String publicId, List<String> allowedFormats) {
        String id = safe(publicId);
        long timestamp = clock.instant().getEpochSecond();
        String formats = String.join(",", allowedFormats);
        String signature = CloudinarySignature.sign(
                Map.of(
                        "public_id",
                        id,
                        "timestamp",
                        Long.toString(timestamp),
                        "allowed_formats",
                        formats,
                        "overwrite",
                        "false"),
                properties.apiSecret());
        return new UploadTicket(
                properties.apiBaseUrl().resolve(path(type, "upload")).toString(),
                properties.apiKey(),
                timestamp,
                signature,
                id,
                allowedFormats,
                false);
    }

    @Override
    public Optional<StoredMedia> describe(PostMediaType type, String publicId) {
        String id = safe(publicId);
        Map<String, Object> body;
        try {
            body = rest.get()
                    .uri(URI.create(properties.apiBaseUrl() + "/v1_1/" + properties.cloudName() + "/resources/"
                            + resourceType(type) + "/upload/" + id))
                    .header(HttpHeaders.AUTHORIZATION, basicAuth)
                    .retrieve()
                    .body(JSON_OBJECT);
        } catch (HttpClientErrorException.NotFound nothingStored) {
            return Optional.empty();
        } catch (RestClientException failed) {
            throw new MediaStorageException("Cloudinary did not describe " + id + ": " + failed.getMessage(), failed);
        }
        if (body == null) {
            throw new MediaStorageException("Cloudinary described " + id + " with an empty body");
        }
        return Optional.of(stored(type, id, body));
    }

    @Override
    public void destroy(PostMediaType type, String publicId) {
        String id = safe(publicId);
        String timestamp = Long.toString(clock.instant().getEpochSecond());
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("public_id", id);
        form.add("timestamp", timestamp);
        form.add("api_key", properties.apiKey());
        form.add(
                "signature",
                CloudinarySignature.sign(Map.of("public_id", id, "timestamp", timestamp), properties.apiSecret()));
        Map<String, Object> body;
        try {
            body = rest.post()
                    .uri(path(type, "destroy"))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(JSON_OBJECT);
        } catch (RestClientException failed) {
            throw new MediaStorageException("Cloudinary did not destroy " + id + ": " + failed.getMessage(), failed);
        }
        Object result = body == null ? null : body.get("result");
        // "not found" is a file already gone, which is what a delete wants.
        if (!"ok".equals(result) && !"not found".equals(result)) {
            throw new MediaStorageException("Cloudinary answered " + result + " to destroying " + id);
        }
    }

    @Override
    public StoredFilePage list(PostMediaType type, String prefix, String cursor) {
        String folder = safe(prefix);
        UriComponentsBuilder uri = UriComponentsBuilder.fromUri(properties.apiBaseUrl())
                .path("/v1_1/" + properties.cloudName() + "/resources/" + resourceType(type) + "/upload")
                .queryParam("prefix", folder + "/")
                .queryParam("max_results", LIST_PAGE_SIZE)
                .queryParam("direction", "asc");
        if (cursor != null) {
            uri.queryParam("next_cursor", cursor);
        }
        Map<String, Object> body;
        try {
            body = rest.get()
                    .uri(uri.encode().build().toUri())
                    .header(HttpHeaders.AUTHORIZATION, basicAuth)
                    .retrieve()
                    .body(JSON_OBJECT);
        } catch (RestClientException failed) {
            throw new MediaStorageException("Cloudinary did not list " + folder + ": " + failed.getMessage(), failed);
        }
        if (body == null || !(body.get("resources") instanceof List<?> resources)) {
            throw new MediaStorageException("Cloudinary listed " + folder + " without resources");
        }
        List<StoredFilePage.File> files = new ArrayList<>();
        for (Object resource : resources) {
            if (resource instanceof Map<?, ?> file
                    && file.get("public_id") instanceof String publicId
                    && file.get("created_at") instanceof String createdAt) {
                files.add(new StoredFilePage.File(publicId, Instant.parse(createdAt)));
            }
        }
        return new StoredFilePage(files, text(body, "next_cursor"));
    }

    private String path(PostMediaType type, String action) {
        return "/v1_1/" + properties.cloudName() + "/" + resourceType(type) + "/" + action;
    }

    private static String resourceType(PostMediaType type) {
        return type.name().toLowerCase(Locale.ROOT);
    }

    private static String safe(String publicId) {
        if (publicId == null || !SAFE_PUBLIC_ID.matcher(publicId).matches()) {
            throw new IllegalArgumentException("not a media key this module issues: " + publicId);
        }
        return publicId;
    }

    static StoredMedia stored(PostMediaType type, String publicId, Map<String, Object> body) {
        String url = text(body, "secure_url");
        String format = text(body, "format");
        Number bytes = (Number) body.get("bytes");
        if (url == null || format == null || bytes == null) {
            throw new MediaStorageException("Cloudinary described " + publicId + " without url, format or bytes");
        }
        Integer duration = null;
        String thumbnail = null;
        if (type == PostMediaType.VIDEO) {
            Object seconds = body.get("duration");
            if (seconds instanceof Number number) {
                duration = new BigDecimal(number.toString())
                        .setScale(0, RoundingMode.CEILING)
                        .intValueExact();
            }
            // Cloudinary serves a still of a video at the same address with an image extension.
            int dot = url.lastIndexOf('.');
            thumbnail = (dot > url.lastIndexOf('/') ? url.substring(0, dot) : url) + ".jpg";
        }
        return new StoredMedia(
                publicId,
                type,
                format.toLowerCase(Locale.ROOT),
                bytes.longValue(),
                integer(body, "width"),
                integer(body, "height"),
                duration,
                url,
                thumbnail);
    }

    private static String text(Map<String, Object> body, String field) {
        return body.get(field) instanceof String value ? value : null;
    }

    private static Integer integer(Map<String, Object> body, String field) {
        return body.get(field) instanceof Number value ? value.intValue() : null;
    }
}
