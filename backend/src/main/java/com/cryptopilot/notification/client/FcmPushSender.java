package com.cryptopilot.notification.client;

import com.cryptopilot.notification.model.enums.PushOutcome;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.MissingNode;

/**
 * Sends push notifications through the HTTP v1 API of Firebase Cloud Messaging (T-058, NSF-15), with an access token
 * of the configured service account, obtained by a signed JWT bearer assertion and kept until a minute before it
 * expires. No Firebase library: the two calls are plain HTTPS, so the dependency set does not grow.
 *
 * <p>The service's answer becomes an outcome:
 *
 * <ul>
 *   <li>2xx is delivered;
 *   <li>only the error code {@code UNREGISTERED}, or {@code INVALID_ARGUMENT} whose field violation names
 *       {@code message.token}, means the token is invalid and its device is deactivated. A bare 404 or a 403
 *       ({@code SENDER_ID_MISMATCH} included) says the project or the API address is wrong, not the device: it is
 *       logged as a configuration problem and no device is touched, so a wrong {@code FCM_PROJECT_ID} cannot
 *       deactivate every device on the first push;
 *   <li>a network failure, 429, 500 or 503 is a failure the dispatcher retries, after the {@code Retry-After} the
 *       service named, if any; a 401 too, after forgetting the access token so the retry signs a new one;
 *   <li>any other refusal (400 not about the token, 403, 404, ...) is not retryable: a retry would be refused alike.
 * </ul>
 *
 * <p>Reference: Google. <i>Firebase Cloud Messaging HTTP v1 API</i>, "projects.messages.send" and "ErrorCode".
 * Fielding, R. et al. (2022). <i>RFC 9110: HTTP Semantics</i>, §10.2.3 "Retry-After". Nottingham, M. &amp; Fielding, R.
 * (2012). <i>RFC 6585: Additional HTTP Status Codes</i>, §4 "429 Too Many Requests". Google.
 * <i>Using OAuth 2.0 for Server to Server Applications</i>. Jones, M. et al. (2015). <i>RFC 7523: JSON Web Token (JWT)
 * Profile for OAuth 2.0 Client Authentication and Authorization Grants</i>.
 */
public class FcmPushSender implements PushSender {

    static final String SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
    private static final String GRANT = "urn:ietf:params:oauth:grant-type:jwt-bearer";
    private static final Duration TOKEN_LIFETIME = Duration.ofHours(1);
    private static final Duration TOKEN_MARGIN = Duration.ofMinutes(1);
    private static final Logger log = LoggerFactory.getLogger(FcmPushSender.class);

    private final FcmCredentials credentials;
    private final RestClient rest;
    private final String apiUrl;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();

    private String accessToken;
    private Instant accessTokenExpiresAt = Instant.MIN;

    /**
     * @param apiUrl the API's base, {@code https://fcm.googleapis.com} in production
     */
    public FcmPushSender(FcmCredentials credentials, RestClient rest, String apiUrl, Clock clock) {
        this.credentials = credentials;
        this.rest = rest;
        this.apiUrl = apiUrl.endsWith("/") ? apiUrl.substring(0, apiUrl.length() - 1) : apiUrl;
        this.clock = clock;
    }

