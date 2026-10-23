package com.cryptopilot.community.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cryptopilot.auth.config.JwtConfig;
import com.cryptopilot.community.event.PostModerated;
import com.cryptopilot.support.InAppNotifications;
import com.cryptopilot.support.TestcontainersConfig;
import com.cryptopilot.user.model.enums.UserRole;
import com.jayway.jsonpath.JsonPath;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The moderation queue end to end: posts with open reports gathered, opening a case, each decision applied to the
 * post and the author, every report of the post resolved together, the audit trail, the event that tells the author
 * and the reporters, and restoring a hidden post.
 *
 * <p>Rule: UC-43; BR-06, BR-47, BR-57, BR-58; SRS 3.11.3; D-98.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, ModerationApiTest.Events.class})
class ModerationApiTest {

    private static final String POSTS = "/api/v1/posts";
    private static final String QUEUE = "/api/v1/admin/reports-queue";

    @TestConfiguration(proxyBeanMethods = false)
    static class Events {

        static final List<PostModerated> PUBLISHED = new CopyOnWriteArrayList<>();

        @Bean
        Recorder postModeratedRecorder() {
            return new Recorder();
        }
    }

    static class Recorder {

        @EventListener
        public void on(PostModerated event) {
            Events.PUBLISHED.add(event);
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient sql;

    @Autowired
    private JwtEncoder jwtEncoder;

    private final List<UUID> accounts = new ArrayList<>();

    @AfterEach
    void removeRows() {
        InAppNotifications.remove(sql, accounts);
        // Children first, across all accounts: a resolved report names its moderator, an audit row its actor.
        for (UUID account : accounts) {
            sql.sql("delete from audit_log where user_id = ?").param(account).update();
            sql.sql("delete from post_report where reporter_id = ? or resolved_by = ?")
                    .params(account, account)
                    .update();
        }
        for (UUID account : accounts) {
            sql.sql("delete from forum_post where author_id = ?").param(account).update();
        }
        for (UUID account : accounts) {
            sql.sql("delete from user_account where user_id = ?").param(account).update();
        }
        Events.PUBLISHED.clear();
    }

    @Test
    void UC43_theQueue_gathersTheOpenReportsOfEachPost() throws Exception {
        UUID admin = admin();
        UUID author = trader();
        String post = newPost(author);
        report(trader(), post, "SPAM");
        report(trader(), post, "SCAM");
        report(trader(), post, "SPAM");

        String item = "$.items[?(@.post.id == '" + post + "')]";
        as(admin, UserRole.ADMIN, get(QUEUE + "?pageSize=100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(item + ".reportCount").value(3))
                .andExpect(jsonPath(item + ".reasons[0]").value("SPAM"))
                .andExpect(jsonPath(item + ".reasons[1]").value("SCAM"))
                .andExpect(jsonPath(item + ".post.title").value("Moon soon, 100x"));
    }

    @Test
    void UC43_openingACase_setsItsReportsToReviewing() throws Exception {
        UUID admin = admin();
        UUID author = trader();
        String post = newPost(author);
        report(trader(), post, "MISLEADING");

        as(admin, UserRole.ADMIN, get(QUEUE + "/" + post))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.post.id").value(post))
                .andExpect(jsonPath("$.post.content").value("Trust me"))
                .andExpect(jsonPath("$.reports.length()").value(1))
                .andExpect(jsonPath("$.reports[0].reason").value("MISLEADING"))
                .andExpect(jsonPath("$.reports[0].reporter.displayName").value("Member"))
                .andExpect(jsonPath("$.reports[0].status").value("REVIEWING"));
        assertThat(statuses(post)).containsOnly("REVIEWING");
    }

    @Test
    void UC43_hidePost_resolvesEveryReport_hidesThePost_auditsAndTellsEveryone() throws Exception {
        UUID admin = admin();
        UUID author = trader();
        UUID first = trader();
        UUID second = trader();
        String post = newPost(author);
        report(first, post, "SPAM");
        report(second, post, "SCAM");
        as(admin, UserRole.ADMIN, get(QUEUE + "/" + post));

        decide(admin, post, "{\"action\": \"HIDE_POST\", \"note\": \"  Pump and dump  \"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.postStatus").value("HIDDEN"))
                .andExpect(jsonPath("$.resolvedReports").value(2))
                .andExpect(jsonPath("$.authorBanned").value(false));

        assertThat(statuses(post)).containsOnly("RESOLVED");
        assertThat(sql.sql("select count(*) from post_report where post_id = ? and resolved_by = ?"
                                + " and resolution_action = 'HIDE_POST' and resolved_at is not null")
                        .params(UUID.fromString(post), admin)
                        .query(Long.class)
                        .single())
                .isEqualTo(2);
        assertThat(postStatus(post)).isEqualTo("HIDDEN");
        assertThat(audits(admin)).containsExactly("POST_HIDDEN");
        assertThat(Events.PUBLISHED).singleElement().satisfies(event -> {
            assertThat(event.authorId()).isEqualTo(author);
            assertThat(event.reporterIds()).containsExactlyInAnyOrder(first, second);
            assertThat(event.moderatorId()).isEqualTo(admin);
            assertThat(event.note()).isEqualTo("Pump and dump");
        });
        // Gone from the queue, from other Traders, and nothing left to decide.
        as(admin, UserRole.ADMIN, get(QUEUE + "?pageSize=100"))
                .andExpect(jsonPath("$.items[?(@.post.id == '" + post + "')]").isEmpty());
        as(first, UserRole.TRADER, get(POSTS + "/" + post)).andExpect(status().isNotFound());
        decide(admin, post, "{\"action\": \"DISMISS\"}").andExpect(status().isNotFound());
    }

    @Test
    void BR06_banUser_bansTheAuthor_endsTheirSessions_andHidesThePost() throws Exception {
        UUID admin = admin();
        UUID author = trader();
        String post = newPost(author);
        report(trader(), post, "SCAM");
        UUID refresh = refreshToken(author);

        decide(admin, post, "{\"action\": \"BAN_USER\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.postStatus").value("HIDDEN"))
                .andExpect(jsonPath("$.authorBanned").value(true));

        assertThat(sql.sql("select account_status from user_account where user_id = ?")
                        .param(author)
                        .query(String.class)
                        .single())
                .isEqualTo("BANNED");
        assertThat(sql.sql("select used_at is not null from user_token where token_id = ?")
                        .param(refresh)
                        .query(Boolean.class)
                        .single())
                .isTrue();
        assertThat(audits(admin)).containsExactlyInAnyOrder("POST_HIDDEN", "ACCOUNT_BANNED");
    }

    @Test
    void D99_removePost_deletesThePostForEveryoneButAdmin_andIsAudited() throws Exception {
        UUID admin = admin();
        UUID author = trader();
        String post = newPost(author);
        report(trader(), post, "SCAM");

        decide(admin, post, "{\"action\": \"REMOVE_POST\", \"note\": \"Fraud\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.postStatus").value("DELETED"))
                .andExpect(jsonPath("$.authorBanned").value(false));

        assertThat(postStatus(post)).isEqualTo("DELETED");
        assertThat(statuses(post)).containsOnly("RESOLVED");
        assertThat(audits(admin)).containsExactly("POST_REMOVED");
        as(author, UserRole.TRADER, get(POSTS + "/" + post)).andExpect(status().isNotFound());
        as(admin, UserRole.ADMIN, get("/api/v1/admin/posts/" + post))
                .andExpect(jsonPath("$.status").value("DELETED"));
        // A removed post is not restored like a hidden one.
        as(admin, UserRole.ADMIN, post("/api/v1/admin/posts/" + post + "/restore"))
                .andExpect(status().isConflict());
    }

