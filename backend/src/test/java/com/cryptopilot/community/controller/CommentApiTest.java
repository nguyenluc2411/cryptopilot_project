package com.cryptopilot.community.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cryptopilot.auth.config.JwtConfig;
import com.cryptopilot.community.event.PostCommented;
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
 * The comments of SCR-25 end to end: threads one level deep, oldest first; editing and deleting own comments; a deleted
 * comment shown in place while it has replies; comments of a post the caller may not see; the event that tells the
 * post's author.
 *
 * <p>Rule: UC-30; BR-47; SRS 3.7.3; CR-04; D-98.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, CommentApiTest.Events.class})
class CommentApiTest {

    private static final String POSTS = "/api/v1/posts";
    private static final String COMMENTS = "/api/v1/comments";

    /** Records every {@link PostCommented} published, as it is published. */
    @TestConfiguration(proxyBeanMethods = false)
    static class Events {

        static final List<PostCommented> PUBLISHED = new CopyOnWriteArrayList<>();

        @Bean
        Recorder postCommentedRecorder() {
            return new Recorder();
        }
    }

    static class Recorder {

        @EventListener
        public void on(PostCommented event) {
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
        for (UUID account : accounts) {
            sql.sql("delete from engagement where user_id = ?").param(account).update();
            sql.sql("delete from forum_comment where author_id = ?")
                    .param(account)
                    .update();
            sql.sql("delete from forum_post where author_id = ?").param(account).update();
            sql.sql("delete from user_account where user_id = ?").param(account).update();
        }
        Events.PUBLISHED.clear();
    }

