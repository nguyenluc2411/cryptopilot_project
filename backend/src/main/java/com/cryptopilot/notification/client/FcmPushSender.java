package com.cryptopilot.notification.client;

import com.cryptopilot.notification.model.enums.PushOutcome;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sends push notifications through the HTTP v1 API of Firebase Cloud Messaging (T-058, NSF-15), with an access token
 * of the configured service account, obtained by a signed JWT bearer assertion and kept until a minute before it
 * expires. No Firebase library: the two calls are plain HTTPS, so the dependency set does not grow.
 *
 * <p>The service's answer becomes an outcome: 2xx is delivered; {@code UNREGISTERED} (404) or an
 * {@code INVALID_ARGUMENT} about the token (400) means the token is invalid and its device is deactivated; anything
 * else, a network failure included, is a failure the dispatcher retries. A 401 also forgets the access token, so the
 * retry signs a new one.
 *
 * <p>Reference: Google. <i>Firebase Cloud Messaging HTTP v1 API</i>, "projects.messages.send" and "ErrorCode". Google.
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
    public PushOutcome send(PushMessage message) {
        String bearer;
        try {
            bearer = accessToken();
        } catch (RuntimeException failure) {
            log.warn("FCM access token not obtained: {}", failure.toString());
            return PushOutcome.FAILED;
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
            return PushOutcome.DELIVERED;
        } catch (RestClientResponseException refused) {
            return outcomeOf(refused.getStatusCode(), refused.getResponseBodyAsString());
        } catch (RestClientException unreachable) {
            log.warn("FCM not reached: {}", unreachable.toString());
            return PushOutcome.FAILED;
        }
    }

    private PushOutcome outcomeOf(HttpStatusCode status, String body) {
        String code = errorCode(body);
        if (status.value() == 404 || "UNREGISTERED".equals(code)) {
            return PushOutcome.INVALID_TOKEN;
        }
        if (status.value() == 400 && "INVALID_ARGUMENT".equals(code) && body.contains("token")) {
            return PushOutcome.INVALID_TOKEN;
        }
        if (status.value() == 401) {
            synchronized (this) {
                accessToken = null;
                accessTokenExpiresAt = Instant.MIN;
            }
        }
        log.warn("FCM refused a message: {} {}", status.value(), code);
        return PushOutcome.FAILED;
    }

    /** The FCM error code of an error body: the {@code errorCode} of its details, else its status. */
    private String errorCode(String body) {
        try {
            JsonNode error = json.readTree(body).path("error");
            for (JsonNode detail : error.path("details")) {
                if (detail.hasNonNull("errorCode")) {
                    return detail.get("errorCode").asString();
                }
            }
            return error.path("status").asString("");
        } catch (RuntimeException notJson) {
            return "";
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
