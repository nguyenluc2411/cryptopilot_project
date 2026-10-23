package com.cryptopilot.community.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cryptopilot.auth.config.JwtConfig;
import com.cryptopilot.support.TestcontainersConfig;
import com.cryptopilot.user.model.enums.UserRole;
import com.jayway.jsonpath.JsonPath;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
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
 * Likes and saves end to end: one per Trader and target however often or however concurrently it is clicked (BR-45),
 * the counts and flags the feed shows, and targets the caller may not see.
 *
 * <p>Rule: UC-31, UC-32; BR-45, BR-47.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class EngagementApiTest {

    private static final String POSTS = "/api/v1/posts";
    private static final String COMMENTS = "/api/v1/comments";

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
    }

    @Test
    void BR45_aLike_isOncePerTrader_andRepeatingItChangesNothing() throws Exception {
        UUID author = trader();
        UUID fan = trader();
        UUID other = trader();
        String post = newPost(author);

        as(fan, post(POSTS + "/" + post + "/like"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.count").value(1));
        as(fan, post(POSTS + "/" + post + "/like"))
                .andExpect(jsonPath("$.count").value(1));
        as(other, post(POSTS + "/" + post + "/like"))
                .andExpect(jsonPath("$.count").value(2));

        as(fan, get(POSTS + "/" + post))
                .andExpect(jsonPath("$.likeCount").value(2))
                .andExpect(jsonPath("$.likedByMe").value(true));
        as(fan, delete(POSTS + "/" + post + "/like"))
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.count").value(1));
        as(fan, delete(POSTS + "/" + post + "/like"))
                .andExpect(jsonPath("$.count").value(1));
        as(fan, get(POSTS + "/" + post)).andExpect(jsonPath("$.likedByMe").value(false));
    }

    @Test
    void BR45_aSave_putsThePostInTheSavedTab_andTakingItBackRemovesIt() throws Exception {
        UUID author = trader();
        UUID reader = trader();
        String post = newPost(author);

        as(reader, post(POSTS + "/" + post + "/save"))
                .andExpect(jsonPath("$.active").value(true));
        as(reader, post(POSTS + "/" + post + "/save"))
                .andExpect(jsonPath("$.count").value(1));
        as(reader, get(POSTS + "?tab=SAVED"))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].savedByMe").value(true));

        as(reader, delete(POSTS + "/" + post + "/save"))
                .andExpect(jsonPath("$.active").value(false));
        as(reader, get(POSTS + "?tab=SAVED")).andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void BR45_aCommentLike_isOncePerTrader_andShownWithTheComment() throws Exception {
        UUID author = trader();
        UUID fan = trader();
        String post = newPost(author);
        String comment = JsonPath.read(
                as(author, post(POSTS + "/" + post + "/comments"), "{\"content\": \"Worth a like\"}")
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.id");

        as(fan, post(COMMENTS + "/" + comment + "/like"))
                .andExpect(jsonPath("$.count").value(1));
        as(fan, post(COMMENTS + "/" + comment + "/like"))
                .andExpect(jsonPath("$.count").value(1));
        as(fan, get(POSTS + "/" + post + "/comments"))
                .andExpect(jsonPath("$.items[0].likeCount").value(1))
                .andExpect(jsonPath("$.items[0].likedByMe").value(true));
        as(author, get(POSTS + "/" + post + "/comments"))
                .andExpect(jsonPath("$.items[0].likedByMe").value(false));
        as(fan, delete(COMMENTS + "/" + comment + "/like"))
                .andExpect(jsonPath("$.count").value(0));

        as(author, delete(COMMENTS + "/" + comment)).andExpect(status().isNoContent());
        as(fan, post(COMMENTS + "/" + comment + "/like")).andExpect(status().isNotFound());
    }

    /** Two likes of the same Trader at the same moment: the unique index lets one row in and neither fails. */
    @RepeatedTest(5)
    void BR45_twoConcurrentLikes_leaveOneRow_andBothSucceed() throws Exception {
        UUID author = trader();
        UUID fan = trader();
        String post = newPost(author);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Integer> like = () -> {
            start.await();
            return as(fan, post(POSTS + "/" + post + "/like"))
                    .andReturn()
                    .getResponse()
                    .getStatus();
        };
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Integer> first = pool.submit(like);
            Future<Integer> second = pool.submit(like);
            start.countDown();
            assertThat(List.of(first.get(), second.get())).containsOnly(200);
        }

        assertThat(sql.sql("select count(*) from engagement where post_id = ? and user_id = ?")
                        .params(UUID.fromString(post), fan)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void BR45_theDatabase_refusesASecondLikeOfTheSameTarget() throws Exception {
        UUID author = trader();
        UUID fan = trader();
        UUID postId = UUID.fromString(newPost(author));
        String insert = """
                insert into engagement (engagement_id, user_id, post_id, action_type, created_at, updated_at)
                values (?, ?, ?, 'LIKE', now(), now())""";
        sql.sql(insert).params(UUID.randomUUID(), fan, postId).update();

        assertThatThrownBy(() ->
                        sql.sql(insert).params(UUID.randomUUID(), fan, postId).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void BR47_aPostTheCallerMayNotSee_cannotBeLikedOrSaved() throws Exception {
        UUID author = trader();
        UUID fan = trader();
        String post = newPost(author);
        sql.sql("update forum_post set post_status = 'HIDDEN' where post_id = ?")
                .param(UUID.fromString(post))
                .update();

        as(fan, post(POSTS + "/" + post + "/like"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.messageCode").value("MSG41"));
        as(fan, post(POSTS + "/" + post + "/save")).andExpect(status().isNotFound());
        as(fan, post(POSTS + "/" + UUID.randomUUID() + "/like")).andExpect(status().isNotFound());
        as(author, post(POSTS + "/" + post + "/like")).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------------------------------

    private String newPost(UUID author) throws Exception {
        return JsonPath.read(
                as(author, post(POSTS), "{\"title\": \"A post to like\", \"content\": \"text\"}")
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

    private UUID trader() {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        sql.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status, created_at,
                                                  updated_at)
                        values (?, ?, 'x', 'TRADER', 'ACTIVE', ?, ?)""").params(id, id + "@t063.invalid", now, now).update();
        sql.sql("insert into user_profile (user_id, display_name, created_at, updated_at) values (?, 'Fan', ?, ?)")
                .params(id, now, now)
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
