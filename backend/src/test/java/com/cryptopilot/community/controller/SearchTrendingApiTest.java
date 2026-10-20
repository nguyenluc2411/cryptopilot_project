package com.cryptopilot.community.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cryptopilot.auth.config.JwtConfig;
import com.cryptopilot.market.MarketTestData;
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
 * Search, hashtags and trending topics, as on CoinMarketCap Community: a search in titles and texts, the hashtags of a
 * post and the feed filtered by one, and the most discussed hashtags and coins of a window.
 *
 * <p>Every test uses words and hashtags of its own run, so posts other tests leave behind cannot change a result.
 *
 * <p>Rule: D-99.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class SearchTrendingApiTest {

    private static final String POSTS = "/api/v1/posts";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient sql;

    @Autowired
    private JwtEncoder jwtEncoder;

    private final List<UUID> accounts = new ArrayList<>();
    private final List<UUID> pairs = new ArrayList<>();
    private String run;
    private String coin;

    @BeforeEach
    void insertPair() {
        run = "r" + Long.toString(System.nanoTime() % 1_000_000_000L);
        coin = "TR" + run.substring(1) + "A";
        pairs.add(new MarketTestData(sql, Instant.now()).pair(coin + "USDT", true, false, "TRADING", null, 0));
    }

    @AfterEach
    void removeRows() {
        for (UUID account : accounts) {
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
            for (UUID id : coins) {
                sql.sql("""
                                delete from coin where coin_id = ?
                                   and not exists (select 1 from crypto_pair
                                                    where base_coin_id = coin.coin_id or quote_coin_id = coin.coin_id)
                                   and not exists (select 1 from post_coin c where c.coin_id = coin.coin_id)""").param(id).update();
            }
        }
    }

    @Test
    void D99_aSearch_findsTheWordInTitlesAndTexts_caseIgnored() throws Exception {
        UUID author = trader();
        String inTitle = id(newPost(author, "Breakout " + run + " soon", "text"));
        String inText = id(newPost(author, "Another post", "about the " + run.toUpperCase() + " setup"));
        newPost(author, "Unrelated post", "nothing here");

        mvc.perform(get(POSTS + "?q=" + run))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[?(@.id == '" + inTitle + "')]").isNotEmpty())
                .andExpect(jsonPath("$.items[?(@.id == '" + inText + "')]").isNotEmpty());
        // Wildcards of the database are taken literally.
        mvc.perform(get(POSTS).param("q", run + "%"))
                .andExpect(jsonPath("$.total").value(0));
        mvc.perform(get(POSTS).param("q", "_" + run.substring(1)))
                .andExpect(jsonPath("$.total").value(0));
        mvc.perform(get(POSTS + "?q=x"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.q").value("MSG15"));
        mvc.perform(get(POSTS + "?q=" + "x".repeat(101))).andExpect(status().isBadRequest());
    }

    @Test
    void D99_aPostsHashtags_comeFromItsText_andTheFeedFiltersByOne() throws Exception {
        UUID author = trader();
        String tag = "tag" + run;
        String tagged = id(newPost(author, "Thoughts on #" + tag, "and #Second" + run)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.hashtags[0]").value("second" + run))
                .andExpect(jsonPath("$.hashtags[1]").value(tag)));
        newPost(author, "No hashtag here", "text");

        mvc.perform(get(POSTS).param("hashtag", "#" + tag.toUpperCase()))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(tagged))
                .andExpect(jsonPath("$.items[0].hashtags.length()").value(2));
        as(author, put(POSTS + "/" + tagged), "{\"title\": \"Edited title\", \"content\": \"no tags now\"}")
                .andExpect(jsonPath("$.hashtags.length()").value(0));
        mvc.perform(get(POSTS + "?hashtag=" + tag))
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void D99_trending_namesTheMostDiscussedHashtagsAndCoinsOfTheWindow() throws Exception {
        UUID author = trader();
        String hot = "hot" + run;
        String warm = "warm" + run;
        newPost(author, "One #" + hot, "text", coin);
        newPost(author, "Two #" + hot, "text #" + warm, coin);
        newPost(author, "Three #" + hot, "text");
        String old = id(newPost(author, "Old #" + warm, "text"));
        sql.sql("update forum_post set created_at = ? where post_id = ?")
                .params(Timestamp.from(Instant.now().minus(Duration.ofDays(3))), UUID.fromString(old))
                .update();

        mvc.perform(get(POSTS + "/trending"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.hashtags[?(@.name == '" + hot + "')].posts").value(3))
                .andExpect(jsonPath("$.hashtags[?(@.name == '" + warm + "')].posts")
                        .value(1))
                .andExpect(
                        jsonPath("$.coins[?(@.name == '" + coin + "')].posts").value(2));
        mvc.perform(get(POSTS + "/trending?window=WEEK"))
                .andExpect(jsonPath("$.hashtags[?(@.name == '" + warm + "')].posts")
                        .value(2));
        mvc.perform(get(POSTS + "/trending?window=YEAR")).andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------------------------------

    private ResultActions newPost(UUID author, String title, String content, String... coins) throws Exception {
        String tags = coins.length == 0 ? "" : ", \"coins\": [\"" + String.join("\", \"", coins) + "\"]";
        return as(author, post(POSTS), "{\"title\": \"" + title + "\", \"content\": \"" + content + "\"" + tags + "}");
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

    private UUID trader() {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        sql.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status, created_at,
                                                  updated_at)
                        values (?, ?, 'x', 'TRADER', 'ACTIVE', ?, ?)""").params(id, id + "@cf04.invalid", now, now).update();
        sql.sql("insert into user_profile (user_id, display_name, created_at, updated_at) values (?, 'Writer', ?, ?)")
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
