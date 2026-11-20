package com.cryptopilot.community.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cryptopilot.auth.config.JwtConfig;
import com.cryptopilot.market.MarketTestData;
import com.cryptopilot.support.InAppNotifications;
import com.cryptopilot.support.TestcontainersConfig;
import com.cryptopilot.user.model.enums.UserRole;
import com.jayway.jsonpath.JsonPath;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
 * The parts of a member page beyond the posts, as on CoinMarketCap Community (D-99): the introduction, link, banner and
 * scores of the page, Token Mentions, and the Comments and Reactions tabs.
 *
 * <p>The invariants, each asserted below over every path that could break it:
 *
 * <ul>
 *   <li>Only published posts and published comments count; a deleted post, its comments and the reactions to it are
 *       gone from every list.
 *   <li>A reader never sees, through the page, a post of a member in a block with them (D-125), and a member who blocks
 *       the reader has no page for them at all (MSG41).
 *   <li>Token Mentions counts the member's own posts and stances only; the shares add up over all their coin tags.
 *   <li>Reactions are likes and Bullish or Bearish votes, never saves or views; a post reacted to twice is listed once,
 *       at its newest reaction.
 * </ul>
 *
 * <p>Rule: BR-47; D-99, D-125.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class MemberPageApiTest {

    private static final String POSTS = "/api/v1/posts";
    private static final String MEMBERS = "/api/v1/community/members/";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient sql;

    @Autowired
    private JwtEncoder jwtEncoder;

    private final List<UUID> accounts = new ArrayList<>();
    private final List<UUID> pairs = new ArrayList<>();
    private MarketTestData market;
    private String coinA;
    private String coinB;
    private int written;

    @BeforeEach
    void insertCoins() {
        market = new MarketTestData(sql, Instant.now());
        String run = Long.toString(System.nanoTime() % 1_000_000_000L);
        coinA = "MP" + run + "A";
        coinB = "MP" + run + "B";
        pairs.add(market.pair(coinA + "USDT", true, false, "TRADING", null, 0));
        pairs.add(market.pair(coinB + "USDT", true, false, "TRADING", null, 1));
    }

    @AfterEach
    void removeRows() {
        InAppNotifications.remove(sql, accounts);
        for (UUID account : accounts) {
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
            sql.sql("delete from user_account where user_id = ?").param(account).update();
        }
        pairs.forEach(market::remove);
    }

    @Test
    void D99_aMembersPage_showsWhatTheySaidOfThemselves_andTheirScores() throws Exception {
        UUID alice = trader("Alice");
        sql.sql("update user_profile set bio = ?, website_url = ?, banner_url = ? where user_id = ?")
                .params("Swing trader", "https://x.com/alice", "https://example.invalid/banner.png", alice)
                .update();

        mvc.perform(get(MEMBERS + alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bio").value("Swing trader"))
                .andExpect(jsonPath("$.websiteUrl").value("https://x.com/alice"))
                .andExpect(jsonPath("$.bannerUrl").value("https://example.invalid/banner.png"))
                .andExpect(jsonPath("$.influenceScore").value(0.0))
                .andExpect(jsonPath("$.depthScore").value(0.0));

        newPost(alice, "Alice on the range", null);
        double influence = JsonPath.read(
                mvc.perform(get(MEMBERS + alice)).andReturn().getResponse().getContentAsString(), "$.influenceScore");
        assertThat(influence).as("a published post counts for influence").isPositive();
    }

    @Test
    void D99_tokenMentions_countTheMembersPublishedPosts_andTheirOwnLean() throws Exception {
        UUID alice = trader("Alice");
        UUID bob = trader("Bob");
        newPost(alice, "Both coins up", "BULLISH", coinA, coinB);
        newPost(alice, "Coin A down", "BEARISH", coinA);
        newPost(alice, "Coin A sideways", null, coinA);
        String deleted = newPost(alice, "Coin B deleted", "BULLISH", coinB);
        as(alice, delete(POSTS + "/" + deleted)).andExpect(status().isNoContent());
        // Another member's post on the coin is theirs, not Alice's.
        newPost(bob, "Bob on coin B", "BEARISH", coinB);

        mvc.perform(get(MEMBERS + alice + "/token-mentions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].symbol").value(coinA))
                .andExpect(jsonPath("$[0].posts").value(3))
                .andExpect(jsonPath("$[0].sharePercent").value(75.0))
                .andExpect(jsonPath("$[0].bullishPosts").value(1))
                .andExpect(jsonPath("$[0].bearishPosts").value(1))
                .andExpect(jsonPath("$[0].lastPostAt").exists())
                .andExpect(jsonPath("$[1].symbol").value(coinB))
                .andExpect(jsonPath("$[1].posts").value(1))
                .andExpect(jsonPath("$[1].sharePercent").value(25.0))
                .andExpect(jsonPath("$[1].bullishPosts").value(1))
                .andExpect(jsonPath("$[1].bearishPosts").value(0));
        mvc.perform(get(MEMBERS + alice + "/token-mentions?limit=1"))
                .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get(MEMBERS + alice + "/token-mentions?limit=0"))
                .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get(MEMBERS + bob + "/token-mentions"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].sharePercent").value(100.0));
    }

    @Test
    void D99_theCommentsTab_listsPublishedComments_newestFirst_withTheirPost() throws Exception {
        UUID alice = trader("Alice");
        UUID bob = trader("Bob");
        UUID carol = trader("Carol");
        String alicePost = newPost(alice, "Alice on the range", null);
        String carolPost = newPost(carol, "Carol on the range", null);
        String gone = newPost(carol, "Carol deleted", null);
        String first = comment(bob, alicePost, "First from Bob", null);
        comment(bob, alicePost, "A reply from Bob", first);
        comment(bob, carolPost, "Bob on Carol", null);
        comment(bob, gone, "Bob on a deleted post", null);
        as(carol, delete(POSTS + "/" + gone)).andExpect(status().isNoContent());
        as(alice, post("/api/v1/comments/" + first + "/like")).andExpect(status().isOk());

        mvc.perform(get(MEMBERS + bob + "/comments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items[0].content").value("Bob on Carol"))
                .andExpect(jsonPath("$.items[0].postId").value(carolPost))
                .andExpect(jsonPath("$.items[0].postTitle").value("Carol on the range"))
                .andExpect(jsonPath("$.items[0].postExcerpt").value("text"))
                .andExpect(jsonPath("$.items[1].content").value("A reply from Bob"))
                .andExpect(jsonPath("$.items[1].reply").value(true))
                .andExpect(jsonPath("$.items[2].commentId").value(first))
                .andExpect(jsonPath("$.items[2].reply").value(false))
                .andExpect(jsonPath("$.items[2].likes").value(1));

        // Alice blocks Carol: Bob's comment on Carol's post is no longer shown to Alice, and still is to anyone else.
        as(alice, post(MEMBERS + carol + "/block")).andExpect(status().isOk());
        as(alice, get(MEMBERS + bob + "/comments"))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].content").value("A reply from Bob"));
        mvc.perform(get(MEMBERS + bob + "/comments?page=2&pageSize=2"))
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.items.length()").value(1));
    }

    @Test
    void D99_theReactionsTab_listsLikedAndVotedPosts_onceEach_newestReactionFirst() throws Exception {
        UUID alice = trader("Alice");
        UUID bob = trader("Bob");
        UUID carol = trader("Carol");
        String liked = newPost(alice, "Liked by Bob", null);
        String voted = newPost(alice, "Voted by Bob", null);
        String saved = newPost(alice, "Saved by Bob", null);
        String gone = newPost(alice, "Deleted after Bob liked it", null);
        String fromCarol = newPost(carol, "Carol liked by Bob", null);
        as(bob, post(POSTS + "/" + liked + "/like")).andExpect(status().isOk());
        reactedEarlier(bob, liked);
        as(bob, put(POSTS + "/" + voted + "/vote"), "{\"sentiment\": \"BULLISH\"}")
                .andExpect(status().isOk());
        reactedEarlier(bob, voted);
        as(bob, post(POSTS + "/" + fromCarol + "/like")).andExpect(status().isOk());
        as(bob, post(POSTS + "/" + saved + "/save")).andExpect(status().isOk());
        as(bob, post(POSTS + "/" + gone + "/like")).andExpect(status().isOk());
        as(alice, delete(POSTS + "/" + gone)).andExpect(status().isNoContent());
        // A like after the vote makes the voted post Bob's newest reaction to it; it is still listed once.
        as(bob, post(POSTS + "/" + voted + "/like")).andExpect(status().isOk());

        mvc.perform(get(MEMBERS + bob + "/reactions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items[0].id").value(voted))
                .andExpect(jsonPath("$.items[1].id").value(fromCarol))
                .andExpect(jsonPath("$.items[2].id").value(liked));

        as(alice, post(MEMBERS + carol + "/block")).andExpect(status().isOk());
        as(alice, get(MEMBERS + bob + "/reactions"))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].id").value(voted))
                .andExpect(jsonPath("$.items[1].id").value(liked));
    }

    @Test
    void D125_aMemberWhoBlocksTheReader_hasNoMentionsCommentsOrReactionsForThem() throws Exception {
        UUID alice = trader("Alice");
        UUID bob = trader("Bob");
        as(alice, post(MEMBERS + bob + "/block")).andExpect(status().isOk());

        for (String tab : new String[] {"/token-mentions", "/comments", "/reactions"}) {
            as(bob, get(MEMBERS + alice + tab)).andExpect(status().isNotFound());
            as(alice, get(MEMBERS + alice + tab)).andExpect(status().isOk());
        }
        mvc.perform(get(MEMBERS + UUID.randomUUID() + "/comments")).andExpect(status().isNotFound());
    }

    private String newPost(UUID author, String title, String sentiment, String... coins) throws Exception {
        StringBuilder body = new StringBuilder("{\"title\": \"" + title + "\", \"content\": \"text\"");
        if (sentiment != null) {
            body.append(", \"sentiment\": \"").append(sentiment).append('"');
        }
        body.append(", \"coins\": [");
        for (int i = 0; i < coins.length; i++) {
            body.append(i == 0 ? "" : ", ").append('"').append(coins[i]).append('"');
        }
        body.append("]}");
        return JsonPath.read(
                as(author, post(POSTS), body.toString())
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.id");
    }

    private String comment(UUID author, String postId, String content, String parent) throws Exception {
        String body = "{\"content\": \"" + content + "\""
                + (parent == null ? "" : ", \"parentCommentId\": \"" + parent + "\"") + "}";
        String created = as(author, post(POSTS + "/" + postId + "/comments"), body)
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        // Comments written in one test are a second apart, so the newest-first order is not left to the clock's grain.
        String id = JsonPath.read(created, "$.id");
        sql.sql("update forum_comment set created_at = created_at + ? * interval '1 second' where comment_id = ?")
                .params(++written, UUID.fromString(id))
                .update();
        return id;
    }

    // Moves a member's reactions to a post an hour back, so the order of the tab does not hang on the clock's grain.
    private void reactedEarlier(UUID member, String postId) {
        sql.sql("update engagement set created_at = created_at - interval '1 hour' where user_id = ? and post_id = ?")
                .params(member, UUID.fromString(postId))
                .update();
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

    private UUID trader(String displayName) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        sql.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status, created_at,
                                                  updated_at)
                        values (?, ?, 'x', 'TRADER', 'ACTIVE', ?, ?)""").params(id, id + "@mp99.invalid", now, now).update();
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
