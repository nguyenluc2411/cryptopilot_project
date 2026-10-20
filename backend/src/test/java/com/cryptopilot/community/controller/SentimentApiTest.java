package com.cryptopilot.community.controller;

import static org.assertj.core.api.Assertions.assertThat;
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
 * Bullish and bearish, as on CoinMarketCap Community: a post's label and the feed filter by it, and the daily vote on
 * a coin — once per Trader and day, the last vote of the day counting, readable by anyone.
 *
 * <p>Rule: BR-60; CR-02; D-99.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class SentimentApiTest {

    private static final String POSTS = "/api/v1/posts";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient sql;

    @Autowired
    private JwtEncoder jwtEncoder;

    private final List<UUID> accounts = new ArrayList<>();
    private final List<UUID> pairs = new ArrayList<>();
    private String coin;
    private String unlisted;

    @BeforeEach
    void insertPairs() {
        MarketTestData market = new MarketTestData(sql, Instant.now());
        String run = Long.toString(System.nanoTime() % 1_000_000_000L);
        coin = "SN" + run + "A";
        unlisted = "SN" + run + "X";
        pairs.add(market.pair(coin + "USDT", true, false, "TRADING", null, 0));
        pairs.add(market.pair(unlisted + "USDT", false, false, null, null, 1));
    }

    @AfterEach
    void removeRows() {
        for (UUID account : accounts) {
            sql.sql("delete from coin_sentiment_vote where user_id = ?")
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
            for (UUID id : coins) {
                sql.sql("""
                                delete from coin where coin_id = ?
                                   and not exists (select 1 from crypto_pair
                                                    where base_coin_id = coin.coin_id or quote_coin_id = coin.coin_id)
                                   and not exists (select 1 from coin_sentiment_vote v where v.coin_id = coin.coin_id)
                                   and not exists (select 1 from post_coin c where c.coin_id = coin.coin_id)""").param(id).update();
            }
        }
    }

    @Test
    void D99_aPostIsLabelledBullishOrBearish_andTheFeedFiltersByIt() throws Exception {
        UUID author = trader();
        String bull = id(as(author, post(POSTS), postBody("Going up", "BULLISH")));
        String bear = id(as(author, post(POSTS), postBody("Going down", "BEARISH")));
        as(author, post(POSTS), postBody("No view", null));

        mvc.perform(get(POSTS + "?coin=" + coin + "&sentiment=BULLISH"))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(bull))
                .andExpect(jsonPath("$.items[0].sentiment").value("BULLISH"));
        mvc.perform(get(POSTS + "?coin=" + coin + "&sentiment=BEARISH"))
                .andExpect(jsonPath("$.items[0].id").value(bear));
        mvc.perform(get(POSTS + "?coin=" + coin)).andExpect(jsonPath("$.total").value(3));
        as(author, put(POSTS + "/" + bear), postBody("Changed my mind", "BULLISH"))
                .andExpect(jsonPath("$.sentiment").value("BULLISH"));
        mvc.perform(get(POSTS + "?coin=" + coin + "&sentiment=BULLISH"))
                .andExpect(jsonPath("$.total").value(2));
        mvc.perform(get(POSTS + "?sentiment=SIDEWAYS")).andExpect(status().isBadRequest());
    }

    @Test
    void D99_aTraderVotesOnceADay_andTheLastVoteOfTheDayCounts() throws Exception {
        UUID first = trader();
        UUID second = trader();
        UUID third = trader();
        vote(first, coin, "BULLISH")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.myVote").value("BULLISH"));
        vote(second, coin, "BULLISH");
        vote(third, coin, "BEARISH");
        vote(first, coin, "BEARISH").andExpect(jsonPath("$.myVote").value("BEARISH"));

        mvc.perform(get(POSTS + "/coins/" + coin.toLowerCase() + "/sentiment"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value(coin))
                .andExpect(jsonPath("$.bullishVotes").value(1))
                .andExpect(jsonPath("$.bearishVotes").value(2))
                .andExpect(jsonPath("$.bullishPercent").value("33.33"))
                .andExpect(jsonPath("$.myVote").doesNotExist());
        as(second, get(POSTS + "/coins/" + coin + "/sentiment"), null)
                .andExpect(jsonPath("$.myVote").value("BULLISH"));
        assertThat(sql.sql("select count(*) from coin_sentiment_vote where user_id = ?")
                        .param(first)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void D99_twoVotesAtOnce_leaveOneRow() throws Exception {
        UUID trader = trader();
        CountDownLatch start = new CountDownLatch(1);
        Callable<Integer> voting = () -> {
            start.await();
            return vote(trader, coin, "BULLISH").andReturn().getResponse().getStatus();
        };
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Integer> a = pool.submit(voting);
            Future<Integer> b = pool.submit(voting);
            start.countDown();
            assertThat(List.of(a.get(), b.get())).containsOnly(200);
        }
        assertThat(sql.sql("select count(*) from coin_sentiment_vote where user_id = ?")
                        .param(trader)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void D99_theSentiment_countsTheLabelsOfRecentPosts_andNeedsNoVote() throws Exception {
        UUID author = trader();
        as(author, post(POSTS), postBody("Going up now", "BULLISH"));
        as(author, post(POSTS), postBody("Going up again", "BULLISH"));
        as(author, post(POSTS), postBody("Going down now", "BEARISH"));

        mvc.perform(get(POSTS + "/coins/" + coin + "/sentiment"))
                .andExpect(jsonPath("$.bullishPosts").value(2))
                .andExpect(jsonPath("$.bearishPosts").value(1))
                .andExpect(jsonPath("$.bullishPercent").doesNotExist());
    }

    @Test
    void D99_votingNeedsATrader_aListedCoin_andASentiment() throws Exception {
        UUID trader = trader();

        mvc.perform(post(POSTS + "/coins/" + coin + "/sentiment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sentiment\": \"BULLISH\"}"))
                .andExpect(status().isUnauthorized());
        vote(trader, unlisted, "BULLISH").andExpect(status().isNotFound());
        vote(trader, "NOSUCHCOIN", "BULLISH").andExpect(status().isNotFound());
        as(trader, post(POSTS + "/coins/" + coin + "/sentiment"), "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.sentiment").value("MSG01"));
        mvc.perform(get(POSTS + "/coins/NOSUCHCOIN/sentiment")).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------------------------------

    private String postBody(String title, String sentiment) {
        return "{\"title\": \"" + title + "\", \"content\": \"text\", \"coins\": [\"" + coin + "\"]"
                + (sentiment == null ? "" : ", \"sentiment\": \"" + sentiment + "\"") + "}";
    }

    private ResultActions vote(UUID trader, String symbol, String sentiment) throws Exception {
        return as(trader, post(POSTS + "/coins/" + symbol + "/sentiment"), "{\"sentiment\": \"" + sentiment + "\"}");
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
                        values (?, ?, 'x', 'TRADER', 'ACTIVE', ?, ?)""").params(id, id + "@cf02.invalid", now, now).update();
        sql.sql("insert into user_profile (user_id, display_name, created_at, updated_at) values (?, 'Voter', ?, ?)")
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
