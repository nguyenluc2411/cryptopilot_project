package com.cryptopilot.auth.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.common.exception.ProblemDetails;
import com.cryptopilot.support.TestcontainersConfig;
import com.cryptopilot.user.UserRole;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Every row of the authorization matrix of SRS section 3.1.3, driven as a real request through the
 * real filter chain.
 *
 * <h2>How a row is proved before its controller exists</h2>
 *
 * <p>Authorization in this application is decided by the filter chain, which matches on the path and
 * the role and runs long before the dispatcher looks for a handler. That is what makes these
 * assertions possible today: of the twelve areas the matrix names, one has endpoints and eleven do
 * not, and the chain answers for all twelve identically. A request that the chain <em>refuses</em>
 * ends at 401 or 403 with no handler ever consulted; a request it <em>admits</em> carries on and is
 * answered by whatever is behind it, which for an area still to be built is a 404 from the servlet.
 *
 * <p>So the two outcomes are asserted asymmetrically, and deliberately. A refusal is asserted
 * exactly - 401 or 403, precisely which one, because that distinction is the subject of the test.
 * An admission is asserted as the absence of a refusal rather than as a particular success, because
 * the successful answer is the business of the task that builds the endpoint and will change from
 * 404 to 200 when it does. Were it pinned here, every one of the eleven areas would break this class
 * on the day it gained a controller, and a test that has to be edited whenever unrelated work lands
 * stops being read.
 *
 * <p>The admission check still has teeth: it fails on 401, on 403, and on any 5xx, so a rule that
 * matched the wrong path, a converter that granted no authority and a chain that threw are all
 * caught.
 *
 * <h2>Guest is a case, not a role</h2>
 *
 * <p>Each area is driven three ways - with no credential at all, with a Trader's token and with an
 * Admin's token - because the matrix has three columns and a rule that grants the right role is only
 * half a rule. The anonymous case is what makes Guest testable although no such role exists: it is
 * the column, and the public rows are the only ones it may pass.
 *
 * <p>Rule: SRS 3.1.3 (screen authorization), SRS 4.2.4 (authorization is enforced on the server for
 * every request), BR-05; TECHNICAL_DESIGN sections 5.3 and 8.
 *
 * <p>Reference: Sandhu, R., Coyne, E., Feinstein, H. &amp; Youman, C. (1996). <i>Role-Based Access
 * Control Models</i>. IEEE Computer 29(2).
 * <p>Reference: Saltzer, J. H. &amp; Schroeder, M. D. (1975). <i>The Protection of Information in
 * Computer Systems</i>, section I.A.3 (fail-safe defaults).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class AuthorizationMatrixTest {

    /** One path per area, standing for the area's wildcard. */
    private static final String PROFILE = "/api/v1/me/profile";

    private static final String PASSWORD_CHANGE = "/api/v1/me/password";
    private static final String ADMIN_USERS = "/api/v1/admin/users";
    private static final String PUBLIC_MARKET = "/api/v1/market/pairs";
    private static final String ANALYSIS = "/api/v1/analysis/spot/BTCUSDT";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JwtEncoder jwtEncoder;

    /**
     * SCR-01 Public Home and the "Query Public Data" row: the only data a Guest may read.
     *
     * <p>Granted to every column of the matrix, so all three callers are checked - a rule that let a
     * Guest in but turned a signed-in Trader away would satisfy the first assertion alone.
     */
    @Nested
    class Scr01PublicMarketData {

        @Test
        void SRS313_theMarketOverview_isReadableByAGuest() throws Exception {
            assertAdmitted(mvc.perform(get(PUBLIC_MARKET)));
        }

        @Test
        void SRS313_theMarketOverview_staysReadableOnceSignedIn() throws Exception {
            assertAdmitted(mvc.perform(get(PUBLIC_MARKET).header(HttpHeaders.AUTHORIZATION, bearer(UserRole.TRADER))));
            assertAdmitted(mvc.perform(get(PUBLIC_MARKET).header(HttpHeaders.AUTHORIZATION, bearer(UserRole.ADMIN))));
        }

        /**
         * Public for reading only. The write side of the same data is SCR-37 Pair Management, an
         * administration screen served under {@code /admin/pairs}, so a write here has no rule and
         * falls to {@code denyAll}.
         */
        @Test
        void SRS313_theMarketOverview_isOpenedForReadingOnly() throws Exception {
            assertUnauthenticated(mvc.perform(put(PUBLIC_MARKET)));
        }
    }

    /** SCR-10 and SCR-11: the computed analysis is inside the Trader area, unlike the raw data. */
    @Nested
    class Scr10AnalysisIsNotPublic {

        @Test
        void SRS313_analysis_isRefusedToAGuest() throws Exception {
            assertUnauthenticated(mvc.perform(get(ANALYSIS)));
        }

        @Test
        void SRS313_analysis_isAdmittedForATrader() throws Exception {
            assertAdmitted(mvc.perform(get(ANALYSIS).header(HttpHeaders.AUTHORIZATION, bearer(UserRole.TRADER))));
        }

        @Test
        void SRS313_analysis_isRefusedToAnAdmin() throws Exception {
            assertForbidden(mvc.perform(get(ANALYSIS).header(HttpHeaders.AUTHORIZATION, bearer(UserRole.ADMIN))));
        }
    }

    /**
     * The Trader rows of the matrix: SCR-08 to SCR-32, one representative path per area.
     *
     * <p>All three columns are asserted for each, which is what makes this the matrix rather than a
     * list of open paths: a Trader passes, a Guest is challenged, and an Admin is refused because
     * SRS 3.1.3 states that administrators use only the administration console.
     */
    @Nested
    class TraderArea {

        @ParameterizedTest(name = "{0} admits a Trader")
        @ValueSource(
                strings = {
                    PROFILE,
                    "/api/v1/me/notification-preferences",
                    "/api/v1/me/devices",
                    "/api/v1/watchlist",
                    "/api/v1/alerts",
                    "/api/v1/notifications",
                    "/api/v1/plans",
                    "/api/v1/journal",
                    "/api/v1/performance",
                    "/api/v1/posts",
                    "/api/v1/comments/1",
                    "/api/v1/media/upload-signature",
                    "/api/v1/news",
                    "/api/v1/ai/conversations",
                    "/api/v1/billing/packages",
                    "/api/v1/billing/orders",
                    "/api/v1/billing/subscription"
                })
        void SRS313_theTraderArea_admitsATrader(String path) throws Exception {
            assertAdmitted(mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer(UserRole.TRADER))));
        }

        @ParameterizedTest(name = "{0} challenges a Guest")
        @ValueSource(
                strings = {
                    PROFILE,
                    "/api/v1/watchlist",
                    "/api/v1/alerts",
                    "/api/v1/notifications",
                    "/api/v1/plans",
                    "/api/v1/journal",
                    "/api/v1/performance",
                    "/api/v1/posts",
                    "/api/v1/news",
                    "/api/v1/ai/conversations",
                    "/api/v1/billing/packages"
                })
        void SRS313_theTraderArea_challengesAGuest(String path) throws Exception {
            assertUnauthenticated(mvc.perform(get(path)));
        }

        @ParameterizedTest(name = "{0} refuses an Admin")
        @ValueSource(
                strings = {
                    PROFILE,
                    "/api/v1/watchlist",
                    "/api/v1/alerts",
                    "/api/v1/notifications",
                    "/api/v1/plans",
                    "/api/v1/journal",
                    "/api/v1/performance",
                    "/api/v1/posts",
                    "/api/v1/news",
                    "/api/v1/ai/conversations",
                    "/api/v1/billing/packages"
                })
        void SRS313_theTraderArea_refusesAnAdmin(String path) throws Exception {
            assertForbidden(mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer(UserRole.ADMIN))));
        }
    }

    /**
     * SCR-07's Security tab, the one row of the matrix both roles hold.
     *
     * <p>The interesting assertion is the Admin one: it is the single place an administrator's token
     * passes a rule outside {@code /admin}, and it passes because an administrator has a password of
     * their own to change. It also proves the ordering inside the chain, since the broader
     * {@code /api/v1/me/**} rule below would refuse this exact caller had it been declared first.
     */
    @Nested
    class Scr07SecurityTab {

        @Test
        void SRS313_changingOwnPassword_isAdmittedForBothRoles() throws Exception {
            assertAdmitted(
                    mvc.perform(put(PASSWORD_CHANGE).header(HttpHeaders.AUTHORIZATION, bearer(UserRole.TRADER))));
            assertAdmitted(mvc.perform(put(PASSWORD_CHANGE).header(HttpHeaders.AUTHORIZATION, bearer(UserRole.ADMIN))));
        }

        @Test
        void SRS313_changingOwnPassword_stillNeedsASession() throws Exception {
            assertUnauthenticated(mvc.perform(put(PASSWORD_CHANGE)));
        }

        /**
         * And the tab beside it is not shared: SRS 3.1.3 grants an administrator SCR-07 with the
         * words "Security tab only", so the Profile tab of the same screen refuses them.
         */
        @Test
        void SRS313_theProfileTab_isNotSharedWithAnAdmin() throws Exception {
            assertForbidden(mvc.perform(get(PROFILE).header(HttpHeaders.AUTHORIZATION, bearer(UserRole.ADMIN))));
        }
    }

    /** SCR-33 to SCR-44, including the "Publish Official Announcement" row. */
    @Nested
    class AdministrationConsole {

        @ParameterizedTest(name = "{0} admits an Admin")
        @ValueSource(
                strings = {
                    "/api/v1/admin/dashboard",
                    ADMIN_USERS,
                    "/api/v1/admin/reports-queue",
                    "/api/v1/admin/pairs",
                    "/api/v1/admin/leverage-brackets",
                    "/api/v1/admin/packages",
                    "/api/v1/admin/orders",
                    "/api/v1/admin/news-sources",
                    "/api/v1/admin/ai-configurations",
                    "/api/v1/admin/settings",
                    "/api/v1/admin/audit-log",
                    "/api/v1/admin/announcements"
                })
        void SRS313_theAdministrationConsole_admitsAnAdmin(String path) throws Exception {
            assertAdmitted(mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer(UserRole.ADMIN))));
        }

        @ParameterizedTest(name = "{0} refuses a Trader")
        @ValueSource(
                strings = {
                    "/api/v1/admin/dashboard",
                    ADMIN_USERS,
                    "/api/v1/admin/reports-queue",
                    "/api/v1/admin/settings",
                    "/api/v1/admin/audit-log",
                    "/api/v1/admin/announcements"
                })
        void SRS313_theAdministrationConsole_refusesATrader(String path) throws Exception {
            assertForbidden(mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer(UserRole.TRADER))));
        }

        @Test
        void SRS313_theAdministrationConsole_challengesAGuest() throws Exception {
            assertUnauthenticated(mvc.perform(get(ADMIN_USERS)));
        }
    }

    /**
     * The payment callbacks, which are not rows of the matrix and must not be treated as if they
     * were.
     *
     * <p>They arrive from the gateway's servers or as a browser redirect, so they carry no bearer
     * token and could never satisfy a Trader rule. They are left unclaimed on purpose: closed today,
     * to be opened by the task that also implements the signature check that is their real
     * authentication (BR-53). The assertion records that decision, so that widening the billing rule
     * to {@code /billing/**} - which would look tidier and would silently make every callback a
     * Trader-only endpoint - fails here.
     */
    @ParameterizedTest(name = "{0} is not covered by the Trader billing rule")
    @ValueSource(strings = {"/api/v1/billing/ipn/vnpay", "/api/v1/billing/ipn/momo", "/api/v1/billing/return/vnpay"})
    void BR53_thePaymentCallbacks_areNotInTheTraderArea(String path) throws Exception {
        assertForbidden(mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer(UserRole.TRADER))));
    }

    /**
     * A token carrying a role the enumeration does not name grants nothing.
     *
     * <p>Such a token can only come from a defect, since the application signs its own. What the
     * assertion pins is that the defect ends in a refusal rather than in an authority no rule
     * mentions - a caller holding {@code ROLE_SUPERUSER} is refused today only because nothing
     * grants that authority, which is luck rather than a rule.
     */
    @Test
    void BR05_aTokenNamingAnUnknownRole_authorizesNothing() throws Exception {
        assertForbidden(
                mvc.perform(get(PROFILE).header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenWithRole("SUPERUSER"))));
    }

    /** A token with no role claim at all is a caller with no role, not a caller with every role. */
    @Test
    void BR05_aTokenWithNoRoleClaim_authorizesNothing() throws Exception {
        assertForbidden(mvc.perform(get(PROFILE).header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenWithRole(null))));
    }

    /**
     * A refusal is answered in the same shape as every other error, not as an empty body.
     *
     * <p>Both outcomes are checked, because they are produced by two different collaborators of the
     * chain - the entry point and the access denied handler - and one of them working proves nothing
     * about the other.
     */
    @Nested
    class RefusalsCarryTheStandardBody {

        @Test
        void TD51_aChallenge_carriesTheProblemDetailOfAnEndedSession() throws Exception {
            MvcResult result = mvc.perform(get(PROFILE)).andReturn();

            assertThat(result.getResponse().getStatus()).isEqualTo(401);
            assertThat(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE))
                    .startsWith("Bearer");
            assertThat(result.getResponse().getContentAsString())
                    .contains("\"" + ProblemDetails.CODE + "\":\"" + ErrorCode.AUTHENTICATION_REQUIRED.code() + "\"")
                    .contains("\"" + ProblemDetails.MESSAGE_CODE + "\":\""
                            + ErrorCode.AUTHENTICATION_REQUIRED.messageCode() + "\"")
                    .contains(ProblemDetails.TRACE_ID);
        }

        @Test
        void TD51_aRefusal_carriesTheProblemDetailOfADeniedAccess() throws Exception {
            MvcResult result = mvc.perform(get(ADMIN_USERS).header(HttpHeaders.AUTHORIZATION, bearer(UserRole.TRADER)))
                    .andReturn();

            assertThat(result.getResponse().getStatus()).isEqualTo(403);
            assertThat(result.getResponse().getContentAsString())
                    .contains("\"" + ProblemDetails.CODE + "\":\"" + ErrorCode.ACCESS_DENIED.code() + "\"")
                    .contains(
                            "\"" + ProblemDetails.MESSAGE_CODE + "\":\"" + ErrorCode.ACCESS_DENIED.messageCode() + "\"")
                    .contains(ProblemDetails.TRACE_ID);
        }

        /**
         * And it names neither the role that would have been enough nor the rule that matched. A 403
         * that explains itself is a map of the application drawn for whoever is probing it.
         */
        @Test
        void NSF_aRefusal_namesNeitherTheRequiredRoleNorTheMatchedRule() throws Exception {
            String body = mvc.perform(get(ADMIN_USERS).header(HttpHeaders.AUTHORIZATION, bearer(UserRole.TRADER)))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();

            assertThat(body).doesNotContain("ADMIN").doesNotContain("ROLE_").doesNotContain("admin/**");
        }
    }

    /**
     * The declared areas, read as data rather than driven.
     *
     * <p>A behavioural test says an Admin is refused at {@code /api/v1/plans}; it cannot say that the
     * rule which refused them was the Trader rule rather than {@code denyAll} catching a path nobody
     * claimed. These assertions pin the declaration, so that deleting an area from the matrix - which
     * leaves every refusal assertion passing, because {@code denyAll} refuses just as firmly - fails
     * here instead of silently closing a screen.
     */
    @Nested
    class TheDeclaredMatrix {

        @Test
        void SRS313_theTraderArea_coversEveryTraderRowOfTheMatrix() {
            assertThat(SecurityConfig.TRADER_AREA)
                    .as("SRS 3.1.3, rows SCR-07 to SCR-32, against the catalogue of TECHNICAL_DESIGN 8")
                    .containsExactly(
                            "/api/v1/me/**",
                            "/api/v1/analysis/**",
                            "/api/v1/watchlist/**",
                            "/api/v1/alerts/**",
                            "/api/v1/notifications/**",
                            "/api/v1/plans/**",
                            "/api/v1/journal/**",
                            "/api/v1/performance",
                            "/api/v1/posts/**",
                            "/api/v1/comments/**",
                            "/api/v1/media/**",
                            "/api/v1/news/**",
                            "/api/v1/ai/**",
                            "/api/v1/billing/packages",
                            "/api/v1/billing/orders/**",
                            "/api/v1/billing/subscription");
        }

        /**
         * The billing paths are named individually. A wildcard here would swallow the two payment
         * callbacks and the gateway return, so the assertion is really about what is absent.
         */
        @Test
        void BR53_theBillingRule_doesNotWildcardOverTheCallbacks() {
            assertThat(SecurityConfig.TRADER_AREA).doesNotContain("/api/v1/billing/**");
        }

        @Test
        void SRS313_theAdministrationConsole_isOnePrefix() {
            assertThat(SecurityConfig.ADMIN_CONSOLE).isEqualTo("/api/v1/admin/**");
        }

        @Test
        void SRS313_theSharedRow_isTheSecurityTabAlone() {
            assertThat(SecurityConfig.SHARED_PASSWORD_CHANGE).isEqualTo("/api/v1/me/password");
        }

        @Test
        void SRS313_thePublicMarketData_isTheMarketPrefixAlone() {
            assertThat(SecurityConfig.PUBLIC_MARKET_DATA).isEqualTo("/api/v1/market/**");
        }
    }

    /** Neither refused by the chain, nor broken by it. */
    private static void assertAdmitted(ResultActions performed) throws Exception {
        int status = performed.andReturn().getResponse().getStatus();
        assertThat(status)
                .as("the filter chain admitted the request; what answers it is the business of the task that builds it")
                .isNotIn(401, 403)
                .isLessThan(500);
    }

    private static void assertForbidden(ResultActions performed) throws Exception {
        assertThat(performed.andReturn().getResponse().getStatus())
                .as("authenticated, and the role does not reach this path")
                .isEqualTo(403);
    }

    private static void assertUnauthenticated(ResultActions performed) throws Exception {
        assertThat(performed.andReturn().getResponse().getStatus())
                .as("no usable credential was presented")
                .isEqualTo(401);
    }

    private String bearer(UserRole role) {
        return "Bearer " + tokenWithRole(role.name());
    }

    /** A token this application would accept, carrying the role given, or none when {@code null}. */
    private String tokenWithRole(String role) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(JwtConfig.ISSUER)
                .subject(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(900));
        if (role != null) {
            claims.claim(JwtConfig.ROLE_CLAIM, role);
        }
        return jwtEncoder
                .encode(JwtEncoderParameters.from(
                        JwsHeader.with(JwtConfig.ALGORITHM).build(), claims.build()))
                .getTokenValue();
    }
}