    @Override
    public PushResult send(PushMessage message) {
        String bearer;
        try {
            bearer = accessToken();
        } catch (RuntimeException failure) {
            log.warn("FCM access token not obtained: {}", failure.toString());
            return PushResult.of(PushOutcome.FAILED);
        }
        Map<String, Object> body = Map.of(
                "message",
                Map.of(
                        "token",
                        message.token(),
                        "notification",
                        Map.of("title", message.title(), "body", message.body()),
                        "data",
                        message.data()));
        try {
            rest.post()
                    .uri(apiUrl + "/v1/projects/" + credentials.projectId() + "/messages:send")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(json.writeValueAsString(body))
                    .retrieve()
                    .toBodilessEntity();
            return PushResult.of(PushOutcome.DELIVERED);
        } catch (RestClientResponseException refused) {
            HttpHeaders headers = refused.getResponseHeaders();
            return outcomeOf(
                    refused.getStatusCode(),
                    refused.getResponseBodyAsString(),
                    headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER));
        } catch (RestClientException unreachable) {
            log.warn("FCM not reached: {}", unreachable.toString());
            return PushResult.of(PushOutcome.FAILED);
        }
    }

    private PushResult outcomeOf(HttpStatusCode status, String body, String retryAfter) {
        JsonNode error = errorOf(body);
        String code = errorCode(error);
        if ("UNREGISTERED".equals(code) || ("INVALID_ARGUMENT".equals(code) && namesTheToken(error))) {
            return PushResult.of(PushOutcome.INVALID_TOKEN);
        }
        int value = status.value();
        if (value == HttpStatus.UNAUTHORIZED.value()) {
            synchronized (this) {
                accessToken = null;
                accessTokenExpiresAt = Instant.MIN;
            }
            log.warn("FCM refused the access token: 401 {}", code);
            return PushResult.of(PushOutcome.FAILED);
        }
        if (value == HttpStatus.TOO_MANY_REQUESTS.value()
                || value == HttpStatus.INTERNAL_SERVER_ERROR.value()
                || value == HttpStatus.SERVICE_UNAVAILABLE.value()) {
            log.warn("FCM is unavailable for now: {} {}", value, code);
            return new PushResult(PushOutcome.FAILED, retryAfter(retryAfter));
        }
        if (value == HttpStatus.NOT_FOUND.value() || value == HttpStatus.FORBIDDEN.value()) {
            log.error(
                    "FCM configuration problem: {} {} for project {} at {}; check FCM_PROJECT_ID and"
                            + " cryptopilot.push.api-url; no device was deactivated",
                    value,
                    code,
                    credentials.projectId(),
                    apiUrl);
        } else {
            log.warn("FCM refused a message, not retried: {} {}", value, code);
        }
        return PushResult.of(PushOutcome.NON_RETRYABLE);
    }

    private JsonNode errorOf(String body) {
        try {
            return json.readTree(body).path("error");
        } catch (RuntimeException notJson) {
            return MissingNode.getInstance();
        }
    }

    /** The FCM error code of an error: the {@code errorCode} of its details, else its status. */
    private static String errorCode(JsonNode error) {
        for (JsonNode detail : error.path("details")) {
            if (detail.hasNonNull("errorCode")) {
                return detail.get("errorCode").asString();
            }
        }
        return error.path("status").asString("");
    }

    /** Whether a {@code google.rpc.BadRequest} detail of the error names the message's token field. */
    private static boolean namesTheToken(JsonNode error) {
        for (JsonNode detail : error.path("details")) {
            for (JsonNode violation : detail.path("fieldViolations")) {
                if ("message.token".equals(violation.path("field").asString(""))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** A {@code Retry-After} in seconds or as an HTTP date; {@code null} when absent or unreadable (RFC 9110). */
    private Duration retryAfter(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        String value = header.trim();
        try {
            long seconds = Long.parseLong(value);
            return seconds < 0 ? null : Duration.ofSeconds(seconds);
        } catch (NumberFormatException notSeconds) {
            try {
                Duration until = Duration.between(
                        clock.instant(), ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME));
                return until.isNegative() ? Duration.ZERO : until;
            } catch (DateTimeParseException notADate) {
                return null;
            }
        }
    }

    /** The access token, signed and exchanged again a minute before it expires. */
    private synchronized String accessToken() {
        Instant now = clock.instant();
        if (accessToken != null && now.isBefore(accessTokenExpiresAt.minus(TOKEN_MARGIN))) {
            return accessToken;
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", GRANT);
        form.add("assertion", assertion(now));
        String answer = rest.post()
                .uri(credentials.tokenUri())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(String.class);
        JsonNode token = json.readTree(answer == null ? "{}" : answer);
        if (!token.hasNonNull("access_token")) {
            throw new IllegalStateException("the token endpoint answered without an access token");
        }
        accessToken = token.get("access_token").asString();
        accessTokenExpiresAt = now.plusSeconds(token.path("expires_in").asLong(TOKEN_LIFETIME.toSeconds()));
        return accessToken;
    }

    /** The JWT bearer assertion of the service account, signed RS256 (RFC 7523). */
    String assertion(Instant now) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", credentials.clientEmail());
        claims.put("scope", SCOPE);
        claims.put("aud", credentials.tokenUri());
        claims.put("iat", now.getEpochSecond());
        claims.put("exp", now.plus(TOKEN_LIFETIME).getEpochSecond());
        String unsigned = base64(json.writeValueAsString(Map.of("alg", "RS256", "typ", "JWT"))) + "."
                + base64(json.writeValueAsString(claims));
        try {
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(credentials.privateKey());
            signer.update(unsigned.getBytes(StandardCharsets.US_ASCII));
            return unsigned + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("the FCM assertion could not be signed", failure);
        }
    }

    private static String base64(String text) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }
}
