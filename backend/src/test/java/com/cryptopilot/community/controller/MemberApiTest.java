package com.cryptopilot.community.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cryptopilot.auth.config.JwtConfig;
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
 * Member pages, as on CoinMarketCap Community: a public page with the member's activity, none for a banned member, and
 * the feed of one member.
 *
 * <p>Rule: D-99.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class MemberApiTest {

    private static final String POSTS = "/api/v1/posts";
    private static final String MEMBERS = "/api/v1/community/members/";

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
            sql.sql("delete from engagement where user_id = ?").param(account).update();
        }
        for (UUID account : accounts) {
            sql.sql("delete from forum_post where author_id = ?").param(account).update();
            sql.sql("delete from user_account where user_id = ?").param(account).update();
        }
    }

    @Test
    void D99_aMembersPage_showsTheirActivity_toAnyone() throws Exception {
        UUID alice = trader("Alice");
        UUID bob = trader("Bob");
        String first = newPost(alice, "Alice on BTC");
        newPost(alice, "Alice on ETH");
        String deleted = newPost(alice, "Alice deleted");
        as(alice, delete(POSTS + "/" + deleted)).andExpect(status().isNoContent());
        as(bob, post(POSTS + "/" + first + "/like")).andExpect(status().isOk());

        mvc.perform(get(MEMBERS + alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Alice"))
                .andExpect(jsonPath("$.joinedAt").exists())
                .andExpect(jsonPath("$.posts").value(2))
                .andExpect(jsonPath("$.likesReceived").value(1))
                .andExpect(jsonPath("$.me").value(false))
                .andExpect(jsonPath("$.email").doesNotExist());
        as(bob, get(MEMBERS + alice)).andExpect(jsonPath("$.me").value(false));
        as(alice, get(MEMBERS + alice)).andExpect(jsonPath("$.me").value(true));
        mvc.perform(get(POSTS + "?author=" + alice))
                .andExpect(jsonPath("$.total").value(2));
    }

    @Test
    void D99_aBannedMember_andAnUnknownOne_haveNoPage() throws Exception {
        UUID alice = trader("Alice");

        mvc.perform(get(MEMBERS + alice)).andExpect(status().isOk());
        sql.sql("update user_account set account_status = 'BANNED' where user_id = ?")
                .param(alice)
                .update();
        mvc.perform(get(MEMBERS + alice)).andExpect(status().isNotFound());
        mvc.perform(get(MEMBERS + UUID.randomUUID())).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------------------------------

    private String newPost(UUID author, String title) throws Exception {
        return JsonPath.read(
                as(author, post(POSTS), "{\"title\": \"" + title + "\", \"content\": \"text\"}")
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

    private UUID trader(String displayName) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        sql.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status, created_at,
                                                  updated_at)
                        values (?, ?, 'x', 'TRADER', 'ACTIVE', ?, ?)""").params(id, id + "@cf03.invalid", now, now).update();
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
