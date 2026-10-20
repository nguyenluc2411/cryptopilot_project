package com.cryptopilot.community.controller;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Violation reports end to end: filed OPEN, once per Trader and post even when sent twice at once, never of one's own
 * post, OTHER with a description, and only for a post the reporter may see.
 *
 * <p>Rule: UC-33; BR-46, BR-47; SRS 3.7.4; MSG34; D-14.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class ReportApiTest {

    private static final String POSTS = "/api/v1/posts";

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
            sql.sql("delete from post_report where reporter_id = ?")
                    .param(account)
                    .update();
            sql.sql("delete from forum_post where author_id = ?").param(account).update();
            sql.sql("delete from user_account where user_id = ?").param(account).update();
        }
    }

    @Test
    void UC33_aReport_isFiledOpen() throws Exception {
        UUID author = trader();
        UUID reporter = trader();
        String post = newPost(author);

        report(reporter, post, "{\"reason\": \"SCAM\", \"description\": \"  Promises 100x  \"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.postId").value(post))
                .andExpect(jsonPath("$.reason").value("SCAM"))
                .andExpect(jsonPath("$.description").value("Promises 100x"))
                .andExpect(jsonPath("$.status").value("OPEN"));

        assertThat(sql.sql("select report_status from post_report where post_id = ? and reporter_id = ?")
                        .params(UUID.fromString(post), reporter)
                        .query(String.class)
                        .single())
                .isEqualTo("OPEN");
    }

    @Test
    void BR46_aTraderReportsAPostOnce() throws Exception {
        UUID author = trader();
        UUID reporter = trader();
        UUID other = trader();
        String post = newPost(author);

        report(reporter, post, "{\"reason\": \"SPAM\"}").andExpect(status().isCreated());
        report(reporter, post, "{\"reason\": \"ABUSE\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("POST_ALREADY_REPORTED"))
                .andExpect(jsonPath("$.messageCode").value("MSG34"));
        report(other, post, "{\"reason\": \"SPAM\"}").andExpect(status().isCreated());
        assertThat(reportsOf(post)).isEqualTo(2);
    }

    @RepeatedTest(5)
    void BR46_twoReportsSentAtOnce_storeOne_andTheOtherIsAlreadyReported() throws Exception {
        UUID author = trader();
        UUID reporter = trader();
        String post = newPost(author);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Integer> send = () -> {
            start.await();
            return report(reporter, post, "{\"reason\": \"SPAM\"}")
                    .andReturn()
                    .getResponse()
                    .getStatus();
        };
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Integer> first = pool.submit(send);
            Future<Integer> second = pool.submit(send);
            start.countDown();
            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(201, 409);
        }
        assertThat(reportsOf(post)).isEqualTo(1);
    }

    @Test
    void BR46_aTraderCannotReportTheirOwnPost() throws Exception {
        UUID author = trader();
        String post = newPost(author);

        report(author, post, "{\"reason\": \"SPAM\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("POST_REPORT_OWN_POST"));
        assertThat(reportsOf(post)).isZero();
    }

    @Test
    void UC33_otherNeedsADescription_ofAtMost500Characters() throws Exception {
        UUID author = trader();
        UUID reporter = trader();
        String post = newPost(author);

        report(reporter, post, "{\"reason\": \"OTHER\", \"description\": \"   \"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.description").value("MSG01"));
        report(reporter, post, "{\"reason\": \"OTHER\", \"description\": \"" + "x".repeat(501) + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.description").value("MSG01"));
        report(reporter, post, "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.reason").value("MSG01"));
        report(reporter, post, "{\"reason\": \"RUDE\"}").andExpect(status().isBadRequest());
        report(reporter, post, "{\"reason\": \"OTHER\", \"description\": \"" + "x".repeat(500) + "\"}")
                .andExpect(status().isCreated());
    }

    @Test
    void BR47_aPostTheReporterMayNotSee_cannotBeReported() throws Exception {
        UUID author = trader();
        UUID reporter = trader();
        String post = newPost(author);
        sql.sql("update forum_post set post_status = 'DELETED' where post_id = ?")
                .param(UUID.fromString(post))
                .update();

        report(reporter, post, "{\"reason\": \"SPAM\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.messageCode").value("MSG41"));
        report(reporter, UUID.randomUUID().toString(), "{\"reason\": \"SPAM\"}").andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------------------------------

    private String newPost(UUID author) throws Exception {
        return JsonPath.read(
                mvc.perform(post(POSTS)
                                .header(HttpHeaders.AUTHORIZATION, bearer(author))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"title\": \"Moon soon, 100x\", \"content\": \"Trust me\"}"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.id");
    }

    private ResultActions report(UUID reporter, String postId, String body) throws Exception {
        return mvc.perform(post(POSTS + "/" + postId + "/reports")
                .header(HttpHeaders.AUTHORIZATION, bearer(reporter))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private long reportsOf(String postId) {
        return sql.sql("select count(*) from post_report where post_id = ?")
                .param(UUID.fromString(postId))
                .query(Long.class)
                .single();
    }

    private UUID trader() {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        sql.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status, created_at,
                                                  updated_at)
                        values (?, ?, 'x', 'TRADER', 'ACTIVE', ?, ?)""").params(id, id + "@t064.invalid", now, now).update();
        sql.sql("insert into user_profile (user_id, display_name, created_at, updated_at) values (?, 'Reporter', ?, ?)")
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
