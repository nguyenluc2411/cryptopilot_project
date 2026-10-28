package com.cryptopilot.community.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cryptopilot.auth.config.JwtConfig;
import com.cryptopilot.community.model.AuthorStanding;
import com.cryptopilot.community.repository.PostFeedRepository;
import com.cryptopilot.support.InAppNotifications;
import com.cryptopilot.support.TestcontainersConfig;
import com.cryptopilot.user.model.enums.UserRole;
import com.jayway.jsonpath.JsonPath;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
 * What SCR-24 shows as CoinMarketCap Community does (D-99): the author's Bullish or Bearish stance, the readers' votes,
 * the For You tab, how an author counts beside their name, Top Influencers and the Trending coins.
 *
 * <p>Rule: D-99.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class CommunityRankingApiTest {

    private static final String POSTS = "/api/v1/posts";
    private static final String MEMBERS = "/api/v1/community/members/";
    private static final String INFLUENCERS = "/api/v1/community/influencers";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient sql;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private PostFeedRepository feeds;

    private final List<UUID> accounts = new ArrayList<>();

    private final List<UUID> coins = new ArrayList<>();

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
            sql.sql("delete from post_coin where post_id in (select post_id from forum_post where author_id = ?)")
                    .param(account)
                    .update();
            sql.sql("delete from forum_post where author_id = ?").param(account).update();
        }
        for (UUID coin : coins) {
            sql.sql("delete from coin where coin_id = ?").param(coin).update();
        }
        for (UUID account : accounts) {
            sql.sql("delete from user_token where user_id = ?").param(account).update();
            sql.sql("delete from user_account where user_id = ?").param(account).update();
        }
    }

    @Test
    void D99_theAuthorMarksAStance_andReadersVoteOnce_aNewVoteReplacingTheOld() throws Exception {
        UUID alice = account(UserRole.TRADER, "Alice");
        UUID bob = account(UserRole.TRADER, "Bob");
        UUID carol = account(UserRole.TRADER, "Carol");
        String id = JsonPath.read(
                as(alice, post(POSTS), "{\"title\": \"Up only\", \"content\": \"text\", \"sentiment\": \"BULLISH\"}")
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.sentiment").value("BULLISH"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.id");

        as(bob, put(POSTS + "/" + id + "/vote"), "{\"sentiment\": \"BEARISH\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.myVote").value("BEARISH"))
                .andExpect(jsonPath("$.bearishCount").value(1));
        as(carol, put(POSTS + "/" + id + "/vote"), "{\"sentiment\": \"BEARISH\"}")
                .andExpect(jsonPath("$.bearishCount").value(2));
        as(bob, put(POSTS + "/" + id + "/vote"), "{\"sentiment\": \"BULLISH\"}")
                .andExpect(jsonPath("$.bullishCount").value(1))
                .andExpect(jsonPath("$.bearishCount").value(1));
        as(bob, put(POSTS + "/" + id + "/vote"), "{\"sentiment\": \"BULLISH\"}")
                .andExpect(jsonPath("$.bullishCount").value(1));
        as(bob, put(POSTS + "/" + id + "/vote"), "{}").andExpect(status().isBadRequest());

        as(bob, get(POSTS + "/" + id))
                .andExpect(jsonPath("$.myVote").value("BULLISH"))
                .andExpect(jsonPath("$.bullishCount").value(1))
                .andExpect(jsonPath("$.bearishCount").value(1));
        as(carol, get(POSTS + "?author=" + alice))
                .andExpect(jsonPath("$.items[0].sentiment").value("BULLISH"))
                .andExpect(jsonPath("$.items[0].myVote").value("BEARISH"));

        as(bob, delete(POSTS + "/" + id + "/vote"))
                .andExpect(jsonPath("$.myVote").doesNotExist())
                .andExpect(jsonPath("$.bullishCount").value(0));
        mvc.perform(put(POSTS + "/" + id + "/vote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sentiment\": \"BULLISH\"}"))
                .andExpect(status().isUnauthorized());

        // A stance taken off on an edit is gone.
        as(alice, put(POSTS + "/" + id), "{\"title\": \"Up only\", \"content\": \"text\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sentiment").doesNotExist());
    }

    @Test
    void D99_aPostShowsHowItsAuthorCounts_andWhetherTheReaderFollowsThem() throws Exception {
        UUID alice = account(UserRole.TRADER, "Alice");
        UUID bob = account(UserRole.TRADER, "Bob");
        String id = newPost(alice, "Alice's post", "text");
        as(bob, post(MEMBERS + alice + "/follow")).andExpect(status().isOk());
        as(bob, post(POSTS + "/" + id + "/like")).andExpect(status().isOk());
        as(bob, post(POSTS + "/" + id + "/comments"), "{\"content\": \"Nice\"}").andExpect(status().isCreated());
        // Alice's own comment does not raise her influence.
        as(alice, post(POSTS + "/" + id + "/comments"), "{\"content\": \"Thanks\"}")
                .andExpect(status().isCreated());

        double expected = new AuthorStanding(1, 1, 1, 1, 0, 0, 0, true).influence();
        as(bob, get(POSTS + "/" + id))
                .andExpect(jsonPath("$.authorStanding.influenceScore").value(expected))
                .andExpect(jsonPath("$.authorStanding.followers").value(1))
                .andExpect(jsonPath("$.authorStanding.followedByMe").value(true));
        mvc.perform(get(POSTS + "?author=" + alice))
                .andExpect(jsonPath("$.items[0].authorStanding.influenceScore").value(expected))
                .andExpect(jsonPath("$.items[0].authorStanding.followedByMe").value(false));
    }

    @Test
    void D99_topInfluencers_rankByInfluenceOrDepth_andLeaveOutABlock() throws Exception {
        UUID alice = account(UserRole.TRADER, "Alice");
        UUID bob = account(UserRole.TRADER, "Bob");
        UUID carol = account(UserRole.TRADER, "Carol");
        // Alice is followed and liked; Bob wrote one long analysis that nobody reacted to.
        String fromAlice = newPost(alice, "Alice's post", "short");
        newPost(bob, "Bob's analysis", "x".repeat(AuthorStanding.DEEP_TEXT_LENGTH));
        as(carol, post(MEMBERS + alice + "/follow")).andExpect(status().isOk());
        as(carol, post(POSTS + "/" + fromAlice + "/like")).andExpect(status().isOk());
        as(alice, post(POSTS), "{\"title\": \"Bearish now\", \"content\": \"text\", \"sentiment\": \"BEARISH\"}")
                .andExpect(status().isCreated());

        List<String> byInfluence = ranks(as(carol, get(INFLUENCERS + "?limit=20")));
        assertThat(byInfluence).containsSubsequence(alice.toString(), bob.toString());
        as(carol, get(INFLUENCERS + "?limit=20"))
                .andExpect(jsonPath("$[?(@.userId == '" + alice + "')].followedByMe")
                        .value(true))
                .andExpect(jsonPath("$[?(@.userId == '" + alice + "')].bearishPosts")
                        .value(1))
                .andExpect(jsonPath("$[?(@.userId == '" + alice + "')].displayName")
                        .value("Alice"));
        List<String> byDepth = ranks(mvc.perform(get(INFLUENCERS + "?rank=DEPTH&limit=20")));
        assertThat(byDepth).containsSubsequence(bob.toString(), alice.toString());

        as(bob, post(MEMBERS + carol + "/block")).andExpect(status().isOk());
        as(carol, get(INFLUENCERS + "?limit=20"))
                .andExpect(jsonPath("$[?(@.userId == '" + bob + "')]").isEmpty());
        as(alice, get(INFLUENCERS + "?limit=20"))
                .andExpect(jsonPath("$[?(@.userId == '" + alice + "')].me").value(true));
    }

    @Test
    void D99_forYou_putsTheMostDiscussedRecentPostFirst_andOnlyThisWeeks() throws Exception {
        UUID alice = account(UserRole.TRADER, "Alice");
        UUID bob = account(UserRole.TRADER, "Bob");
        String quiet = newPost(alice, "Quiet post", "text");
        String busy = newPost(alice, "Busy post", "text");
        String old = newPost(alice, "Old busy post", "text");
        sql.sql("update forum_post set created_at = ? where post_id = ?")
                .params(Timestamp.from(Instant.now().minus(Duration.ofDays(8))), UUID.fromString(old))
                .update();
        // The quiet post is the newest: without what the busy one drew, it would come first.
        sql.sql("update forum_post set created_at = ? where post_id = ?")
                .params(Timestamp.from(Instant.now().minus(Duration.ofHours(1))), UUID.fromString(busy))
                .update();
        as(bob, post(POSTS + "/" + busy + "/like")).andExpect(status().isOk());
        as(bob, post(POSTS + "/" + busy + "/comments"), "{\"content\": \"Hi\"}").andExpect(status().isCreated());
        as(bob, post(POSTS + "/" + old + "/like")).andExpect(status().isOk());

        List<String> ids = JsonPath.read(
                mvc.perform(get(POSTS + "?tab=TRENDING&author=" + alice))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.items[*].id");
        assertThat(ids).containsExactly(busy, quiet);
    }

    @Test
    void D99_trendingCoins_isPublic_andAnswersAtMostTheLimit() throws Exception {
        mvc.perform(get(POSTS + "/trending-coins?limit=3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.lessThanOrEqualTo(3)));
    }

    @Test
    void D125_trendingCoins_countTheWeekAndEveryPost_mostDiscussedOfTheDayFirst() throws Exception {
        UUID alice = account(UserRole.TRADER, "Alice");
        Instant now = Instant.now();
        String suffix = Long.toString(System.nanoTime() % 1_000_000, 36).toUpperCase(Locale.ROOT);
        UUID busy = coin("TA" + suffix);
        UUID calm = coin("TB" + suffix);
        UUID stale = coin("TC" + suffix);
        // Busy: two posts today, one older than the week. Calm: one today, one three days old, three older ones.
        taggedPost(alice, busy, now.minus(Duration.ofHours(2)));
        taggedPost(alice, busy, now.minus(Duration.ofHours(3)));
        taggedPost(alice, busy, now.minus(Duration.ofDays(10)));
        taggedPost(alice, calm, now.minus(Duration.ofHours(1)));
        taggedPost(alice, calm, now.minus(Duration.ofDays(3)));
        for (int days = 8; days <= 10; days++) {
            taggedPost(alice, calm, now.minus(Duration.ofDays(days)));
        }
        // Stale was talked about, but not this week.
        taggedPost(alice, stale, now.minus(Duration.ofDays(9)));

        List<PostFeedRepository.TrendingCoin> trending = feeds.trendingCoins(now, 1000).stream()
                .filter(coin -> List.of(busy, calm, stale).contains(coin.coinId()))
                .toList();
        assertThat(trending)
                .extracting(
                        PostFeedRepository.TrendingCoin::coinId,
                        PostFeedRepository.TrendingCoin::postsLastDay,
                        PostFeedRepository.TrendingCoin::postsLastWeek,
                        PostFeedRepository.TrendingCoin::totalPosts)
                .containsExactly(tuple(busy, 2L, 2L, 3L), tuple(calm, 1L, 2L, 5L));

        mvc.perform(get(POSTS + "/trending-coins?limit=20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.symbol == 'TA" + suffix + "')].postsLastDay")
                        .value(2))
                .andExpect(jsonPath("$[?(@.symbol == 'TA" + suffix + "')].postsLastWeek")
                        .value(2))
                .andExpect(jsonPath("$[?(@.symbol == 'TA" + suffix + "')].totalPosts")
                        .value(3))
                .andExpect(jsonPath("$[?(@.symbol == 'TB" + suffix + "')].postsLastWeek")
                        .value(2))
                .andExpect(jsonPath("$[?(@.symbol == 'TB" + suffix + "')].totalPosts")
                        .value(5))
                .andExpect(jsonPath("$[?(@.symbol == 'TC" + suffix + "')]").isEmpty());
    }

    private List<String> ranks(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$[*].userId");
    }

    private String newPost(UUID author, String title, String content) throws Exception {
        return JsonPath.read(
                as(author, post(POSTS), "{\"title\": \"" + title + "\", \"content\": \"" + content + "\"}")
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.id");
    }

    private UUID coin(String symbol) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        sql.sql("insert into coin (coin_id, symbol, coin_name, created_at, updated_at) values (?, ?, ?, ?, ?)")
                .params(id, symbol, symbol, now, now)
                .update();
        coins.add(id);
        return id;
    }

    private void taggedPost(UUID author, UUID coin, Instant at) {
        UUID id = UUID.randomUUID();
        sql.sql("""
                        insert into forum_post (post_id, author_id, title, content, post_type, post_status,
                                                is_official, created_at, updated_at)
                        values (?, ?, 'Tagged', 'text', 'TEXT', 'PUBLISHED', false, ?, ?)""").params(id, author, Timestamp.from(at), Timestamp.from(at)).update();
        sql.sql("insert into post_coin (post_id, coin_id) values (?, ?)")
                .params(id, coin)
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

    private UUID account(UserRole role, String displayName) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        sql.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status, created_at,
                                                  updated_at)
                        values (?, ?, 'x', ?, 'ACTIVE', ?, ?)""").params(id, id + "@ranking.invalid", role.name(), now, now).update();
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
