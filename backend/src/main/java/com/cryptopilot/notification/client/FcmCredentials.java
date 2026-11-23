package com.cryptopilot.notification.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The service account Firebase Cloud Messaging is called with (T-058): the project, the account's address and its
 * private key, read from the JSON key file the Firebase console issues. The key never leaves this object; it signs the
 * assertion exchanged for an access token.
 *
 * <p>Reference: Google. <i>Using OAuth 2.0 for Server to Server Applications</i> (the JWT bearer assertion of a
 * service account).
 *
 * @param projectId the Firebase project
 * @param clientEmail the service account's address, the issuer of the assertion
 * @param privateKey its RSA key
 * @param tokenUri where the assertion is exchanged for an access token
 */
public record FcmCredentials(String projectId, String clientEmail, PrivateKey privateKey, String tokenUri) {

    static final String DEFAULT_TOKEN_URI = "https://oauth2.googleapis.com/token";

    public FcmCredentials {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(clientEmail, "clientEmail");
        Objects.requireNonNull(privateKey, "privateKey");
        Objects.requireNonNull(tokenUri, "tokenUri");
    }

    /**
     * Reads a service account key file.
     *
     * @param projectOverride the project to send through, or blank for the file's {@code project_id}
     * @throws IllegalStateException when the file cannot be read or is not a service account key
     */
    public static FcmCredentials fromFile(Path file, String projectOverride) {
        try {
            return fromJson(Files.readString(file, StandardCharsets.UTF_8), projectOverride);
        } catch (IOException unreadable) {
            throw new IllegalStateException("the FCM service account file " + file + " cannot be read", unreadable);
        }
    }

    /** Reads the JSON of a service account key, as {@link #fromFile} does. */
    public static FcmCredentials fromJson(String json, String projectOverride) {
        JsonNode key;
        try {
            key = JsonMapper.builder().build().readTree(json);
        } catch (RuntimeException notJson) {
            throw new IllegalStateException("the FCM service account key is not JSON", notJson);
        }
        String project =
                projectOverride != null && !projectOverride.isBlank() ? projectOverride : text(key, "project_id");
        String tokenUri = key.hasNonNull("token_uri") ? key.get("token_uri").asString() : DEFAULT_TOKEN_URI;
        return new FcmCredentials(project, text(key, "client_email"), privateKey(text(key, "private_key")), tokenUri);
    }

    private static String text(JsonNode key, String field) {
        if (!key.hasNonNull(field) || key.get(field).asString().isBlank()) {
            throw new IllegalStateException("the FCM service account key has no " + field);
        }
        return key.get(field).asString();
    }

    private static PrivateKey privateKey(String pem) {
        String base64 = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        try {
            return KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
        } catch (GeneralSecurityException | IllegalArgumentException invalid) {
            throw new IllegalStateException("the FCM service account key holds no RSA private key", invalid);
        }
    }

    /** The credentials without the key, for a log line. */
    @Override
    public String toString() {
        return "FcmCredentials(projectId=" + projectId + ", clientEmail=" + clientEmail + ", privateKey=***)";
    }
}
