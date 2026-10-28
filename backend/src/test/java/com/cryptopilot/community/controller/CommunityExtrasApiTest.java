package com.cryptopilot.community.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import java.util.stream.Collectors;
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
 * What makes the community a forum beyond the registered topic (D-125): blocking a member, mentioning members, replies
 * told to the comment's author, post views, reporting and moderating comments, and a banned member taken out of the
 * community.
 *
 * <p>Rule: D-125; UC-43; BR-05, BR-57; SRS 3.11.3.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class CommunityExtrasApiTest {

    private static final String POSTS = "/api/v1/posts";
    private static final String COMMENTS = "/api/v1/comments/";
    private static final String MEMBERS = "/api/v1/community/members/";
    private static final String COMMENT_QUEUE = "/api/v1/admin/comment-reports-queue";

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
            sql.sql("delete from engagement where user_id = ?").param(account).update();
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

    // ---- blocking ---------------------------------------------------------------------------

    @Test
    void D125_aBlock_worksBothWays_evenThroughADirectLink_andOnlyTheBlockerSeesTheOthersPage() throws Exception {
        UUID alice = account(UserRole.TRADER, "Alice");
        UUID bob = account(UserRole.TRADER, "Bob");
        UUID carol = account(UserRole.TRADER, "Carol");
        String fromBob = newPost(bob, "From Bob");
        String fromAlice = newPost(alice, "From Alice");
        as(alice, post(MEMBERS + bob + "/follow")).andExpect(status().isOk());
        as(bob, post(MEMBERS + alice + "/follow")).andExpect(status().isOk());
        as(carol, post(MEMBERS + alice + "/follow")).andExpect(status().isOk());

        as(alice, post(MEMBERS + bob + "/block"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.count").value(1));

        // The blocker still opens the blocked member's page, to lift the block there.
        as(alice, get(MEMBERS + bob))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blockedByMe").value(true))
                .andExpect(jsonPath("$.followedByMe").value(false))
                .andExpect(jsonPath("$.followers").value(0))
                .andExpect(jsonPath("$.following").value(0));
        // The blocked member no longer finds the blocker anywhere.
        as(bob, get(MEMBERS + alice)).andExpect(status().isNotFound());
        as(bob, get(MEMBERS + alice + "/followers")).andExpect(status().isNotFound());
        as(bob, get(MEMBERS + carol + "/following"))
                .andExpect(jsonPath("$.total").value(0));
        mvc.perform(get(MEMBERS + carol + "/following"))
                .andExpect(jsonPath("$.total").value(1));
        // Neither sees the other's posts, in the feed or through a direct link.
        as(alice, get(POSTS + "?author=" + bob)).andExpect(jsonPath("$.total").value(0));
        as(bob, get(POSTS + "?author=" + alice)).andExpect(jsonPath("$.total").value(0));
        as(alice, get(POSTS + "/" + fromBob)).andExpect(status().isNotFound());
        as(bob, get(POSTS + "/" + fromAlice)).andExpect(status().isNotFound());
        as(bob, get(POSTS + "/" + fromAlice + "/comments")).andExpect(status().isNotFound());
        // Nor likes, saves or reports it.
        as(bob, post(POSTS + "/" + fromAlice + "/like")).andExpect(status().isNotFound());
        as(bob, post(POSTS + "/" + fromAlice + "/save")).andExpect(status().isNotFound());
        as(bob, post(POSTS + "/" + fromAlice + "/reports"), "{\"reason\": \"SPAM\"}")
                .andExpect(status().isNotFound());
        as(alice, post(POSTS + "/" + fromBob + "/like")).andExpect(status().isNotFound());
        // Others still see both.
        as(carol, get(POSTS + "/" + fromBob)).andExpect(status().isOk());
        mvc.perform(get(POSTS + "/" + fromAlice)).andExpect(status().isOk());
        // No follow either way.
        as(bob, post(MEMBERS + alice + "/follow")).andExpect(status().isNotFound());
        as(alice, post(MEMBERS + bob + "/follow"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RELATION_NOT_ALLOWED"));
        as(alice, get("/api/v1/community/blocks"))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].userId").value(bob.toString()));

        as(alice, delete(MEMBERS + bob + "/block"))
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.count").value(0));
        as(alice, get(POSTS + "/" + fromBob)).andExpect(status().isOk());
        as(bob, get(MEMBERS + alice)).andExpect(status().isOk());
        as(bob, post(MEMBERS + alice + "/follow")).andExpect(status().isOk());
    }

    @Test
    void D125_inABlock_neitherCommentsOnTheOthersPosts_norRepliesToTheirComments_norSeesTheirComments()
            throws Exception {
        UUID alice = account(UserRole.TRADER, "Alice");
        UUID bob = account(UserRole.TRADER, "Bob");
        UUID carol = account(UserRole.TRADER, "Carol");
        String alicePost = newPost(alice, "Alice's post");
        String bobPost = newPost(bob, "Bob's post");
        String carolPost = newPost(carol, "Carol's post");
        String aliceComment = comment(alice, carolPost, "Alice here", null);
        String bobComment = comment(bob, carolPost, "Bob here", null);

        as(alice, post(MEMBERS + bob + "/block")).andExpect(status().isOk());

        as(bob, post(POSTS + "/" + alicePost + "/comments"), "{\"content\": \"Hi\"}")
                .andExpect(status().isNotFound());
        as(alice, post(POSTS + "/" + bobPost + "/comments"), "{\"content\": \"Hi\"}")
                .andExpect(status().isNotFound());
        as(
                        bob,
                        post(POSTS + "/" + carolPost + "/comments"),
                        "{\"content\": \"Hi\", \"parentCommentId\": \"" + aliceComment + "\"}")
                .andExpect(status().isNotFound());
        as(bob, post(COMMENTS + aliceComment + "/like")).andExpect(status().isNotFound());
        as(alice, post(COMMENTS + bobComment + "/reports"), "{\"reason\": \"SPAM\"}")
                .andExpect(status().isNotFound());
        as(alice, get(POSTS + "/" + carolPost + "/comments"))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].author.userId").value(alice.toString()));
        as(bob, get(POSTS + "/" + carolPost + "/comments"))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].author.userId").value(bob.toString()));
        as(carol, get(POSTS + "/" + carolPost + "/comments"))
                .andExpect(jsonPath("$.total").value(2));
        as(alice, get(POSTS + "/" + carolPost))
                .andExpect(jsonPath("$.commentCount").value(1));
        as(bob, get(POSTS + "/" + carolPost))
                .andExpect(jsonPath("$.commentCount").value(1));
        as(carol, get(POSTS + "/" + carolPost))
                .andExpect(jsonPath("$.commentCount").value(2));
    }

    @Test
    void D125_nobodyBlocksThemselves_norFollowsOrBlocksAnAdministrator_andUnfollowingNobodyIsFine() throws Exception {
        UUID alice = account(UserRole.TRADER, "Alice");
        UUID admin = account(UserRole.ADMIN, "Moderator");

        as(alice, post(MEMBERS + alice + "/block"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BLOCK_SELF"));
        as(alice, post(MEMBERS + admin + "/follow"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RELATION_NOT_ALLOWED"));
        as(alice, post(MEMBERS + admin + "/block")).andExpect(jsonPath("$.code").value("RELATION_NOT_ALLOWED"));
        as(alice, delete(MEMBERS + alice + "/follow")).andExpect(status().isOk());
        as(alice, delete(MEMBERS + alice + "/block")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/community/blocks")).andExpect(status().isUnauthorized());
    }

    // ---- mentions and replies ---------------------------------------------------------------

    @Test
    void D125_theMentionSearch_findsTraders_withoutTheCaller_administrators_orBlockedMembers() throws Exception {
        String tag = "Zq" + UUID.randomUUID().toString().substring(0, 6);
        UUID alice = account(UserRole.TRADER, tag + " Alice");
        UUID bob = account(UserRole.TRADER, tag + " Bob");
        UUID carol = account(UserRole.TRADER, tag + " Carol");
        account(UserRole.ADMIN, tag + " Admin");
        as(carol, post(MEMBERS + alice + "/block")).andExpect(status().isOk());

        as(alice, get("/api/v1/community/members?q=" + tag.toLowerCase()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].userId").value(bob.toString()));
        as(alice, get("/api/v1/community/members?q=")).andExpect(status().isBadRequest());
        as(alice, get("/api/v1/community/members?q=%25"))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void D125_aMention_tellsTheMembersMentioned_once_andLeavesOutTheAuthorAndBlockers() throws Exception {
        UUID alice = account(UserRole.TRADER, "Alice");
        UUID bob = account(UserRole.TRADER, "Bob");
        UUID carol = account(UserRole.TRADER, "Carol");
        UUID dave = account(UserRole.TRADER, "Dave");
        as(dave, post(MEMBERS + alice + "/block")).andExpect(status().isOk());

        String postId = JsonPath.read(
                as(
                                alice,
                                post(POSTS),
                                "{\"title\": \"Ask @Bob\", \"content\": \"@Bob @Dave what now?\", \"mentions\": [\""
                                        + bob + "\", \"" + dave + "\", \"" + alice + "\", \"" + UUID.randomUUID()
                                        + "\"]}")
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.id");
        as(
                        alice,
                        put(POSTS + "/" + postId),
                        "{\"title\": \"Ask @Bob\", \"content\": \"@Bob again\", \"mentions\": [\"" + bob + "\"]}")
                .andExpect(status().isOk());
        comment(alice, postId, "@Carol too", carol);

        await().atMost(Duration.ofSeconds(10)).until(() -> mentions(bob) == 1 && mentions(carol) == 1);
        InAppNotifications.remove(sql, List.of());
        assertThat(mentions(bob)).isEqualTo(1);
        assertThat(mentions(dave)).isZero();
        assertThat(mentions(alice)).isZero();
        as(bob, get("/api/v1/notifications"))
                .andExpect(jsonPath("$.items[0].type").value("MENTION"))
                .andExpect(jsonPath("$.items[0].content").value("Alice mentioned you in a post."))
                .andExpect(jsonPath("$.items[0].referenceId").value(postId));
    }

    @Test
    void D125_aReply_tellsTheAuthorOfTheCommentRepliedTo() throws Exception {
        UUID alice = account(UserRole.TRADER, "Alice");
        UUID bob = account(UserRole.TRADER, "Bob");
        UUID carol = account(UserRole.TRADER, "Carol");
        String postId = newPost(alice, "Alice's post");
        String bobComment = comment(bob, postId, "Bob's take", null);

        comment(carol, postId, "Carol answers Bob", UUID.fromString(bobComment));

        await().atMost(Duration.ofSeconds(10)).until(() -> notifications(bob, "COMMENT") == 1);
        as(bob, get("/api/v1/notifications"))
                .andExpect(jsonPath("$.items[0].title").value("New reply to your comment"))
                .andExpect(jsonPath("$.items[0].content").value("Carol replied to your comment."));
        await().atMost(Duration.ofSeconds(10)).until(() -> notifications(alice, "COMMENT") == 2);
    }

    // ---- views ------------------------------------------------------------------------------

    @Test
    void D125_aPostsViews_countEachSignedInReaderOnce_butNeitherTheAuthorNorAGuest() throws Exception {
        UUID alice = account(UserRole.TRADER, "Alice");
        UUID bob = account(UserRole.TRADER, "Bob");
        UUID carol = account(UserRole.TRADER, "Carol");
        String postId = newPost(alice, "Read me");

        as(alice, get(POSTS + "/" + postId)).andExpect(jsonPath("$.viewCount").value(0));
        mvc.perform(get(POSTS + "/" + postId)).andExpect(jsonPath("$.viewCount").value(0));
        as(bob, get(POSTS + "/" + postId)).andExpect(jsonPath("$.viewCount").value(1));
        as(bob, get(POSTS + "/" + postId)).andExpect(jsonPath("$.viewCount").value(1));
        as(carol, get(POSTS + "/" + postId)).andExpect(jsonPath("$.viewCount").value(2));
        mvc.perform(get(POSTS + "?author=" + alice))
                .andExpect(jsonPath("$.items[0].viewCount").value(2));
    }

    // ---- comment reports and their moderation -----------------------------------------------

    @Test
    void D125_aComment_isReportedOnce_neverByItsAuthor_andTheReportKeepsItsText() throws Exception {
        UUID alice = account(UserRole.TRADER, "Alice");
        UUID bob = account(UserRole.TRADER, "Bob");
        UUID admin = account(UserRole.ADMIN, "Moderator");
        String postId = newPost(alice, "Alice's post");
        String bobComment = comment(bob, postId, "Buy my scam coin", null);

        as(bob, post(COMMENTS + bobComment + "/reports"), "{\"reason\": \"SCAM\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COMMENT_REPORT_OWN_COMMENT"));
        as(alice, post(COMMENTS + bobComment + "/reports"), "{\"reason\": \"SCAM\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.commentId").value(bobComment))
                .andExpect(jsonPath("$.postId").value(postId))
                .andExpect(jsonPath("$.status").value("OPEN"));
        as(alice, post(COMMENTS + bobComment + "/reports"), "{\"reason\": \"SPAM\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COMMENT_ALREADY_REPORTED"));
        as(carol(), post(COMMENTS + bobComment + "/reports"), "{\"reason\": \"OTHER\"}")
                .andExpect(status().isBadRequest());
        as(bob, put(COMMENTS + bobComment), "{\"content\": \"Nothing to see\"}").andExpect(status().isOk());

        as(admin, get("/api/v1/admin/reports-queue"))
                .andExpect(jsonPath("$.items[?(@.post.id == '" + postId + "')]").isEmpty());
        as(admin, get(COMMENT_QUEUE))
                .andExpect(jsonPath("$.items[?(@.comment.id == '" + bobComment + "')].reportCount")
                        .value(1));
        as(admin, get(COMMENT_QUEUE + "/" + bobComment))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.comment.content").value("Nothing to see"))
                .andExpect(jsonPath("$.comment.postTitle").value("Alice's post"))
                .andExpect(jsonPath("$.reports[0].reportedContent").value("Buy my scam coin"))
                .andExpect(jsonPath("$.reports[0].status").value("REVIEWING"));
    }

    @Test
    void D125_hidingAComment_showsAPlaceholderWhileItHasReplies_tellsTheAuthorAndReporter_andCanBeUndone()
            throws Exception {
        UUID alice = account(UserRole.TRADER, "Alice");
        UUID bob = account(UserRole.TRADER, "Bob");
        UUID admin = account(UserRole.ADMIN, "Moderator");
        String postId = newPost(alice, "Alice's post");
        String bobComment = comment(bob, postId, "Rude words", null);
        comment(alice, postId, "Please be nice", UUID.fromString(bobComment));
        as(alice, post(COMMENTS + bobComment + "/reports"), "{\"reason\": \"ABUSE\"}")
                .andExpect(status().isCreated());

        as(admin, post(COMMENT_QUEUE + "/" + bobComment + "/decision"), "{\"action\": \"HIDE_POST\"}")
                .andExpect(status().isBadRequest());
        as(admin, post(COMMENT_QUEUE + "/" + bobComment + "/decision"), "{\"action\": \"HIDE_COMMENT\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commentStatus").value("HIDDEN"))
                .andExpect(jsonPath("$.resolvedReports").value(1));

        mvc.perform(get(POSTS + "/" + postId + "/comments"))
                .andExpect(jsonPath("$.items[0].hidden").value(true))
                .andExpect(jsonPath("$.items[0].content").doesNotExist())
                .andExpect(jsonPath("$.items[0].author").doesNotExist())
                .andExpect(jsonPath("$.items[0].replies[0].content").value("Please be nice"));
        as(bob, put(COMMENTS + bobComment), "{\"content\": \"Edited away\"}").andExpect(status().isNotFound());
        as(bob, delete(COMMENTS + bobComment)).andExpect(status().isNotFound());
        await().atMost(Duration.ofSeconds(10))
                .until(() -> notifications(bob, "MODERATION") == 1 && notifications(alice, "MODERATION") == 1);
        assertThat(sql.sql("select count(*) from audit_log where entity_id = ? and action_code = 'COMMENT_HIDDEN'")
                        .param(UUID.fromString(bobComment))
                        .query(Long.class)
                        .single())
                .isEqualTo(1);

        as(admin, post("/api/v1/admin/comments/" + bobComment + "/restore"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));
        as(admin, post("/api/v1/admin/comments/" + bobComment + "/restore"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COMMENT_NOT_HIDDEN"));
        mvc.perform(get(POSTS + "/" + postId + "/comments"))
                .andExpect(jsonPath("$.items[0].content").value("Rude words"));
    }

    @Test
    void D125_aPostsDecision_refusesCommentActions_andCommentReportsStayOutOfThePostQueue() throws Exception {
        UUID alice = account(UserRole.TRADER, "Alice");
        UUID bob = account(UserRole.TRADER, "Bob");
        UUID admin = account(UserRole.ADMIN, "Moderator");
        String postId = newPost(alice, "Alice's post");
        as(bob, post(POSTS + "/" + postId + "/reports"), "{\"reason\": \"SPAM\"}")
                .andExpect(status().isCreated());

        as(admin, post("/api/v1/admin/reports-queue/" + postId + "/decision"), "{\"action\": \"REMOVE_COMMENT\"}")
                .andExpect(status().isBadRequest());
        as(admin, post("/api/v1/admin/reports-queue/" + postId + "/decision"), "{\"action\": \"DISMISS\"}")
                .andExpect(status().isOk());
    }

    // ---- bans -------------------------------------------------------------------------------

    @Test
    void D125_banningForAComment_hidesTheMembersPostsAndComments_andDropsTheirFollowsAndBlocks() throws Exception {
        UUID alice = account(UserRole.TRADER, "Alice");
        UUID bob = account(UserRole.TRADER, "Bob");
        UUID carol = account(UserRole.TRADER, "Carol");
        UUID admin = account(UserRole.ADMIN, "Moderator");
        String alicePost = newPost(alice, "Alice's post");
        String bobPost = newPost(bob, "Bob's post");
        String bobComment = comment(bob, alicePost, "Scam link", null);
        comment(bob, alicePost, "Another one", null);
        as(bob, post(MEMBERS + carol + "/follow")).andExpect(status().isOk());
        as(carol, post(MEMBERS + bob + "/follow")).andExpect(status().isOk());
        as(alice, post(COMMENTS + bobComment + "/reports"), "{\"reason\": \"SCAM\"}")
                .andExpect(status().isCreated());

        as(admin, post(COMMENT_QUEUE + "/" + bobComment + "/decision"), "{\"action\": \"BAN_USER\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorBanned").value(true))
                .andExpect(jsonPath("$.commentStatus").value("HIDDEN"));

        await().atMost(Duration.ofSeconds(10))
                .until(() ->
                        sql.sql("select count(*) from user_relation where source_user_id = ? or target_user_id = ?")
                                        .params(bob, bob)
                                        .query(Long.class)
                                        .single()
                                == 0);
        await().atMost(Duration.ofSeconds(10)).until(() -> "HIDDEN"
                .equals(sql.sql("select post_status from forum_post where post_id = ?")
                        .param(UUID.fromString(bobPost))
                        .query(String.class)
                        .single()));
        assertThat(sql.sql("select count(*) from forum_comment where author_id = ? and comment_status = 'PUBLISHED'")
                        .param(bob)
                        .query(Long.class)
                        .single())
                .isZero();
        mvc.perform(get(POSTS + "/" + alicePost + "/comments"))
                .andExpect(jsonPath("$.total").value(0));
        mvc.perform(get(MEMBERS + carol)).andExpect(jsonPath("$.followers").value(0));
        as(admin, post("/api/v1/admin/comments/" + bobComment + "/restore"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUTHOR_BANNED"));
        as(admin, post("/api/v1/admin/posts/" + bobPost + "/restore"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUTHOR_BANNED"));
    }

    @Test
    void D125_editingAPostAgain_tellsAtMostTenMembersOfItsMentions() throws Exception {
        UUID alice = account(UserRole.TRADER, "Alice");
        List<UUID> members = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            members.add(account(UserRole.TRADER, "Member " + i));
        }
        String postId = JsonPath.read(
                as(
                                alice,
                                post(POSTS),
                                "{\"title\": \"Hello all\", \"content\": \"Hi\", \"mentions\": "
                                        + mentionList(members.subList(0, 10)) + "}")
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.id");
        await().atMost(Duration.ofSeconds(10)).until(() -> mentions(members.get(9)) == 1);

        as(
                        alice,
                        put(POSTS + "/" + postId),
                        "{\"title\": \"Hello all\", \"content\": \"Hi again\", \"mentions\": "
                                + mentionList(members.subList(10, 11)) + "}")
                .andExpect(status().isOk());
        InAppNotifications.remove(sql, List.of());

        assertThat(mentions(members.get(10))).isZero();
        assertThat(mentions(members.get(0))).isEqualTo(1);
    }

    // ------------------------------------------------------------------------------------------

    private static String mentionList(List<UUID> members) {
        return members.stream().map(id -> "\"" + id + "\"").collect(Collectors.joining(", ", "[", "]"));
    }

    private UUID carol() {
        return account(UserRole.TRADER, "Carol");
    }

    private long mentions(UUID user) {
        return notifications(user, "MENTION");
    }

    private long notifications(UUID user, String type) {
        return sql.sql("select count(*) from notification where user_id = ? and notification_type = ?")
                .params(user, type)
                .query(Long.class)
                .single();
    }

    private String newPost(UUID author, String title) throws Exception {
        return JsonPath.read(
                as(author, post(POSTS), "{\"title\": \"" + title + "\", \"content\": \"text\"}")
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.id");
    }

    private String comment(UUID author, String postId, String content, UUID parentOrMention) throws Exception {
        String body;
        if (parentOrMention == null) {
            body = "{\"content\": \"" + content + "\"}";
        } else if (content.startsWith("@")) {
            body = "{\"content\": \"" + content + "\", \"mentions\": [\"" + parentOrMention + "\"]}";
        } else {
            body = "{\"content\": \"" + content + "\", \"parentCommentId\": \"" + parentOrMention + "\"}";
        }
        return JsonPath.read(
                as(author, post(POSTS + "/" + postId + "/comments"), body)
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.id");
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
                        values (?, ?, 'x', ?, 'ACTIVE', ?, ?)""").params(id, id + "@extras.invalid", role.name(), now, now).update();
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