    @Test
    void UC30_threadsAreOldestFirst_andRepliesHangOneLevelDeep() throws Exception {
        UUID author = trader("Alice");
        UUID bob = trader("Bob");
        UUID carol = trader("Carol");
        String post = newPost(author);
        String first = id(comment(bob, post, "First!", null).andExpect(status().isCreated()));
        String second = id(comment(carol, post, "Second", null));
        String reply = id(comment(carol, post, "Reply to Bob", first)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.parentCommentId").value(first)));
        // A reply to a reply joins the same thread.
        id(comment(bob, post, "Reply to Carol", reply)
                .andExpect(jsonPath("$.parentCommentId").value(first)));

        as(author, get(POSTS + "/" + post + "/comments"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].id").value(first))
                .andExpect(jsonPath("$.items[0].author.displayName").value("Bob"))
                .andExpect(jsonPath("$.items[0].content").value("First!"))
                .andExpect(jsonPath("$.items[0].replies.length()").value(2))
                .andExpect(jsonPath("$.items[0].replies[0].content").value("Reply to Bob"))
                .andExpect(jsonPath("$.items[0].replies[1].content").value("Reply to Carol"))
                .andExpect(jsonPath("$.items[1].id").value(second))
                .andExpect(jsonPath("$.items[1].replies.length()").value(0))
                .andExpect(jsonPath("$.items[0].mine").value(false));
        as(author, get(POSTS + "/" + post), null)
                .andExpect(jsonPath("$.commentCount").value(4));
    }

    @Test
    void UC30_aComment_isAnnouncedToThePostsAuthor() throws Exception {
        UUID author = trader("Alice");
        UUID bob = trader("Bob");
        String post = newPost(author);

        String comment = id(comment(bob, post, "Nice setup", null));

        assertThat(Events.PUBLISHED).singleElement().satisfies(event -> {
            assertThat(event.postId()).hasToString(post);
            assertThat(event.postAuthorId()).isEqualTo(author);
            assertThat(event.commentId()).hasToString(comment);
            assertThat(event.commenterId()).isEqualTo(bob);
            assertThat(event.threadId()).isNull();
        });
    }

    @Test
    void UC30_theAuthorEditsAndDeletesTheirComment_othersCannot() throws Exception {
        UUID author = trader("Alice");
        UUID bob = trader("Bob");
        String post = newPost(author);
        String comment = id(comment(bob, post, "Typo here", null));

        as(author, put(COMMENTS + "/" + comment), "{\"content\": \"Hijacked\"}").andExpect(status().isNotFound());
        as(author, delete(COMMENTS + "/" + comment), null).andExpect(status().isNotFound());
        as(bob, put(COMMENTS + "/" + comment), "{\"content\": \"  Fixed  \"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("Fixed"))
                .andExpect(jsonPath("$.mine").value(true));

        as(bob, delete(COMMENTS + "/" + comment), null).andExpect(status().isNoContent());
        as(bob, put(COMMENTS + "/" + comment), "{\"content\": \"Back\"}").andExpect(status().isNotFound());
        as(bob, delete(COMMENTS + "/" + comment), null).andExpect(status().isNotFound());
        assertThat(sql.sql("select comment_status from forum_comment where comment_id = ?")
                        .param(UUID.fromString(comment))
                        .query(String.class)
                        .single())
                .isEqualTo("DELETED");
    }

    @Test
    void BR47_aDeletedComment_staysInPlaceWhileItHasReplies_withoutTextOrAuthor() throws Exception {
        UUID author = trader("Alice");
        UUID bob = trader("Bob");
        String post = newPost(author);
        String withReply = id(comment(bob, post, "Answered", null));
        comment(author, post, "Answer", withReply);
        String alone = id(comment(bob, post, "Alone", null));

        as(bob, delete(COMMENTS + "/" + withReply), null).andExpect(status().isNoContent());
        as(bob, delete(COMMENTS + "/" + alone), null).andExpect(status().isNoContent());

        as(author, get(POSTS + "/" + post + "/comments"), null)
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(withReply))
                .andExpect(jsonPath("$.items[0].deleted").value(true))
                .andExpect(jsonPath("$.items[0].content").doesNotExist())
                .andExpect(jsonPath("$.items[0].author").doesNotExist())
                .andExpect(jsonPath("$.items[0].replies[0].content").value("Answer"));
        // A deleted comment takes no new replies.
        comment(author, post, "Too late", alone).andExpect(status().isNotFound());
    }

    @Test
    void BR47_theCommentsOfAPostTheCallerMayNotSee_doNotExist() throws Exception {
        UUID author = trader("Alice");
        UUID bob = trader("Bob");
        String post = newPost(author);
        String comment = id(comment(bob, post, "Before hiding", null));
        sql.sql("update forum_post set post_status = 'HIDDEN' where post_id = ?")
                .param(UUID.fromString(post))
                .update();

        as(bob, get(POSTS + "/" + post + "/comments"), null).andExpect(status().isNotFound());
        comment(bob, post, "After hiding", null).andExpect(status().isNotFound());
        as(bob, put(COMMENTS + "/" + comment), "{\"content\": \"Edit\"}").andExpect(status().isNotFound());
        // The author still sees the hidden post and its discussion.
        as(author, get(POSTS + "/" + post + "/comments"), null)
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void UC30_aReply_mustNameACommentOfTheSamePost() throws Exception {
        UUID author = trader("Alice");
        String post = newPost(author);
        String other = newPost(author);
        String elsewhere = id(comment(author, other, "On the other post", null));

        comment(author, post, "Wrong thread", elsewhere).andExpect(status().isNotFound());
        comment(author, post, "No thread", UUID.randomUUID().toString()).andExpect(status().isNotFound());
    }

    @Test
    void UC30_aCommentHasOneTo2000Characters() throws Exception {
        UUID author = trader("Alice");
        String post = newPost(author);

        comment(author, post, "   ", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.content").value("MSG01"));
        comment(author, post, "x".repeat(2_001), null).andExpect(status().isBadRequest());
        comment(author, post, "x".repeat(2_000), null).andExpect(status().isCreated());
        as(author, post(POSTS + "/" + UUID.randomUUID() + "/comments"), "{\"content\": \"Lost\"}")
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------------------------------

    private String newPost(UUID author) throws Exception {
        return id(as(author, post(POSTS), "{\"title\": \"A post to discuss\", \"content\": \"text\"}"));
    }

    private ResultActions comment(UUID author, String postId, String content, String parent) throws Exception {
        String body = "{\"content\": \"" + content + "\""
                + (parent == null ? "" : ", \"parentCommentId\": \"" + parent + "\"") + "}";
        return as(author, post(POSTS + "/" + postId + "/comments"), body);
    }

    private ResultActions as(UUID user, MockHttpServletRequestBuilder request, String body) throws Exception {
        request.header(HttpHeaders.AUTHORIZATION, bearer(user));
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(request);
    }

    private static String id(ResultActions created) throws Exception {
        return JsonPath.read(created.andReturn().getResponse().getContentAsString(), "$.id");
    }

    private UUID trader(String displayName) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        sql.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status, created_at,
                                                  updated_at)
                        values (?, ?, 'x', 'TRADER', 'ACTIVE', ?, ?)""").params(id, id + "@t063.invalid", now, now).update();
        sql.sql("insert into user_profile (user_id, display_name, created_at, updated_at) values (?, ?, ?, ?)")
                .params(id, displayName, now, now)
                .update();
        accounts.add(id);
        return id;
    }

    private String bearer(UUID userId) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(JwtConfig.ISSUER)
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(900))
                .claim(JwtConfig.ROLE_CLAIM, UserRole.TRADER.name())
                .build();
        return "Bearer "
                + jwtEncoder
                        .encode(JwtEncoderParameters.from(
                                JwsHeader.with(JwtConfig.ALGORITHM).build(), claims))
                        .getTokenValue();
    }
}