    @Test
    void UC43_warnAndDismiss_leaveThePostPublished_andAreAudited() throws Exception {
        UUID admin = admin();
        String warned = newPost(trader());
        String dismissed = newPost(trader());
        report(trader(), warned, "ABUSE");
        report(trader(), dismissed, "SPAM");

        decide(admin, warned, "{\"action\": \"WARN_USER\"}")
                .andExpect(jsonPath("$.postStatus").value("PUBLISHED"));
        decide(admin, dismissed, "{\"action\": \"DISMISS\"}")
                .andExpect(jsonPath("$.postStatus").value("PUBLISHED"));

        assertThat(postStatus(warned)).isEqualTo("PUBLISHED");
        assertThat(postStatus(dismissed)).isEqualTo("PUBLISHED");
        assertThat(audits(admin)).containsExactlyInAnyOrder("USER_WARNED", "REPORTS_DISMISSED");
        assertThat(Events.PUBLISHED).hasSize(2);
    }

    @Test
    void BR58_banningAnAdministratorsPost_isRefused_andChangesNothing() throws Exception {
        UUID admin = admin();
        UUID otherAdmin = admin();
        String announcement = JsonPath.read(
                as(
                                otherAdmin,
                                UserRole.ADMIN,
                                post("/api/v1/admin/announcements"),
                                "{\"title\": \"Maintenance tonight\", \"content\": \"text\"}")
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.id");
        report(trader(), announcement, "MISLEADING");

        decide(admin, announcement, "{\"action\": \"BAN_USER\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.messageCode").value("MSG39"));

        assertThat(postStatus(announcement)).isEqualTo("PUBLISHED");
        assertThat(statuses(announcement)).containsOnly("OPEN");
        assertThat(audits(admin)).isEmpty();
        assertThat(Events.PUBLISHED).isEmpty();
    }

