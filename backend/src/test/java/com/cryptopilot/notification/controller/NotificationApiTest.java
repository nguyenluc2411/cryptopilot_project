package com.cryptopilot.notification.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cryptopilot.auth.config.JwtConfig;
import com.cryptopilot.community.event.PostCommented;
import com.cryptopilot.notification.service.CommunityNotificationService;
import com.cryptopilot.notification.service.NotificationService;
import com.cryptopilot.support.InAppNotifications;
import com.cryptopilot.support.TestcontainersConfig;
import com.cryptopilot.user.model.enums.UserRole;
import com.jayway.jsonpath.JsonPath;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 * The notification centre (SRS 3.4.5, UC-15) and the community notifications that fill it (E-1): a comment tells the
 * post's author, a follow the member followed, a moderation decision the author and the reporters; the bell counts
 * the unread ones, which are marked read one by one or all at once; NSF-17 deletes those older than 90 days.
 *
 * <p>Rule: UC-15; SRS 3.4.5, 3.7.3, 3.11.3; D-99; NSF-15, NSF-17.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class NotificationApiTest {

    private static final String NOTIFICATIONS = "/api/v1/notifications";
    private static final String POSTS = "/api/v1/posts";
    private static final String MEMBERS = "/api/v1/community/members/";
    private static final String QUEUE = "/api/v1/admin/reports-queue";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient sql;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private CommunityNotificationService community;

    @Autowired
    private NotificationService notifications;

    private final List<UUID> accounts = new ArrayList<>();

    @AfterEach
    void removeRows() {
        InAppNotifications.remove(sql, accounts);
        for (UUID account : accounts) {
            sql.sql("delete from audit_log where user_id = ?").param(account).update();
            sql.sql("delete from post_report where reporter_id = ? or resolved_by = ?")
                    .params(account, account)
                    .update();
            sql.sql("delete from user_follow where follower_id = ? or followee_id = ?")
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
            sql.sql("delete from user_account where user_id = ?").param(account).update();
        }
    }

    @Test
    void SRS373_aComment_tellsThePostsAuthor_butNotTheirOwn() throws Exception {
        UUID alice = account(UserRole.TRADER, "Alice");
        UUID bob = account(UserRole.TRADER, "Bob");
        String post = newPost(alice);

        as(alice, post(POSTS + "/" + post + "/comments"), "{\"content\": \"My own comment\"}")
                .andExpect(status().isCreated());
        as(bob, post(POSTS + "/" + post + "/comments"), "{\"content\": \"Nice call\"}")
                .andExpect(status().isCreated());
        awaitNotifications(alice, 1);

        as(alice, get(NOTIFICATIONS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].type").value("COMMENT"))
                .andExpect(jsonPath("$.items[0].title").value("New comment on your post"))
                .andExpect(jsonPath("$.items[0].content").value("Bob commented on your post."))
                .andExpect(jsonPath("$.items[0].referenceType").value("POST"))
                .andExpect(jsonPath("$.items[0].referenceId").value(post))
                .andExpect(jsonPath("$.items[0].read").value(false));
        as(alice, get(NOTIFICATIONS + "/unread-count"))
                .andExpect(jsonPath("$.unread").value(1));
        as(bob, get(NOTIFICATIONS)).andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void NSF15_anEventDeliveredTwice_notifiesOnce() throws Exception {
        UUID alice = account(UserRole.TRADER, "Alice");
        UUID bob = account(UserRole.TRADER, "Bob");
        PostCommented commented =
                new PostCommented(UUID.randomUUID(), alice, UUID.randomUUID(), bob, null, Instant.now());

        community.commented(commented);
        community.commented(commented);

        assertThat(count(alice)).isEqualTo(1);
    }

    @Test
    void D99_aFollow_tellsTheMemberFollowed_onceWhileUnread() throws Exception {
        UUID alice = account(UserRole.TRADER, "Alice");
        UUID bob = account(UserRole.TRADER, "Bob");

        as(bob, post(MEMBERS + alice + "/follow")).andExpect(status().isOk());
        awaitNotifications(alice, 1);
        as(bob, delete(MEMBERS + alice + "/follow")).andExpect(status().isOk());
        as(bob, post(MEMBERS + alice + "/follow")).andExpect(status().isOk());
        awaitDelivered();

        assertThat(count(alice)).isEqualTo(1);
        as(alice, get(NOTIFICATIONS))
                .andExpect(jsonPath("$.items[0].type").value("FOLLOW"))
                .andExpect(jsonPath("$.items[0].content").value("Bob started following you."))
                .andExpect(jsonPath("$.items[0].referenceType").value("MEMBER"))
                .andExpect(jsonPath("$.items[0].referenceId").value(bob.toString()));
    }

    @Test
    void SRS3113_aModerationDecision_tellsTheAuthorAndEachReporter() throws Exception {
        UUID admin = account(UserRole.ADMIN, "Moderator");
        UUID author = account(UserRole.TRADER, "Author");
        UUID first = account(UserRole.TRADER, "First");
        UUID second = account(UserRole.TRADER, "Second");
        String post = newPost(author);
        report(first, post);
        report(second, post);

        as(admin, post(QUEUE + "/" + post + "/decision"), "{\"action\": \"HIDE_POST\", \"note\": \"Pump and dump\"}")
                .andExpect(status().isOk());
        awaitNotifications(author, 1);
        awaitNotifications(first, 1);
        awaitNotifications(second, 1);

        as(author, get(NOTIFICATIONS))
                .andExpect(jsonPath("$.items[0].type").value("MODERATION"))
                .andExpect(jsonPath("$.items[0].title").value("Your post was hidden"))
                .andExpect(jsonPath("$.items[0].content")
                        .value("A moderator hid your post because it breaks the community rules."
                                + " Moderator's note: Pump and dump"))
                .andExpect(jsonPath("$.items[0].referenceId").value(post));
        as(first, get(NOTIFICATIONS))
                .andExpect(jsonPath("$.items[0].title").value("Your report was reviewed"))
                .andExpect(jsonPath("$.items[0].content")
                        .value("A moderator reviewed the post you reported: it was hidden."));
    }

    @Test
    void SRS3113_dismissedReports_areToldToTheReportersOnly() throws Exception {
        UUID admin = account(UserRole.ADMIN, "Moderator");
        UUID author = account(UserRole.TRADER, "Author");
        UUID reporter = account(UserRole.TRADER, "Reporter");
        String post = newPost(author);
        report(reporter, post);

        as(admin, post(QUEUE + "/" + post + "/decision"), "{\"action\": \"DISMISS\"}")
                .andExpect(status().isOk());
        awaitNotifications(reporter, 1);
        awaitDelivered();

        assertThat(count(author)).isZero();
    }

    @Test
    void UC15_notificationsAreMarkedRead_oneOrAll_andOnlyTheCallersOwn() throws Exception {
        UUID alice = account(UserRole.TRADER, "Alice");
        UUID bob = account(UserRole.TRADER, "Bob");
        UUID first = notification(alice, Instant.now().minusSeconds(60));
        UUID second = notification(alice, Instant.now());
        notification(alice, Instant.now().plusSeconds(1));

        String readAt = JsonPath.read(
                as(alice, post(NOTIFICATIONS + "/" + first + "/read"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.read").value(true))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.readAt");
        as(alice, post(NOTIFICATIONS + "/" + first + "/read"))
                .andExpect(jsonPath("$.readAt").value(readAt));
        as(bob, post(NOTIFICATIONS + "/" + second + "/read")).andExpect(status().isNotFound());

        as(alice, get(NOTIFICATIONS + "/unread-count"))
                .andExpect(jsonPath("$.unread").value(2));
        as(alice, get(NOTIFICATIONS + "?unreadOnly=true&pageSize=1"))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items.length()").value(1));
        as(alice, get(NOTIFICATIONS))
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items[2].id").value(first.toString()));

        as(alice, post(NOTIFICATIONS + "/read-all"))
                .andExpect(jsonPath("$.unread").value(0));
        as(alice, get(NOTIFICATIONS + "?unreadOnly=true"))
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void UC15_aGuest_isChallenged() throws Exception {
        mvc.perform(get(NOTIFICATIONS)).andExpect(status().isUnauthorized());
    }

    @Test
    void NSF17_notificationsOlderThan90Days_areDeleted() {
        UUID alice = account(UserRole.TRADER, "Alice");
        notification(alice, Instant.now().minus(91, ChronoUnit.DAYS));
        UUID kept = notification(alice, Instant.now().minus(89, ChronoUnit.DAYS));

        assertThat(notifications.removeExpired()).isGreaterThanOrEqualTo(1);

        assertThat(sql.sql("select notification_id from notification where user_id = ?")
                        .param(alice)
                        .query(UUID.class)
                        .list())
                .containsExactly(kept);
    }

    private long count(UUID user) {
        return sql.sql("select count(*) from notification where user_id = ?")
                .param(user)
                .query(Long.class)
                .single();
    }

    private void awaitNotifications(UUID user, long expected) {
        await().atMost(Duration.ofSeconds(10)).until(() -> count(user) >= expected);
    }

    private void awaitDelivered() {
        await().atMost(Duration.ofSeconds(10))
                .until(() -> sql.sql(
                                        "select count(*) from event_publication"
                                                + " where listener_id like '%NotificationListener%' and completion_date is null")
                                .query(Long.class)
                                .single()
                        == 0);
    }

    private UUID notification(UUID user, Instant createdAt) {
        UUID id = UUID.randomUUID();
        Timestamp at = Timestamp.from(createdAt);
        sql.sql("""
                        insert into notification (notification_id, user_id, notification_type, title, content,
                                                  created_at, updated_at)
                        values (?, ?, 'PLAN', 'Plan expired', 'Your plan expired.', ?, ?)""").params(id, user, at, at).update();
        return id;
    }

    private String newPost(UUID author) throws Exception {
        return JsonPath.read(
                as(author, post(POSTS), "{\"title\": \"Moon soon\", \"content\": \"Trust me\"}")
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.id");
    }

    private void report(UUID reporter, String postId) throws Exception {
        as(reporter, post(POSTS + "/" + postId + "/reports"), "{\"reason\": \"SCAM\"}")
                .andExpect(status().isCreated());
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

    private UUID account(UserRole role, String displayName) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        sql.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status, created_at,
                                                  updated_at)
                        values (?, ?, 'x', ?, 'ACTIVE', ?, ?)""").params(id, id + "@e1.invalid", role.name(), now, now).update();
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
