package com.cryptopilot.notification.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.cryptopilot.notification.model.enums.PushOutcome;
import com.cryptopilot.support.MutableTestClock;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * The FCM HTTP v1 adapter (T-058) against a mock of the two Google endpoints: the token exchange of the service
 * account, and the message send.
 *
 * <p>Invariants: (1) the assertion is signed RS256 by the service account's key and names the messaging scope; (2) an
 * access token is reused until a minute before it expires; (3) 2xx is delivered; only {@code UNREGISTERED} or an
 * {@code INVALID_ARGUMENT} naming {@code message.token} is {@code INVALID_TOKEN}, so a bare 404 or a 403 never
 * deactivates a device; network, 401, 429, 500 and 503 are {@code FAILED} (retried), any other refusal
 * {@code NON_RETRYABLE}; (4) a 401 forgets the access token; (5) a {@code Retry-After} in seconds or as a date travels
 * with a retryable failure.
 *
 * <p>Rule: NSF-15.
 */
class FcmPushSenderTest {

    private static final Instant T0 = Instant.parse("2026-11-22T08:00:00Z");
    private static final String TOKEN_URI = "https://oauth2.example.test/token";
    private static final String SEND = "https://fcm.example.test/v1/projects/cryptopilot-test/messages:send";

    private final MutableTestClock clock = new MutableTestClock(T0);
    private KeyPair keys;
    private MockRestServiceServer server;
    private FcmPushSender sender;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keys = generator.generateKeyPair();
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        sender = new FcmPushSender(
                new FcmCredentials(
                        "cryptopilot-test", "push@cryptopilot-test.iam.example", keys.getPrivate(), TOKEN_URI),
                builder.build(),
                "https://fcm.example.test/",
                clock);
    }

    @Test
    void NSF15_aMessage_isSentWithTheExchangedToken_andTheTokenIsReused() {
        expectToken("at-1", 3600);
        expectSend("at-1", withSuccess("{\"name\":\"projects/x/messages/1\"}", MediaType.APPLICATION_JSON));
        expectSend("at-1", withSuccess());

        assertThat(sender.send(message()).outcome()).isEqualTo(PushOutcome.DELIVERED);
        clock.set(T0.plus(Duration.ofMinutes(58)));
        assertThat(sender.send(message()).outcome()).isEqualTo(PushOutcome.DELIVERED);

        server.verify();
    }

    @Test
    void NSF15_aTokenAboutToExpire_isExchangedAgain() {
        expectToken("at-1", 3600);
        expectSend("at-1", withSuccess());
        expectToken("at-2", 3600);
        expectSend("at-2", withSuccess());

        sender.send(message());
        clock.set(T0.plus(Duration.ofMinutes(59)).plusSeconds(1));
        sender.send(message());

        server.verify();
    }

    @ParameterizedTest(name = "{0} {1} is {2}")
    @CsvSource(
            delimiter = ';',
            value = {
                "404; {\"error\":{\"status\":\"NOT_FOUND\",\"details\":[{\"errorCode\":\"UNREGISTERED\"}]}}; INVALID_TOKEN",
                "400; {\"error\":{\"status\":\"INVALID_ARGUMENT\",\"details\":[{\"errorCode\":\"INVALID_ARGUMENT\"},{\"fieldViolations\":[{\"field\":\"message.token\"}]}]}}; INVALID_TOKEN",
                "400; {\"error\":{\"status\":\"INVALID_ARGUMENT\",\"message\":\"The registration token is not a valid FCM registration token\"}}; NON_RETRYABLE",
                "400; {\"error\":{\"status\":\"INVALID_ARGUMENT\",\"details\":[{\"fieldViolations\":[{\"field\":\"message.data[0].value\"}]}]}}; NON_RETRYABLE",
                "400; {\"error\":{\"status\":\"INVALID_ARGUMENT\",\"message\":\"Invalid JSON payload\"}}; NON_RETRYABLE",
                "404; {\"error\":{\"status\":\"NOT_FOUND\",\"message\":\"Requested entity was not found.\"}}; NON_RETRYABLE",
                "404; not json; NON_RETRYABLE",
                "403; {\"error\":{\"status\":\"PERMISSION_DENIED\",\"details\":[{\"errorCode\":\"SENDER_ID_MISMATCH\"}]}}; NON_RETRYABLE",
                "502; {\"error\":{\"status\":\"UNAVAILABLE\"}}; NON_RETRYABLE",
                "500; {\"error\":{\"status\":\"INTERNAL\"}}; FAILED",
                "503; not json; FAILED",
                "429; {\"error\":{\"status\":\"RESOURCE_EXHAUSTED\",\"details\":[{\"errorCode\":\"QUOTA_EXCEEDED\"}]}}; FAILED"
            })
    void NSF15_theServicesAnswer_becomesAnOutcome(int status, String body, PushOutcome outcome) {
        expectToken("at-1", 3600);
        expectSend("at-1", withStatus(HttpStatus.valueOf(status)).body(body).contentType(MediaType.APPLICATION_JSON));

        assertThat(sender.send(message()).outcome()).isEqualTo(outcome);
    }

    @ParameterizedTest(name = "Retry-After {0} is {1}s")
    @CsvSource(
            delimiter = ';',
            value = {"7; 7", "'Sun, 22 Nov 2026 08:00:30 GMT'; 30", "'Sun, 22 Nov 2026 07:59:00 GMT'; 0"})
    void NSF15_aRetryAfter_travelsWithARetryableFailure(String header, long seconds) {
        expectToken("at-1", 3600);
        expectSend("at-1", withStatus(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", header));

        PushResult result = sender.send(message());

        assertThat(result.outcome()).isEqualTo(PushOutcome.FAILED);
        assertThat(result.retryAfterHint()).contains(Duration.ofSeconds(seconds));
    }

    @ParameterizedTest(name = "Retry-After ''{0}'' is ignored")
    @CsvSource(value = {"soon", "-3", "' '"})
    void NSF15_anUnreadableRetryAfter_isIgnored(String header) {
        expectToken("at-1", 3600);
        expectSend("at-1", withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", header));

        PushResult result = sender.send(message());

        assertThat(result.outcome()).isEqualTo(PushOutcome.FAILED);
        assertThat(result.retryAfterHint()).isEmpty();
    }

    @Test
    void NSF15_aFailureWithoutRetryAfter_namesNoWait() {
        expectToken("at-1", 3600);
        expectSend("at-1", withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThat(sender.send(message()).retryAfterHint()).isEmpty();
    }

    @Test
    void NSF15_a401_forgetsTheAccessToken() {
        expectToken("at-1", 3600);
        expectSend("at-1", withStatus(HttpStatus.UNAUTHORIZED));
        expectToken("at-2", 3600);
        expectSend("at-2", withSuccess());

        assertThat(sender.send(message()).outcome()).isEqualTo(PushOutcome.FAILED);
        assertThat(sender.send(message()).outcome()).isEqualTo(PushOutcome.DELIVERED);

        server.verify();
    }

    @Test
    void NSF15_aTokenEndpointThatFails_isAFailure_notAnException() {
        server.expect(ExpectedCount.once(), requestTo(TOKEN_URI))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("{\"error\":\"invalid_grant\"}"));

        assertThat(sender.send(message()).outcome()).isEqualTo(PushOutcome.FAILED);
    }

    @Test
    void NSF15_theAssertion_isSignedByTheServiceAccount() throws Exception {
        String[] parts = sender.assertion(T0).split("\\.");

        String claims = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        assertThat(claims)
                .contains("\"iss\":\"push@cryptopilot-test.iam.example\"")
                .contains("\"scope\":\"" + FcmPushSender.SCOPE + "\"")
                .contains("\"aud\":\"" + TOKEN_URI + "\"")
                .contains("\"iat\":" + T0.getEpochSecond())
                .contains("\"exp\":" + T0.plus(Duration.ofHours(1)).getEpochSecond());
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(keys.getPublic());
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(verifier.verify(Base64.getUrlDecoder().decode(parts[2]))).isTrue();
    }

    @Test
    void NSF15_theCredentials_areReadFromTheKeyFile_andNeverPrinted() {
        // The PEM armour around the key this test generates; built in parts so the source holds no armour literal.
        String armour = "PRIVATE" + " KEY-----";
        String pem = "-----BEGIN " + armour + "\n"
                + Base64.getMimeEncoder(64, new byte[] {10})
                        .encodeToString(keys.getPrivate().getEncoded())
                + "\n-----END " + armour + "\n";
        String json = "{\"type\":\"service_account\",\"project_id\":\"p1\",\"client_email\":\"a@b.example\","
                + "\"private_key\":\"" + pem.replace("\n", "\\n") + "\"}";

        FcmCredentials read = FcmCredentials.fromJson(json, "");
        FcmCredentials overridden = FcmCredentials.fromJson(json, "p2");

        assertThat(read.projectId()).isEqualTo("p1");
        assertThat(read.tokenUri()).isEqualTo(FcmCredentials.DEFAULT_TOKEN_URI);
        assertThat(read.privateKey().getEncoded()).isEqualTo(keys.getPrivate().getEncoded());
        assertThat(overridden.projectId()).isEqualTo("p2");
        assertThat(read.toString()).doesNotContain("BEGIN").contains("privateKey=***");
        assertThatIllegalStateException().isThrownBy(() -> FcmCredentials.fromJson("{\"project_id\":\"p\"}", ""));
        assertThatIllegalStateException().isThrownBy(() -> FcmCredentials.fromJson("not json", ""));
        assertThatIllegalStateException()
                .isThrownBy(() -> FcmCredentials.fromJson(json.replace(pem.replace("\n", "\\n"), "xyz"), ""));
    }

    @Test
    void NSF15_aMessage_neverPrintsItsToken() {
        assertThat(message().toString()).doesNotContain("device-token").contains("token=***");
    }

    private void expectToken(String accessToken, int expiresIn) {
        server.expect(ExpectedCount.once(), requestTo(TOKEN_URI))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content()
                        .string(org.hamcrest.Matchers.containsString(
                                "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer")))
                .andRespond(withSuccess(
                        "{\"access_token\":\"" + accessToken + "\",\"expires_in\":" + expiresIn + "}",
                        MediaType.APPLICATION_JSON));
    }

    private void expectSend(String accessToken, org.springframework.test.web.client.ResponseCreator response) {
        server.expect(ExpectedCount.once(), requestTo(SEND))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + accessToken))
                .andExpect(jsonPath("$.message.token").value("device-token"))
                .andExpect(jsonPath("$.message.notification.title").value("Order filled"))
                .andExpect(jsonPath("$.message.data.type").value("MARKET"))
                .andRespond(response);
    }

    private static PushMessage message() {
        return new PushMessage(
                "device-token", "Order filled", "Your BTCUSDT buy market order filled 2.", Map.of("type", "MARKET"));
    }
}