    @Test
    void UC43_aHiddenPost_isRestored_andOnlyAHiddenOne() throws Exception {
        UUID admin = admin();
        UUID author = trader();
        String post = newPost(author);
        report(trader(), post, "SPAM");
        decide(admin, post, "{\"action\": \"HIDE_POST\"}");

        as(admin, UserRole.ADMIN, post("/api/v1/admin/posts/" + post + "/restore"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));
        as(admin, UserRole.ADMIN, post("/api/v1/admin/posts/" + post + "/restore"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("POST_NOT_HIDDEN"));
        assertThat(audits(admin)).containsExactlyInAnyOrder("POST_HIDDEN", "POST_RESTORED");
        as(trader(), UserRole.TRADER, get(POSTS + "/" + post)).andExpect(status().isOk());
    }

    @Test
    void BR47_anAdministratorSeesADeletedPost() throws Exception {
        UUID admin = admin();
        UUID author = trader();
        String post = newPost(author);
        as(
                        author,
                        UserRole.TRADER,
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(POSTS + "/" + post))
                .andExpect(status().isNoContent());

        as(admin, UserRole.ADMIN, get("/api/v1/admin/posts/" + post))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELETED"));
    }

    @Test
    void UC43_aDecisionNeedsAnActionAndAPostWithOpenReports() throws Exception {
        UUID admin = admin();
        String post = newPost(trader());

        decide(admin, post, "{\"action\": \"HIDE_POST\"}").andExpect(status().isNotFound());
        report(trader(), post, "SPAM");
        decide(admin, post, "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.action").exists());
        decide(admin, post, "{\"action\": \"DELETE\"}").andExpect(status().isBadRequest());
        decide(admin, post, "{\"action\": \"DISMISS\", \"note\": \"" + "x".repeat(501) + "\"}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void theQueue_isForAdministratorsOnly() throws Exception {
        UUID trader = trader();
        as(trader, UserRole.TRADER, get(QUEUE)).andExpect(status().isForbidden());
        mvc.perform(get(QUEUE)).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------------------------------

    private String newPost(UUID author) throws Exception {
        return JsonPath.read(
                as(author, UserRole.TRADER, post(POSTS), "{\"title\": \"Moon soon, 100x\", \"content\": \"Trust me\"}")
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.id");
    }

    private void report(UUID reporter, String postId, String reason) throws Exception {
        as(reporter, UserRole.TRADER, post(POSTS + "/" + postId + "/reports"), "{\"reason\": \"" + reason + "\"}")
                .andExpect(status().isCreated());
    }

    private ResultActions decide(UUID admin, String postId, String body) throws Exception {
        return as(admin, UserRole.ADMIN, post(QUEUE + "/" + postId + "/decision"), body);
    }

    private List<String> statuses(String postId) {
        return sql.sql("select report_status from post_report where post_id = ?")
                .param(UUID.fromString(postId))
                .query(String.class)
                .list();
    }

    private String postStatus(String postId) {
        return sql.sql("select post_status from forum_post where post_id = ?")
                .param(UUID.fromString(postId))
                .query(String.class)
                .single();
    }

    private List<String> audits(UUID actor) {
        return sql.sql("select action_code from audit_log where user_id = ?")
                .param(actor)
                .query(String.class)
                .list();
    }

    private UUID refreshToken(UUID userId) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        sql.sql("""
                        insert into user_token (token_id, user_id, token_type, token_hash, expires_at, created_at,
                                                updated_at, token_family_id)
                        values (?, ?, 'REFRESH', ?, ?, ?, ?, ?)""")
                .params(
                        id,
                        userId,
                        Long.toHexString(System.nanoTime())
                                + id.toString().replace("-", "").substring(0, 32),
                        Timestamp.from(Instant.now().plusSeconds(3600)),
                        now,
                        now,
                        UUID.randomUUID())
                .update();
        return id;
    }

    private ResultActions as(UUID user, UserRole role, MockHttpServletRequestBuilder request) throws Exception {
        return as(user, role, request, null);
    }

    private ResultActions as(UUID user, UserRole role, MockHttpServletRequestBuilder request, String body)
            throws Exception {
        request.header(HttpHeaders.AUTHORIZATION, bearer(user, role));
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(request);
    }

    private UUID trader() {
        return account(UserRole.TRADER);
    }

    private UUID admin() {
        return account(UserRole.ADMIN);
    }

    private UUID account(UserRole role) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        sql.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status, created_at,
                                                  updated_at)
                        values (?, ?, 'x', ?, 'ACTIVE', ?, ?)""").params(id, id + "@t084.invalid", role.name(), now, now).update();
        sql.sql("insert into user_profile (user_id, display_name, created_at, updated_at) values (?, 'Member', ?, ?)")
                .params(id, now, now)
                .update();
        accounts.add(id);
        return id;
    }

    private String bearer(UUID userId, UserRole role) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(JwtConfig.ISSUER)
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(900))
                .claim(JwtConfig.ROLE_CLAIM, role.name())
                .build();
        return "Bearer "
                + jwtEncoder
                        .encode(JwtEncoderParameters.from(
                                JwsHeader.with(JwtConfig.ALGORITHM).build(), claims))
                        .getTokenValue();
    }
}
