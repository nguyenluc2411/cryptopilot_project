package com.cryptopilot.academy.controller;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * A Trader's progress in the Academy, against the seeded lessons: marking is idempotent and keeps the first instant,
 * the mark comes off, a lesson that is not published cannot be marked, and only a Trader holds a progress.
 *
 * <p>Rule: T-114.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class AcademyProgressApiTest {

    private static final String PROGRESS = "/api/v1/me/academy/progress";
    private static final String ON_PATH = "tien-ma-hoa-la-gi";
    private static final String OFF_PATH = "co-che-dong-thuan-pow-pos";

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
            sql.sql("delete from academy_progress where user_id = ?")
                    .param(account)
                    .update();
            sql.sql("delete from user_account where user_id = ?").param(account).update();
        }
    }

    @Test
    void T114_aTrader_marksLessons_andSeesTheirProgress() throws Exception {
        UUID alice = trader();

        as(alice, get(PROGRESS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completed.length()").value(0))
                .andExpect(jsonPath("$.publishedTotal").value(21))
                .andExpect(jsonPath("$.pathCompleted").value(0))
                .andExpect(jsonPath("$.pathTotal").value(11));

        String first = as(alice, post(PROGRESS + "/" + ON_PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value(ON_PATH))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String again = as(alice, post(PROGRESS + "/" + ON_PATH))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat((String) JsonPath.read(again, "$.completedAt")).isEqualTo(JsonPath.read(first, "$.completedAt"));
        as(alice, post(PROGRESS + "/" + OFF_PATH)).andExpect(status().isOk());

        as(alice, get(PROGRESS))
                .andExpect(jsonPath("$.completed.length()").value(2))
                .andExpect(jsonPath("$.completed[0].slug").value(ON_PATH))
                .andExpect(jsonPath("$.pathCompleted").value(1));

        as(alice, delete(PROGRESS + "/" + ON_PATH)).andExpect(status().isNoContent());
        as(alice, delete(PROGRESS + "/" + ON_PATH)).andExpect(status().isNoContent());
        as(alice, get(PROGRESS))
                .andExpect(jsonPath("$.completed.length()").value(1))
                .andExpect(jsonPath("$.completed[0].slug").value(OFF_PATH))
                .andExpect(jsonPath("$.pathCompleted").value(0));
    }

    @Test
    void T114_progressIsEachTradersOwn() throws Exception {
        UUID alice = trader();
        UUID bob = trader();

        as(alice, post(PROGRESS + "/" + ON_PATH)).andExpect(status().isOk());
        as(bob, get(PROGRESS)).andExpect(jsonPath("$.completed.length()").value(0));
    }

    @Test
    void T114_aLessonThatIsNotPublished_cannotBeMarked() throws Exception {
        UUID alice = trader();

        as(alice, post(PROGRESS + "/khong-co-bai-nay")).andExpect(status().isNotFound());
        as(alice, delete(PROGRESS + "/khong-co-bai-nay")).andExpect(status().isNotFound());
    }

    @Test
    void T114_onlyATrader_holdsAProgress() throws Exception {
        mvc.perform(get(PROGRESS)).andExpect(status().isUnauthorized());
        mvc.perform(post(PROGRESS + "/" + ON_PATH)).andExpect(status().isUnauthorized());
        mvc.perform(get(PROGRESS).header(HttpHeaders.AUTHORIZATION, bearer(UUID.randomUUID(), UserRole.ADMIN)))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------------------------------

    private ResultActions as(UUID user, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, bearer(user, UserRole.TRADER)));
    }

    private UUID trader() {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        sql.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status, created_at,
                                                  updated_at)
                        values (?, ?, 'x', 'TRADER', 'ACTIVE', ?, ?)""").params(id, id + "@academy.invalid", now, now).update();
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
