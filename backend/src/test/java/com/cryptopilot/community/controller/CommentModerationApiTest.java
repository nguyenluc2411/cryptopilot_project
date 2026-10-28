package com.cryptopilot.community.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cryptopilot.auth.config.JwtConfig;
import com.cryptopilot.support.InAppNotifications;
import com.cryptopilot.support.TestcontainersConfig;
import com.cryptopilot.user.model.enums.UserRole;
import com.jayway.jsonpath.JsonPath;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The decisions on a reported comment that {@link CommunityExtrasApiTest} does not walk through: WARN_USER, DISMISS,
 * REMOVE_COMMENT, BAN_USER of an administrator or of a member banned already, and the refusals of the queue and of a
 * restore. A restore asks whether the author is banned, not whether they have a public profile.
 *
 * <p>Rule: UC-43; BR-05, BR-57, BR-58; SRS 3.11.3; D-125.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class CommentModerationApiTest {

    private static final String POSTS = "/api/v1/posts";
    private static final String COMMENTS = "/api/v1/comments/";
    private static final String COMMENT_QUEUE = "/api/v1/admin/comment-reports-queue/";
    private static final String RESTORE_COMMENT = "/api/v1/admin/comments/";
    private static final String RESTORE_POST = "/api/v1/admin/posts/";

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
        for (UUID account : accounts) {
            sql.sql("delete from audit_log where user_id = ?").param(account).update();
            sql.sql("delete from post_report where reporter_id = ? or resolved_by = ?")
                    .params(account, account)
                    .update();
            sql.sql("delete from user_relation where source_user_id = ? or target_user_id = ?")
                    .params(account, account)
                    .update();
            sql.sql("delete from forum_comment where author_id = ?")
                    .param(account)
                    .update();
        }
        for (UUID account : accounts) {
            sql.sql("delete from forum_post where author_id = ?").param(account).update();
        }
        for (UUID account : accounts) {
            sql.sql("delete from user_token where user_id = ?").param(account).update();
            sql.sql("delete from user_account where user_id = ?").param(account).update();
        }
    }

    @Test
    void UC43_warnUser_leavesTheCommentPublished_resolvesTheReports_andIsAudited() throws Exception {
        UUID alice = account(UserRole.TRADER, "Alice");
        UUID bob = account(UserRole.TRADER, "Bob");
        UUID admin = account(UserRole.ADMIN, "Moderator");
        String bobComment = comment(bob, newPost(alice), "Borderline");
        report(alice, bobComment);

        decide(admin, bobComment, "WARN_USER")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commentStatus").value("PUBLISHED"))
                .andExpect(jsonPath("$.resolvedReports").value(1))
                .andExpect(jsonPath("$.authorBanned").value(false));

        assertThat(audits(admin)).containsExactly("USER_WARNED");
        assertThat(resolutions(bobComment)).containsExactly("WARN_USER");
        assertThat(accountStatus(bob)).isEqualTo("ACTIVE");
    }

    @Test
    void UC43_dismiss_leavesTheCommentPublished_andAuditsTheDismissedReports() throws Exception {
        UUID alice = account(UserRole.TRADER, "Alice");
        UUID bob = account(UserRole.TRADER, "Bob");
        UUID carol = account(UserRole.TRADER, "Carol");
        UUID admin = account(UserRole.ADMIN, "Moderator");
        String bobComment = comment(bob, newPost(alice), "Just an opinion");
        report(alice, bobComment);
        report(carol, bobComment);

        decide(admin, bobComment, "DISMISS")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commentStatus").value("PUBLISHED"))
                .andExpect(jsonPath("$.resolvedReports").value(2));

        assertThat(audits(admin)).containsExactly("REPORTS_DISMISSED");
        assertThat(resolutions(bobComment)).containsExactly("DISMISS", "DISMISS");
    }

    @Test
    void UC43_removeComment_deletesIt_whichARestoreRefusesAsNotHidden() throws Exception {
        UUID alice = account(UserRole.TRADER, "Alice");
        UUID bob = account(UserRole.TRADER, "Bob");
        UUID admin = account(UserRole.ADMIN, "Moderator");
        String postId = newPost(alice);
        String bobComment = comment(bob, postId, "Spam spam spam");
        report(alice, bobComment);

        decide(admin, bobComment, "REMOVE_COMMENT")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commentStatus").value("DELETED"));

        assertThat(audits(admin)).containsExactly("COMMENT_REMOVED");
        as(admin, post(RESTORE_COMMENT + bobComment + "/restore"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COMMENT_NOT_HIDDEN"));
        mvc.perform(get(POSTS + "/" + postId + "/comments"))
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void UC43_aPublishedComment_isNotRestored() throws Exception {
        UUID alice = account(UserRole.TRADER, "Alice");
        UUID admin = account(UserRole.ADMIN, "Moderator");
        String aliceComment = comment(alice, newPost(alice), "Fine words");

        as(admin, post(RESTORE_COMMENT + aliceComment + "/restore"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COMMENT_NOT_HIDDEN"));
        assertThat(audits(admin)).isEmpty();
    }

    @Test
    void BR58_banningForAnAdministratorsComment_isRefused_andChangesNothing() throws Exception {
        UUID alice = account(UserRole.TRADER, "Alice");
        UUID otherAdmin = account(UserRole.ADMIN, "Other moderator");
        UUID admin = account(UserRole.ADMIN, "Moderator");
        String adminComment = insertComment(otherAdmin, UUID.fromString(newPost(alice)), "PUBLISHED");
        report(alice, adminComment);

        decide(admin, adminComment, "BAN_USER")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.messageCode").value("MSG39"));

        assertThat(commentStatus(adminComment)).isEqualTo("PUBLISHED");
        assertThat(reportStatuses(adminComment)).containsOnly("OPEN");
        assertThat(audits(admin)).isEmpty();
        assertThat(accountStatus(otherAdmin)).isEqualTo("ACTIVE");
    }

    @Test
    void UC43_banningAMemberBannedAlready_hidesTheComment_withoutASecondBan() throws Exception {
        UUID alice = account(UserRole.TRADER, "Alice");
        UUID bob = account(UserRole.TRADER, "Bob");
        UUID admin = account(UserRole.ADMIN, "Moderator");
        String postId = newPost(alice);
        String first = comment(bob, postId, "Scam one");
        String second = comment(bob, postId, "Scam two");
        report(alice, first);
        report(alice, second);

        decide(admin, first, "BAN_USER").andExpect(jsonPath("$.authorBanned").value(true));
        // The ban hides Bob's other comments after it commits (D-98); deciding before that may meet the change.
        await().atMost(Duration.ofSeconds(10)).until(() -> "HIDDEN".equals(commentStatus(second)));
        decide(admin, second, "BAN_USER")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorBanned").value(false))
                .andExpect(jsonPath("$.commentStatus").value("HIDDEN"));

        assertThat(audits(admin)).filteredOn("ACCOUNT_BANNED"::equals).hasSize(1);
        assertThat(accountStatus(bob)).isEqualTo("BANNED");
    }

    @Test
    void UC43_aCommentThatIsNotThere_orHasNoOpenReport_isNotFound() throws Exception {
        UUID alice = account(UserRole.TRADER, "Alice");
        UUID bob = account(UserRole.TRADER, "Bob");
        UUID admin = account(UserRole.ADMIN, "Moderator");
        UUID missing = UUID.randomUUID();
        String bobComment = comment(bob, newPost(alice), "Reported once");

        as(admin, get(COMMENT_QUEUE + missing)).andExpect(status().isNotFound());
        decide(admin, missing.toString(), "DISMISS").andExpect(status().isNotFound());
        as(admin, post(RESTORE_COMMENT + missing + "/restore")).andExpect(status().isNotFound());
        // Not reported yet.
        as(admin, get(COMMENT_QUEUE + bobComment)).andExpect(status().isNotFound());

        report(alice, bobComment);
        decide(admin, bobComment, "DISMISS").andExpect(status().isOk());
        // Decided already: no report of it is open any more.
        decide(admin, bobComment, "HIDE_COMMENT").andExpect(status().isNotFound());
        as(admin, get(COMMENT_QUEUE + bobComment)).andExpect(status().isNotFound());
        assertThat(commentStatus(bobComment)).isEqualTo("PUBLISHED");
    }

    @Test
    void D125_theHiddenContentOfAnAuthorWithoutAProfile_isRestored() throws Exception {
        UUID alice = account(UserRole.TRADER, "Alice");
        UUID nameless = accountWithoutProfile();
        UUID admin = account(UserRole.ADMIN, "Moderator");
        UUID hiddenPost = insertPost(nameless, "HIDDEN");
        String hiddenComment = insertComment(nameless, UUID.fromString(newPost(alice)), "HIDDEN");

        as(admin, post(RESTORE_COMMENT + hiddenComment + "/restore"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));
        as(admin, post(RESTORE_POST + hiddenPost + "/restore")).andExpect(status().isOk());

        assertThat(commentStatus(hiddenComment)).isEqualTo("PUBLISHED");
        assertThat(sql.sql("select post_status from forum_post where post_id = ?")
                        .param(hiddenPost)
                        .query(String.class)
                        .single())
                .isEqualTo("PUBLISHED");
    }

    // ------------------------------------------------------------------------------------------

    private ResultActions decide(UUID admin, String commentId, String action) throws Exception {
        return as(admin, post(COMMENT_QUEUE + commentId + "/decision"), "{\"action\": \"" + action + "\"}");
    }

    private void report(UUID reporter, String commentId) throws Exception {
        as(reporter, post(COMMENTS + commentId + "/reports"), "{\"reason\": \"SPAM\"}")
                .andExpect(status().isCreated());
    }

    private List<String> audits(UUID admin) {
        return sql.sql("select action_code from audit_log where user_id = ? order by created_at")
                .param(admin)
                .query(String.class)
                .list();
    }

    private List<String> reportStatuses(String commentId) {
        return sql.sql("select report_status from post_report where comment_id = ?")
                .param(UUID.fromString(commentId))
                .query(String.class)
                .list();
    }

    private List<String> resolutions(String commentId) {
        return sql.sql("select resolution_action from post_report where comment_id = ? and report_status = 'RESOLVED'")
                .param(UUID.fromString(commentId))
                .query(String.class)
                .list();
    }

    private String commentStatus(String commentId) {
        return sql.sql("select comment_status from forum_comment where comment_id = ?")
                .param(UUID.fromString(commentId))
                .query(String.class)
                .single();
    }

    private String accountStatus(UUID account) {
        return sql.sql("select account_status from user_account where user_id = ?")
                .param(account)
                .query(String.class)
                .single();
    }

    private String newPost(UUID author) throws Exception {
        return JsonPath.read(
                as(author, post(POSTS), "{\"title\": \"A post\", \"content\": \"text\"}")
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.id");
    }

    private String comment(UUID author, String postId, String content) throws Exception {
        return JsonPath.read(
                as(author, post(POSTS + "/" + postId + "/comments"), "{\"content\": \"" + content + "\"}")
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.id");
    }

    private UUID insertPost(UUID author, String status) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        sql.sql("""
                        insert into forum_post (post_id, author_id, title, content, post_type, post_status,
                                                is_official, created_at, updated_at)
                        values (?, ?, 'A post', 'text', 'TEXT', ?, false, ?, ?)""").params(id, author, status, now, now).update();
        return id;
    }

    private String insertComment(UUID author, UUID postId, String status) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        sql.sql("""
                        insert into forum_comment (comment_id, post_id, author_id, content, comment_status,
                                                   created_at, updated_at)
                        values (?, ?, ?, 'A comment', ?, ?, ?)""").params(id, postId, author, status, now, now).update();
        return id.toString();
    }

    private ResultActions as(UUID user, MockHttpServletRequestBuilder request) throws Exception {
        return as(user, request, null);
    }

    private ResultActions as(UUID user, MockHttpServletRequestBuilder request, String body) throws Exception {
        request.header(HttpHeaders.AUTHORIZATION, bearer(user));
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(request);
    }

    // An account as it is between registration and its profile: user_account only.
    private UUID accountWithoutProfile() {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        sql.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status, created_at,
                                                  updated_at)
                        values (?, ?, 'x', 'TRADER', 'ACTIVE', ?, ?)""").params(id, id + "@moderation.invalid", now, now).update();
        accounts.add(id);
        return id;
    }

    private UUID account(UserRole role, String displayName) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        sql.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status, created_at,
                                                  updated_at)
                        values (?, ?, 'x', ?, 'ACTIVE', ?, ?)""")
                .params(id, id + "@moderation.invalid", role.name(), now, now)
                .update();
        sql.sql("insert into user_profile (user_id, display_name, created_at, updated_at) values (?, ?, ?, ?)")
                .params(id, displayName, now, now)
                .update();
        accounts.add(id);
        return id;
    }

    private String bearer(UUID userId) {
        String role = sql.sql("select role from user_account where user_id = ?")
                .param(userId)
                .query(String.class)
                .single();
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(JwtConfig.ISSUER)
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(900))
                .claim(JwtConfig.ROLE_CLAIM, role)
                .build();
        return "Bearer "
                + jwtEncoder
                        .encode(JwtEncoderParameters.from(
                                JwsHeader.with(JwtConfig.ALGORITHM).build(), claims))
                        .getTokenValue();
    }
}
