package com.cryptopilot.user.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cryptopilot.support.TestcontainersConfig;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Push notification devices over HTTP: "The mobile application registers the FCM device token after
 * login and deactivates it on logout" (SRS 3.2.5).
 *
 * <p>Each state is asserted against the {@code user_device} row, because that row is what the push
 * dispatcher of NSF-15 will read — a device is only as active as its {@code is_active} column says.
 *
 * <p>Sign-out is driven through the real {@code /auth/logout} endpoint with a real refresh token, so
 * the test proves the whole seam: {@code auth} learns whose session it is from the token, and asks
 * {@code user} to deactivate that account's device and no other.
 *
 * <p>Rule: SRS UC-05, UC-08, sections 3.2.5 and 4.2.4; NSF-15; message MSG01.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class DeviceControllerTest {

    private static final String TEST_DOMAIN = "@t014device.invalid";

    private static final String PASSWORD = "Abcdefg1";

    private static final String DEVICES = "/api/v1/me/devices";

    private static final String LOGOUT = "/api/v1/auth/logout";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient sql;

    @AfterEach
    void removeWhatTheTestWrote() {
        sql.sql("delete from user_account where email like :pattern")
                .param("pattern", "%" + TEST_DOMAIN)
                .update();
    }

    /** Registration answers 201 with a key, and the row is the caller's, active and seen now. */
    @Test
    void UC08_registeringADevice_storesItActiveForTheCaller() throws Exception {
        Session trader = signedInTrader("registers");

        String body = mvc.perform(post(DEVICES)
                        .header(HttpHeaders.AUTHORIZATION, trader.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(deviceBody("token-registers", "ANDROID")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.platform").value("ANDROID"))
                .andExpect(jsonPath("$.active").value(true))
                .andReturn()
                .getResponse()
                .getContentAsString();

        UUID deviceId = UUID.fromString(JsonPath.read(body, "$.deviceId"));
        assertThat(body).as("the caller already has the token").doesNotContain("token-registers");
        assertThat(ownerOf(deviceId)).isEqualTo(accountOf("registers"));
        assertThat(isActive(deviceId)).isTrue();
        assertThat(lastSeenOf(deviceId)).isNotNull();
    }

    /**
     * An installation that signs out and back in is the same row, active again — not a second row for
     * the same token, which the unique index would refuse anyway.
     */
    @Test
    void UC08_theSameInstallationSigningInAgain_isTheSameRowReactivated() throws Exception {
        Session trader = signedInTrader("returns");
        UUID first = register(trader, "token-returns");
        mvc.perform(delete(DEVICES + "/" + first).header(HttpHeaders.AUTHORIZATION, trader.bearer()));
        assertThat(isActive(first)).isFalse();

        UUID second = register(trader, "token-returns");

        assertThat(second).isEqualTo(first);
        assertThat(isActive(first)).isTrue();
        assertThat(deviceCountFor("token-returns")).isOne();
    }

    /**
     * A phone that changes hands: the token now belongs to the second account, and the first account
     * no longer has a row that would send its notifications to somebody else's phone.
     */
    @Test
    void UC08_aTokenRegisteredByAnotherAccount_passesToTheCaller() throws Exception {
        Session previous = signedInTrader("previous-owner");
        Session next = signedInTrader("next-owner");
        UUID previousRow = register(previous, "token-shared");

        UUID nextRow = register(next, "token-shared");

        assertThat(nextRow).isNotEqualTo(previousRow);
        assertThat(ownerOf(nextRow)).isEqualTo(accountOf("next-owner"));
        assertThat(deviceCountFor("token-shared")).isOne();
    }

    /** SRS 3.2.5: deactivated by its key, 204, and the row stays so the installation is recognised. */
    @Test
    void UC08_deactivatingOwnDevice_answersNoContentAndKeepsTheRow() throws Exception {
        Session trader = signedInTrader("deactivates");
        UUID deviceId = register(trader, "token-deactivates");

        mvc.perform(delete(DEVICES + "/" + deviceId).header(HttpHeaders.AUTHORIZATION, trader.bearer()))
                .andExpect(status().isNoContent());
        // Again: the device is already in the state the caller asked for, so this succeeds too.
        mvc.perform(delete(DEVICES + "/" + deviceId).header(HttpHeaders.AUTHORIZATION, trader.bearer()))
                .andExpect(status().isNoContent());

        assertThat(isActive(deviceId)).isFalse();
    }

    /**
     * SRS 4.2.4, ownership: one account cannot silence another's phone. The key of somebody else's
     * device is answered exactly as a key that names nothing, and the device stays active.
     */
    @Test
    void UC08_anotherAccountsDevice_isNotFoundAndStaysActive() throws Exception {
        Session owner = signedInTrader("owner");
        Session stranger = signedInTrader("stranger");
        UUID deviceId = register(owner, "token-owned");

        mvc.perform(delete(DEVICES + "/" + deviceId).header(HttpHeaders.AUTHORIZATION, stranger.bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.messageCode").value("MSG41"));
        mvc.perform(delete(DEVICES + "/" + UUID.randomUUID()).header(HttpHeaders.AUTHORIZATION, stranger.bearer()))
                .andExpect(status().isNotFound());

        assertThat(isActive(deviceId)).isTrue();
    }

    /** A key that is not a key is a bad request, not a server error. */
    @Test
    void UC08_aMalformedDeviceKey_isABadRequest() throws Exception {
        Session trader = signedInTrader("malformed");

        mvc.perform(delete(DEVICES + "/not-a-uuid").header(HttpHeaders.AUTHORIZATION, trader.bearer()))
                .andExpect(status().isBadRequest());
    }

    /** A missing token or platform is MSG01; a platform the schema does not know is refused too. */
    @Test
    void UC08_anIncompleteRegistration_isRefused() throws Exception {
        Session trader = signedInTrader("incomplete");

        mvc.perform(post(DEVICES)
                        .header(HttpHeaders.AUTHORIZATION, trader.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fcmToken\": \"\", \"platform\": null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.fcmToken").value("MSG01"))
                .andExpect(jsonPath("$.errors.platform").value("MSG01"));
        mvc.perform(post(DEVICES)
                        .header(HttpHeaders.AUTHORIZATION, trader.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(deviceBody("token-web", "WEB")))
                .andExpect(status().isBadRequest());

        assertThat(deviceCountFor("token-web")).isZero();
    }

    /**
     * SRS 3.2.5: "deactivates it on logout". The sign-out names its messaging token beside the refresh
     * token and the device stops receiving push notifications — without an access token, which may
     * already have expired by the time somebody signs out.
     */
    @Test
    void UC05_signingOutWithTheDeviceToken_deactivatesTheDevice() throws Exception {
        Session trader = signedInTrader("signs-out");
        UUID deviceId = register(trader, "token-signs-out");

        mvc.perform(post(LOGOUT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\": \"" + trader.refreshToken()
                                + "\", \"fcmToken\": \"token-signs-out\"}"))
                .andExpect(status().isNoContent());

        assertThat(isActive(deviceId)).isFalse();
    }

    /** The web client has no device: a sign-out without one ends the session exactly as before. */
    @Test
    void UC05_signingOutWithoutADeviceToken_leavesDevicesAlone() throws Exception {
        Session trader = signedInTrader("web-client");
        UUID deviceId = register(trader, "token-web-client");

        mvc.perform(post(LOGOUT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\": \"" + trader.refreshToken() + "\"}"))
                .andExpect(status().isNoContent());

        assertThat(isActive(deviceId)).isTrue();
    }

    /** A blank device token is the same as none: the session ends and no device is looked up. */
    @Test
    void UC05_signingOutWithABlankDeviceToken_leavesDevicesAlone() throws Exception {
        Session trader = signedInTrader("blank-device");
        UUID deviceId = register(trader, "token-blank-device");

        mvc.perform(post(LOGOUT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\": \"" + trader.refreshToken() + "\", \"fcmToken\": \" \"}"))
                .andExpect(status().isNoContent());

        assertThat(isActive(deviceId)).isTrue();
    }

    /**
     * A sign-out can silence only its own account's device. Naming somebody else's messaging token
     * beside one's own refresh token changes nothing, and is answered the same 204 as every sign-out.
     */
    @Test
    void UC05_signingOutWithAnotherAccountsDeviceToken_changesNothing() throws Exception {
        Session owner = signedInTrader("device-owner");
        Session other = signedInTrader("other-signs-out");
        UUID ownersDevice = register(owner, "token-of-the-owner");

        mvc.perform(post(LOGOUT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\": \"" + other.refreshToken()
                                + "\", \"fcmToken\": \"token-of-the-owner\"}"))
                .andExpect(status().isNoContent());

        assertThat(isActive(ownersDevice)).isTrue();
    }

    /**
     * A refresh token that is not a session deactivates nothing, because there is no account to say
     * whose device it would be — and the answer is still the 204 every sign-out gets.
     */
    @Test
    void UC05_anUnknownRefreshToken_deactivatesNothing() throws Exception {
        Session owner = signedInTrader("unknown-token");
        UUID deviceId = register(owner, "token-unknown-session");

        mvc.perform(post(LOGOUT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\": \"not-a-session\", \"fcmToken\": \"token-unknown-session\"}"))
                .andExpect(status().isNoContent());

        assertThat(isActive(deviceId)).isTrue();
    }

    private UUID register(Session trader, String fcmToken) throws Exception {
        String body = mvc.perform(post(DEVICES)
                        .header(HttpHeaders.AUTHORIZATION, trader.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(deviceBody(fcmToken, "IOS")))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.deviceId"));
    }

    private static String deviceBody(String fcmToken, String platform) {
        return "{\"fcmToken\": \"" + fcmToken + "\", \"platform\": \"" + platform + "\"}";
    }

    private boolean isActive(UUID deviceId) {
        return sql.sql("select is_active from user_device where device_id = ?")
                .param(deviceId)
                .query(Boolean.class)
                .single();
    }

    private UUID ownerOf(UUID deviceId) {
        return sql.sql("select user_id from user_device where device_id = ?")
                .param(deviceId)
                .query(UUID.class)
                .single();
    }

    private Object lastSeenOf(UUID deviceId) {
        List<Object> seen = sql.sql("select last_seen_at from user_device where device_id = ?")
                .param(deviceId)
                .query((row, n) -> row.getObject(1))
                .list();
        return seen.get(0);
    }

    private int deviceCountFor(String fcmToken) {
        return sql.sql("select count(*) from user_device where fcm_token = ?")
                .param(fcmToken)
                .query(Integer.class)
                .single();
    }

    private UUID accountOf(String localPart) {
        return sql.sql("select user_id from user_account where email = ?")
                .param(localPart + TEST_DOMAIN)
                .query(UUID.class)
                .single();
    }

    /** Registers, verifies and signs in a Trader, and answers both tokens of the session. */
    private Session signedInTrader(String localPart) throws Exception {
        String email = localPart + TEST_DOMAIN;
        mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"" + email + "\", \"displayName\": \"Test Person\", \"password\": \""
                                + PASSWORD + "\", \"confirmPassword\": \"" + PASSWORD
                                + "\", \"acceptsDisclaimer\": true}"))
                .andExpect(status().isCreated());
        sql.sql("update user_account set email_verified_at = created_at where email = ?")
                .param(email)
                .update();
        String body = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"" + email + "\", \"password\": \"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return new Session(JsonPath.read(body, "$.accessToken"), JsonPath.read(body, "$.refreshToken"));
    }

    private record Session(String accessToken, String refreshToken) {

        String bearer() {
            return "Bearer " + accessToken;
        }
    }
}
