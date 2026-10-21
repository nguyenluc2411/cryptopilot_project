package com.cryptopilot.community.controller;

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
 * Search, as on CoinMarketCap Community: a word in the titles and texts of the published posts.
 *
 * <p>Every test uses words of its own run, so posts other tests leave behind cannot change a result.
 *
 * <p>Rule: D-99.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class SearchApiTest {

    private static final String POSTS = "/api/v1/posts";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient sql;

    @Autowired
    private JwtEncoder jwtEncoder;

    private final List<UUID> accounts = new ArrayList<>();
    private final String run = "r" + Long.toString(System.nanoTime() % 1_000_000_000L);

    @AfterEach
    void removeRows() {
        for (UUID account : accounts) {
            sql.sql("delete from forum_post where author_id = ?").param(account).update();
            sql.sql("delete from user_account where user_id = ?").param(account).update();
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

    // ------------------------------------------------------------------------------------------

    private ResultActions newPost(UUID author, String title, String content) throws Exception {
        return as(author, post(POSTS), "{\"title\": \"" + title + "\", \"content\": \"" + content + "\"}");
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
                        values (?, ?, 'x', 'TRADER', 'ACTIVE', ?, ?)""").params(id, id + "@search.invalid", now, now).update();
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
