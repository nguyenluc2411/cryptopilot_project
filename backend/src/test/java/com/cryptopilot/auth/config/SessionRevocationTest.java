package com.cryptopilot.auth.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cryptopilot.support.TestcontainersConfig;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
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
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;

/**
 * An access token stops working the moment its session ends, not fifteen minutes later (D-33).
 *
 * <p>Each case opens real sessions through the sign-in endpoint, reads the profile once with each
 * access token so that the session cache has an answer in memory — the case eviction exists for — then
 * ends sessions and asks again. A refused token is answered 401 with MSG44, the standard body of an
 * unusable credential.
 *
 * <p>Rule: SRS 3.2.3, 3.2.5, 4.2.4; BR-04; D-33; messages MSG14, MSG44.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class SessionRevocationTest {

    private static final String TEST_DOMAIN = "@t014sid.invalid";

    private static final String PASSWORD = "Abcdefg1";

    private static final String NEW_PASSWORD = "Zyxwvu9Q";

    private static final String PROFILE = "/api/v1/me/profile";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient sql;

    @Autowired
    private JwtEncoder jwtEncoder;

    @AfterEach
    void removeWhatTheTestWrote() {
        sql.sql("delete from user_account where email like :pattern")
                .param("pattern", "%" + TEST_DOMAIN)
                .update();
    }

    /**
     * SRS 3.2.5 and ASVS V3.3.3: after a password change, the other session's access token is refused
     * on its very next request, and the session the change was made from carries on.
     */
    @Test
    void UC07_afterAPasswordChange_anotherSessionsAccessTokenIsRefusedAtOnce() throws Exception {
        registerVerified("two-sessions");
        Session laptop = signIn("two-sessions", PASSWORD);
        Session phone = signIn("two-sessions", PASSWORD);
        assertReadable(laptop);
        assertReadable(phone);

        mvc.perform(put("/api/v1/me/password")
                        .header(HttpHeaders.AUTHORIZATION, laptop.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(changeBody(PASSWORD, NEW_PASSWORD)))
                .andExpect(status().isOk());

        assertRefused(phone);
        assertReadable(laptop);
    }

    /** A sign-out ends the access token of that session too, and leaves the account's other session alone. */
    @Test
    void UC05_afterASignOut_thatSessionsAccessTokenIsRefused() throws Exception {
        registerVerified("signs-out");
        Session leaving = signIn("signs-out", PASSWORD);
        Session staying = signIn("signs-out", PASSWORD);
        assertReadable(leaving);

        mvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\": \"" + leaving.refreshToken() + "\"}"))
                .andExpect(status().isNoContent());

        assertRefused(leaving);
        assertReadable(staying);
    }

    /** A renewed session is the same session: the new access token works, the family is still alive. */
    @Test
    void TD715_aRenewedSession_staysAlive() throws Exception {
        registerVerified("renews");
        Session first = signIn("renews", PASSWORD);
        assertReadable(first);

        String body = mvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\": \"" + first.refreshToken() + "\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertReadable(new Session(JsonPath.read(body, "$.accessToken"), JsonPath.read(body, "$.refreshToken")));
        assertReadable(first);
    }

    /**
     * A {@code sid} naming a session of another account is refused: the claim is checked together
     * with the subject, so a token cannot borrow somebody else's live session.
     */
    @Test
    void D33_aSidNamingAnotherAccountsSession_isRefused() throws Exception {
        registerVerified("owner-of-sid");
        Session owners = signIn("owner-of-sid", PASSWORD);
        UUID ownersSession = UUID.fromString(sql.sql("select token_family_id from user_token t join user_account a"
                        + " on a.user_id = t.user_id where a.email = ? and t.token_type = 'REFRESH'")
                .param("owner-of-sid" + TEST_DOMAIN)
                .query(String.class)
                .single());
        registerVerified("borrower");
        UUID borrower = accountOf("borrower");

        mvc.perform(get(PROFILE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(borrower, ownersSession.toString())))
                .andExpect(status().isUnauthorized());
        assertReadable(owners);
    }

    /** A {@code sid} that is not an identifier is refused rather than crashing the request. */
    @Test
    void D33_aMalformedSid_isRefused() throws Exception {
        registerVerified("malformed-sid");

        mvc.perform(get(PROFILE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(accountOf("malformed-sid"), "not-a-uuid")))
                .andExpect(status().isUnauthorized());
    }

    private void assertReadable(Session session) throws Exception {
        mvc.perform(get(PROFILE).header(HttpHeaders.AUTHORIZATION, session.bearer()))
                .andExpect(status().isOk());
    }

    private void assertRefused(Session session) throws Exception {
        mvc.perform(get(PROFILE).header(HttpHeaders.AUTHORIZATION, session.bearer()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.messageCode").value("MSG44"));
    }

    private String token(UUID subject, String sid) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(JwtConfig.ISSUER)
                .subject(subject.toString())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(900))
                .claim(JwtConfig.ROLE_CLAIM, "TRADER")
                .claim(JwtConfig.SESSION_CLAIM, sid)
                .build();
        return jwtEncoder
                .encode(JwtEncoderParameters.from(
                        JwsHeader.with(JwtConfig.ALGORITHM).build(), claims))
                .getTokenValue();
    }

    private static String changeBody(String current, String next) {
        return "{\"currentPassword\": \"" + current + "\", \"newPassword\": \"" + next + "\", \"confirmPassword\": \""
                + next + "\"}";
    }

    private UUID accountOf(String localPart) {
        return sql.sql("select user_id from user_account where email = ?")
                .param(localPart + TEST_DOMAIN)
                .query(UUID.class)
                .single();
    }

    private void registerVerified(String localPart) throws Exception {
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
    }

    private Session signIn(String localPart, String password) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                "{\"email\": \"" + localPart + TEST_DOMAIN + "\", \"password\": \"" + password + "\"}"))
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
