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
import java.time.Duration;
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
 * The community posts end to end: publishing and editing, the coin tags of listed pairs, the feed tabs and filters,
 * who sees a hidden or deleted post, and the announcements pinned above the feed.
 *
 * <p>Every test writes its own accounts, pairs and posts and {@link #removeRows()} deletes them. The shared feed tabs
 * are read filtered by a coin only this test's pairs trade, so posts other tests leave behind cannot change a result.
 *
 * <p>Rule: UC-26, UC-27, UC-29, UC-32, UC-44; BR-07, BR-47, BR-61; SRS 3.7.1, 3.7.2; CR-04.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class PostApiTest {

    private static final String POSTS = "/api/v1/posts";
    private static final String ANNOUNCEMENTS = "/api/v1/admin/announcements";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient sql;

    @Autowired
    private JwtEncoder jwtEncoder;

    private final List<UUID> accounts = new ArrayList<>();
    private final List<UUID> pairs = new ArrayList<>();

    /** Two coins of listed pairs and one of a pair enabled on no market. */
    private String coinA;

    private String coinB;
    private String unlistedCoin;

    @BeforeEach
    void insertPairs() {
        MarketTestData market = new MarketTestData(sql, Instant.now());
        String run = Long.toString(System.nanoTime() % 1_000_000_000L);
        coinA = "CM" + run + "A";
        coinB = "CM" + run + "B";
        unlistedCoin = "CM" + run + "X";
        pairs.add(market.pair(coinA + "USDT", true, false, "TRADING", null, 0));
        pairs.add(market.pair(coinB + "USDT", false, true, null, "TRADING", 1));
        pairs.add(market.pair(unlistedCoin + "USDT", false, false, null, null, 2));
    }

    @AfterEach
    void removeRows() {
        InAppNotifications.remove(sql, accounts);
        for (UUID account : accounts) {
            sql.sql("delete from audit_log where user_id = ?").param(account).update();
            sql.sql("delete from engagement where user_id = ?").param(account).update();
            sql.sql("delete from forum_comment where author_id = ?")
                    .param(account)
                    .update();
            sql.sql("delete from forum_post where author_id = ?").param(account).update();
            sql.sql("delete from user_account where user_id = ?").param(account).update();
        }
        for (UUID pair : pairs) {
            List<UUID> coins = sql.sql("select base_coin_id from crypto_pair where pair_id = ?"
                            + " union select quote_coin_id from crypto_pair where pair_id = ?")
                    .params(pair, pair)
                    .query(UUID.class)
                    .list();
            sql.sql("delete from crypto_pair where pair_id = ?").param(pair).update();
            for (UUID coin : coins) {
                sql.sql("""
                                delete from coin where coin_id = ?
                                   and not exists (select 1 from crypto_pair
                                                    where base_coin_id = coin.coin_id or quote_coin_id = coin.coin_id)""").param(coin).update();
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Publishing and editing (UC-27, UC-29, SRS 3.7.2)
    // ------------------------------------------------------------------------------------------

    @Test
    void UC27_aTraderPublishesAPost_andReadsItBack() throws Exception {
        UUID author = trader("Alice");

        String id = id(as(author, post(POSTS), body("  BTC breakout  ", "Watching the range.", "SPOT", coinB, coinA))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("BTC breakout"))
                .andExpect(jsonPath("$.content").value("Watching the range."))
                .andExpect(jsonPath("$.postType").value("TEXT"))
                .andExpect(jsonPath("$.marketType").value("SPOT"))
                .andExpect(jsonPath("$.official").value(false))
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.coins[0]").value(coinA))
                .andExpect(jsonPath("$.coins[1]").value(coinB))
                .andExpect(jsonPath("$.author.userId").value(author.toString()))
                .andExpect(jsonPath("$.author.displayName").value("Alice"))
                .andExpect(jsonPath("$.likeCount").value(0))
                .andExpect(jsonPath("$.commentCount").value(0))
                .andExpect(jsonPath("$.mine").value(true)));

        UUID reader = trader("Bob");
        as(reader, get(POSTS + "/" + id), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("BTC breakout"))
                .andExpect(jsonPath("$.mine").value(false));
        assertThat(sql.sql("select count(*) from post_coin where post_id = ?")
                        .param(UUID.fromString(id))
                        .query(Long.class)
                        .single())
                .isEqualTo(2);
    }

    @Test
    void UC27_aCoinTag_isCaseInsensitive_andCountedOnce() throws Exception {
        UUID author = trader("Alice");
        as(author, post(POSTS), body("Tagged twice", "text", null, coinA.toLowerCase(), coinA))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.coins.length()").value(1))
                .andExpect(jsonPath("$.coins[0]").value(coinA))
                .andExpect(jsonPath("$.marketType").doesNotExist());
    }

    @Test
    void UC27_invalidFields_areRefusedWithMsg01() throws Exception {
        UUID author = trader("Alice");

        as(author, post(POSTS), body("   abcd   ", " ", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messageCode").value("MSG01"))
                .andExpect(jsonPath("$.errors.title").exists())
                .andExpect(jsonPath("$.errors.content").exists());
        as(author, post(POSTS), body("x".repeat(151), "y".repeat(10_001), null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.title").exists())
                .andExpect(jsonPath("$.errors.content").exists());
        as(author, post(POSTS), body("Six coins", "text", null, "A1", "A2", "A3", "A4", "A5", "A6"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.coins").exists());
        as(author, post(POSTS), body("Unlisted coin", "text", null, coinA, unlistedCoin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messageCode").value("MSG01"))
                .andExpect(jsonPath("$.errors.coins").value("MSG01"));
        as(author, post(POSTS), "{\"title\": \"Market\", \"content\": \"text\", \"marketType\": \"MARGIN\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messageCode").value("MSG01"));

        assertThat(sql.sql("select count(*) from forum_post where author_id = ?")
                        .param(author)
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void UC29_theAuthorEditsTheirPost_andTheTagsAreReplaced() throws Exception {
        UUID author = trader("Alice");
        String id = id(as(author, post(POSTS), body("First title", "First text", "SPOT", coinA)));

        as(author, put(POSTS + "/" + id), body("Second title", "Second text", "FUTURES", coinB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Second title"))
                .andExpect(jsonPath("$.content").value("Second text"))
                .andExpect(jsonPath("$.marketType").value("FUTURES"))
                .andExpect(jsonPath("$.coins.length()").value(1))
                .andExpect(jsonPath("$.coins[0]").value(coinB));
        as(author, put(POSTS + "/" + id), body("Third title", "Third text", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coins.length()").value(0));
    }

    @Test
    void UC29_anotherTrader_cannotEditOrDeleteThePost() throws Exception {
        UUID author = trader("Alice");
        UUID other = trader("Mallory");
        String id = id(as(author, post(POSTS), body("Alice's post", "text", null)));

        as(other, put(POSTS + "/" + id), body("Taken over", "text", null))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.messageCode").value("MSG41"));
        as(other, delete(POSTS + "/" + id), null).andExpect(status().isNotFound());
        as(author, get(POSTS + "/" + id), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Alice's post"))
                .andExpect(jsonPath("$.status").value("PUBLISHED"));
    }

    // ------------------------------------------------------------------------------------------
    // Who sees what (BR-47)
    // ------------------------------------------------------------------------------------------

    @Test
    void BR47_aDeletedPost_isGoneForEveryone_andStaysInTheTable() throws Exception {
        UUID author = trader("Alice");
        UUID reader = trader("Bob");
        String id = id(as(author, post(POSTS), body("To be deleted", "text", null, coinA)));

        as(author, delete(POSTS + "/" + id), null).andExpect(status().isNoContent());

        as(author, get(POSTS + "/" + id), null).andExpect(status().isNotFound());
        as(reader, get(POSTS + "/" + id), null).andExpect(status().isNotFound());
        as(author, put(POSTS + "/" + id), body("Back again", "text", null)).andExpect(status().isNotFound());
        as(author, delete(POSTS + "/" + id), null).andExpect(status().isNotFound());
        as(reader, get(POSTS + "?coin=" + coinA), null)
                .andExpect(jsonPath("$.total").value(0));
        as(author, get(POSTS + "?tab=MINE"), null).andExpect(jsonPath("$.total").value(0));
        assertThat(storedStatus(id)).isEqualTo("DELETED");
    }

    @Test
    void BR47_aHiddenPost_isSeenByItsAuthorOnly() throws Exception {
        UUID author = trader("Alice");
        UUID reader = trader("Bob");
        String id = id(as(author, post(POSTS), body("To be hidden", "text", null, coinA)));
        sql.sql("update forum_post set post_status = 'HIDDEN' where post_id = ?")
                .param(UUID.fromString(id))
                .update();

        as(author, get(POSTS + "/" + id), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("HIDDEN"));
        as(reader, get(POSTS + "/" + id), null).andExpect(status().isNotFound());
        as(reader, get(POSTS + "?coin=" + coinA), null)
                .andExpect(jsonPath("$.total").value(0));
        as(author, get(POSTS + "?coin=" + coinA), null)
                .andExpect(jsonPath("$.total").value(0));
        as(author, get(POSTS + "?tab=MINE"), null)
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].status").value("HIDDEN"));
        // The author may delete it, not edit it.
        as(author, put(POSTS + "/" + id), body("Still hidden", "text", null))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.messageCode").value("MSG41"));
    }

    @Test
    void BR47_aPostModerationHid_cannotBeEditedByItsAuthor_andKeepsItsText() throws Exception {
        UUID author = trader("Alice");
        String id = hiddenByModeration(author, "Before review", "Original text");

        as(author, put(POSTS + "/" + id), body("Cleaned up", "Other text", null))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.messageCode").value("MSG41"));

        as(author, get(POSTS + "/" + id), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("HIDDEN"))
                .andExpect(jsonPath("$.title").value("Before review"))
                .andExpect(jsonPath("$.content").value("Original text"));
    }

    @Test
    void BR47_aPostModerationHid_canStillBeDeletedByItsAuthor() throws Exception {
        UUID author = trader("Alice");
        String id = hiddenByModeration(author, "Before review", "Original text");

        as(author, delete(POSTS + "/" + id), null).andExpect(status().isNoContent());

        assertThat(storedStatus(id)).isEqualTo("DELETED");
    }

    // ------------------------------------------------------------------------------------------
    // The feed (UC-26, SRS 3.7.1)
    // ------------------------------------------------------------------------------------------

    @Test
    void UC26_latest_isNewestFirst_andFiltersByCoinAndMarket() throws Exception {
        UUID author = trader("Alice");
        UUID reader = trader("Bob");
        String oldest = id(as(author, post(POSTS), body("Oldest post", "text", "SPOT", coinA)));
        String middle = id(as(author, post(POSTS), body("Middle post", "text", "FUTURES", coinA, coinB)));
        String newest = id(as(author, post(POSTS), body("Newest post", "text", null, coinB)));
        createdAgo(oldest, Duration.ofHours(3));
        createdAgo(middle, Duration.ofHours(2));
        createdAgo(newest, Duration.ofHours(1));

        as(reader, get(POSTS + "?coin=" + coinA), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].id").value(middle))
                .andExpect(jsonPath("$.items[1].id").value(oldest))
                .andExpect(jsonPath("$.items[0].excerpt").value("text"))
                .andExpect(jsonPath("$.items[0].author.displayName").value("Alice"));
        as(reader, get(POSTS + "?coin=" + coinA.toLowerCase() + "&market=SPOT"), null)
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(oldest));
        as(reader, get(POSTS + "?coin=" + coinB + "&pageSize=1&page=2"), null)
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.pageSize").value(1))
                .andExpect(jsonPath("$.items[0].id").value(middle));
        as(reader, get(POSTS + "?coin=NOSUCHCOIN"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void UC26_aLongText_isCutToTheExcerptInTheFeed() throws Exception {
        UUID author = trader("Alice");
        as(author, post(POSTS), body("Long post", "a".repeat(400), null, coinA));

        as(author, get(POSTS + "?coin=" + coinA), null)
                .andExpect(jsonPath("$.items[0].excerpt").value("a".repeat(300)))
                .andExpect(jsonPath("$.items[0].content").doesNotExist());
    }

    @Test
    void UC26_mostLiked_ranksThePostsOfTheLastSevenDaysByLikes() throws Exception {
        UUID author = trader("Alice");
        UUID fan1 = trader("Fan one");
        UUID fan2 = trader("Fan two");
        String once = id(as(author, post(POSTS), body("Liked once", "text", null, coinA)));
        String twice = id(as(author, post(POSTS), body("Liked twice", "text", null, coinA)));
        String old = id(as(author, post(POSTS), body("Liked but old", "text", null, coinA)));
        createdAgo(old, Duration.ofDays(8));
        engage(fan1, once, "LIKE");
        engage(fan1, twice, "LIKE");
        engage(fan2, twice, "LIKE");
        engage(fan1, old, "LIKE");
        engage(fan2, old, "LIKE");
        engage(fan1, once, "SAVE");

        as(fan1, get(POSTS + "?tab=MOST_LIKED&coin=" + coinA), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].id").value(twice))
                .andExpect(jsonPath("$.items[0].likeCount").value(2))
                .andExpect(jsonPath("$.items[0].likedByMe").value(true))
                .andExpect(jsonPath("$.items[1].id").value(once))
                .andExpect(jsonPath("$.items[1].likeCount").value(1))
                .andExpect(jsonPath("$.items[1].savedByMe").value(true));
        as(fan2, get(POSTS + "?tab=MOST_LIKED&coin=" + coinA), null)
                .andExpect(jsonPath("$.items[1].likedByMe").value(false))
                .andExpect(jsonPath("$.items[1].savedByMe").value(false));
    }

    @Test
    void UC32_theSavedTab_listsTheCallersSavedPosts_mostRecentlySavedFirst() throws Exception {
        UUID author = trader("Alice");
        UUID reader = trader("Bob");
        String first = id(as(author, post(POSTS), body("Saved first", "text", null)));
        String second = id(as(author, post(POSTS), body("Saved second", "text", null)));
        String deleted = id(as(author, post(POSTS), body("Saved then deleted", "text", null)));
        engage(reader, first, "SAVE", Instant.now().minusSeconds(60));
        engage(reader, second, "SAVE", Instant.now());
        engage(reader, deleted, "SAVE", Instant.now());
        as(author, delete(POSTS + "/" + deleted), null).andExpect(status().isNoContent());

        as(reader, get(POSTS + "?tab=SAVED"), null)
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].id").value(second))
                .andExpect(jsonPath("$.items[1].id").value(first));
        as(author, get(POSTS + "?tab=SAVED"), null)
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void UC26_theCommentCount_countsPublishedCommentsOnly() throws Exception {
        UUID author = trader("Alice");
        UUID reader = trader("Bob");
        String id = id(as(author, post(POSTS), body("Discussed", "text", null)));
        comment(reader, id, "PUBLISHED");
        comment(reader, id, "PUBLISHED");
        comment(reader, id, "DELETED");

        as(reader, get(POSTS + "/" + id), null)
                .andExpect(jsonPath("$.commentCount").value(2));
    }

    @Test
    void CR04_aPageOutsideTheBounds_isRefusedWithMsg15() throws Exception {
        UUID reader = trader("Bob");
        as(reader, get(POSTS + "?pageSize=101"), null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.pageSize").value("MSG15"));
        as(reader, get(POSTS + "?tab=POPULAR"), null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messageCode").value("MSG01"));
    }

    // ------------------------------------------------------------------------------------------
    // Announcements (UC-44, BR-61)
    // ------------------------------------------------------------------------------------------

    @Test
    void BR61_anAnnouncement_isPinned_andLeftOutOfTheFeed() throws Exception {
        UUID admin = account(UserRole.ADMIN, "Admin");
        UUID reader = trader("Bob");

        String id = id(as(admin, UserRole.ADMIN, post(ANNOUNCEMENTS), body("Maintenance tonight", "text", null, coinA))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.official").value(true))
                .andExpect(jsonPath("$.author.displayName").value("Admin")));

        as(reader, get(POSTS + "/pinned"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.id == '" + id + "')].official").value(true));
        as(reader, get(POSTS + "?coin=" + coinA), null)
                .andExpect(jsonPath("$.total").value(0));
        as(reader, get(POSTS + "/" + id), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.official").value(true));
        // A Trader can neither change it through the posts endpoints nor reach the console.
        as(reader, put(POSTS + "/" + id), body("Not mine", "text", null)).andExpect(status().isNotFound());

        as(admin, UserRole.ADMIN, put(ANNOUNCEMENTS + "/" + id), body("Maintenance at 23:00", "text", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Maintenance at 23:00"));
        as(admin, UserRole.ADMIN, get(ANNOUNCEMENTS), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.id == '" + id + "')].title").value("Maintenance at 23:00"));

        as(admin, UserRole.ADMIN, delete(ANNOUNCEMENTS + "/" + id), null).andExpect(status().isNoContent());
        as(reader, get(POSTS + "/" + id), null).andExpect(status().isNotFound());
        as(admin, UserRole.ADMIN, delete(ANNOUNCEMENTS + "/" + id), null).andExpect(status().isNotFound());
    }

    @Test
    void BR61_aTraderPost_isNoAnnouncement() throws Exception {
        UUID admin = account(UserRole.ADMIN, "Admin");
        UUID author = trader("Alice");
        String id = id(as(author, post(POSTS), body("Ordinary post", "text", null)));

        as(admin, UserRole.ADMIN, put(ANNOUNCEMENTS + "/" + id), body("Promoted", "text", null))
                .andExpect(status().isNotFound());
        as(admin, UserRole.ADMIN, delete(ANNOUNCEMENTS + "/" + id), null).andExpect(status().isNotFound());
        assertThat(storedStatus(id)).isEqualTo("PUBLISHED");
    }

    // ------------------------------------------------------------------------------------------
    // Security (SRS 3.1.3)
    // ------------------------------------------------------------------------------------------

    @Test
    void D99_aGuest_readsTheCommunity_butWritesNothing() throws Exception {
        UUID author = trader("Alice");
        String id = id(as(author, post(POSTS), body("Public post", "Readable by anyone", "SPOT", coinA)));
        sql.sql("""
                        insert into forum_comment (comment_id, post_id, author_id, content, comment_status, created_at,
                                                   updated_at)
                        values (?, ?, ?, 'a comment', 'PUBLISHED', now(), now())""").params(UUID.randomUUID(), UUID.fromString(id), author).update();

        mvc.perform(get(POSTS + "?coin=" + coinA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].likedByMe").value(false))
                .andExpect(jsonPath("$.items[0].savedByMe").value(false));
        mvc.perform(get(POSTS + "?tab=MOST_LIKED&coin=" + coinA)).andExpect(status().isOk());
        mvc.perform(get(POSTS + "/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("Readable by anyone"))
                .andExpect(jsonPath("$.mine").value(false));
        mvc.perform(get(POSTS + "/" + id + "/comments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].content").value("a comment"))
                .andExpect(jsonPath("$.items[0].likedByMe").value(false));
        mvc.perform(get(POSTS + "/pinned")).andExpect(status().isOk());

        // The Trader's own tabs and every write need a session.
        mvc.perform(get(POSTS + "?tab=SAVED")).andExpect(status().isUnauthorized());
        mvc.perform(get(POSTS + "?tab=MINE"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.messageCode").value("MSG44"));
        mvc.perform(post(POSTS).contentType(MediaType.APPLICATION_JSON).content(body("Guest post", "text", null)))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(POSTS + "/" + id + "/like")).andExpect(status().isUnauthorized());
        // A hidden post stays hidden from a Guest too.
        sql.sql("update forum_post set post_status = 'HIDDEN' where post_id = ?")
                .param(UUID.fromString(id))
                .update();
        mvc.perform(get(POSTS + "/" + id)).andExpect(status().isNotFound());
    }

    @Test
    void D99_aCoinsDiscussion_countsItsPublishedPosts_forAnyone() throws Exception {
        UUID author = trader("Alice");
        String recent = id(as(author, post(POSTS), body("Recent", "text", null, coinA)));
        String lastWeek = id(as(author, post(POSTS), body("Last week", "text", null, coinA)));
        String old = id(as(author, post(POSTS), body("Long ago", "text", null, coinA, coinB)));
        String deleted = id(as(author, post(POSTS), body("Deleted", "text", null, coinA)));
        createdAgo(recent, Duration.ofHours(2));
        createdAgo(lastWeek, Duration.ofDays(3));
        createdAgo(old, Duration.ofDays(30));
        as(author, delete(POSTS + "/" + deleted), null).andExpect(status().isNoContent());

        mvc.perform(get(POSTS + "/coins/" + coinA.toLowerCase() + "/discussion"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value(coinA))
                .andExpect(jsonPath("$.postsLastDay").value(1))
                .andExpect(jsonPath("$.postsLastWeek").value(2))
                .andExpect(jsonPath("$.totalPosts").value(3))
                .andExpect(jsonPath("$.lastPostAt").exists());
        mvc.perform(get(POSTS + "/coins/" + unlistedCoin + "/discussion"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalPosts").value(0))
                .andExpect(jsonPath("$.lastPostAt").doesNotExist());
        mvc.perform(get(POSTS + "/coins/NOSUCHCOIN/discussion")).andExpect(status().isNotFound());
    }

    @Test
    void D99_anOfficialPost_hasACategory_andThePinnedPostsFilterByIt() throws Exception {
        UUID admin = account(UserRole.ADMIN, "Admin");
        UUID trader = trader("Bob");
        String education = id(as(
                        admin,
                        UserRole.ADMIN,
                        post(ANNOUNCEMENTS),
                        "{\"title\": \"What is funding\", \"content\": \"text\", \"officialCategory\": \"EDUCATION\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.officialCategory").value("EDUCATION")));
        String announcement = id(as(admin, UserRole.ADMIN, post(ANNOUNCEMENTS), body("Maintenance", "text", null))
                .andExpect(jsonPath("$.officialCategory").value("ANNOUNCEMENT")));

        mvc.perform(get(POSTS + "/pinned?category=EDUCATION&pageSize=100"))
                .andExpect(jsonPath("$.items[?(@.id == '" + education + "')]").isNotEmpty())
                .andExpect(
                        jsonPath("$.items[?(@.id == '" + announcement + "')]").isEmpty());
        as(
                        admin,
                        UserRole.ADMIN,
                        put(ANNOUNCEMENTS + "/" + announcement),
                        "{\"title\": \"Release 1.2\", \"content\": \"text\", \"officialCategory\": \"PLATFORM_UPDATE\"}")
                .andExpect(jsonPath("$.officialCategory").value("PLATFORM_UPDATE"));
        // A Trader's post has no category.
        as(
                        trader,
                        post(POSTS),
                        "{\"title\": \"Not official\", \"content\": \"text\", \"officialCategory\": \"EDUCATION\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.officialCategory").value("MSG01"));
        as(trader, post(POSTS), body("Plain post", "text", null))
                .andExpect(jsonPath("$.officialCategory").doesNotExist());
    }

    @Test
    void theConsole_isForAdmins() throws Exception {
        UUID trader = trader("Bob");

        mvc.perform(get(ANNOUNCEMENTS)).andExpect(status().isUnauthorized());
        as(trader, post(ANNOUNCEMENTS), body("Fake news", "text", null)).andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------------------------------

    private ResultActions as(UUID trader, MockHttpServletRequestBuilder request, String body) throws Exception {
        return as(trader, UserRole.TRADER, request, body);
    }

    private ResultActions as(UUID user, UserRole role, MockHttpServletRequestBuilder request, String body)
            throws Exception {
        request.header(HttpHeaders.AUTHORIZATION, bearer(user, role));
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(request);
    }

    /** A post of {@code author} that another Trader reported and an Admin hid (HIDE_POST). */
    private String hiddenByModeration(UUID author, String title, String content) throws Exception {
        String id = id(as(author, post(POSTS), body(title, content, null)));
        as(trader("Bob"), post(POSTS + "/" + id + "/reports"), "{\"reason\": \"SPAM\"}")
                .andExpect(status().isCreated());
        as(
                        account(UserRole.ADMIN, "Admin"),
                        UserRole.ADMIN,
                        post("/api/v1/admin/reports-queue/" + id + "/decision"),
                        "{\"action\": \"HIDE_POST\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.postStatus").value("HIDDEN"));
        return id;
    }

    private static String id(ResultActions created) throws Exception {
        return JsonPath.read(created.andReturn().getResponse().getContentAsString(), "$.id");
    }

    private static String body(String title, String content, String market, String... coins) {
        StringBuilder json = new StringBuilder("{\"title\": ")
                .append(quoted(title))
                .append(", \"content\": ")
                .append(quoted(content));
        if (market != null) {
            json.append(", \"marketType\": ").append(quoted(market));
        }
        json.append(", \"coins\": [");
        for (int i = 0; i < coins.length; i++) {
            json.append(i == 0 ? "" : ", ").append(quoted(coins[i]));
        }
        return json.append("]}").toString();
    }

    private static String quoted(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private String storedStatus(String postId) {
        return sql.sql("select post_status from forum_post where post_id = ?")
                .param(UUID.fromString(postId))
                .query(String.class)
                .single();
    }

    private void createdAgo(String postId, Duration age) {
        sql.sql("update forum_post set created_at = ? where post_id = ?")
                .params(Timestamp.from(Instant.now().minus(age)), UUID.fromString(postId))
                .update();
    }

    private void engage(UUID user, String postId, String action) {
        engage(user, postId, action, Instant.now());
    }

    private void engage(UUID user, String postId, String action, Instant at) {
        sql.sql("""
                        insert into engagement (engagement_id, user_id, post_id, action_type, created_at, updated_at)
                        values (?, ?, ?, ?, ?, ?)""")
                .params(
                        UUID.randomUUID(),
                        user,
                        UUID.fromString(postId),
                        action,
                        Timestamp.from(at),
                        Timestamp.from(at))
                .update();
    }

    private void comment(UUID author, String postId, String status) {
        Timestamp now = Timestamp.from(Instant.now());
        sql.sql("""
                        insert into forum_comment (comment_id, post_id, author_id, content, comment_status, created_at,
                                                   updated_at)
                        values (?, ?, ?, 'a comment', ?, ?, ?)""")
                .params(UUID.randomUUID(), UUID.fromString(postId), author, status, now, now)
                .update();
    }

    private UUID trader(String displayName) {
        return account(UserRole.TRADER, displayName);
    }

    private UUID account(UserRole role, String displayName) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        sql.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status, created_at,
                                                  updated_at)
                        values (?, ?, 'x', ?, 'ACTIVE', ?, ?)""").params(id, id + "@t060.invalid", role.name(), now, now).update();
        sql.sql("""
                        insert into user_profile (user_id, display_name, created_at, updated_at)
                        values (?, ?, ?, ?)""").params(id, displayName, now, now).update();
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
