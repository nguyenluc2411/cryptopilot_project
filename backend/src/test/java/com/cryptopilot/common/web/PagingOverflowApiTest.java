package com.cryptopilot.common.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cryptopilot.auth.config.JwtConfig;
import com.cryptopilot.billing.model.enums.PlanTier;
import com.cryptopilot.support.PlanTraders;
import com.cryptopilot.support.TestcontainersConfig;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import org.springframework.transaction.annotation.Transactional;

/**
 * A page far past the last row is a 400 on its parameter on every kind of paged list, never a 500 from an offset that
 * overflows in the persistence layer (T-118): JPA lists, the SQL lists of the community, news and Academy, and the
 * administration console's lists. A page number beyond what an integer holds is refused the same way.
 *
 * <p>Rule: CR-04; T-118 (PR #95 review N-5).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@Transactional
class PagingOverflowApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient sql;

    @Autowired
    private JwtEncoder jwtEncoder;

    private String trader;
    private String admin;

    @BeforeEach
    void setUp() {
        trader = new PlanTraders(sql, jwtEncoder, Instant.now()).bearer(PlanTier.FREE);
        admin = adminBearer();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
            strings = {
                "/api/v1/journal",
                "/api/v1/plans",
                "/api/v1/notifications",
                "/api/v1/posts",
                "/api/v1/news",
                "/api/v1/academy/articles"
            })
    void T118_aTradersListPastTheLastOffset_isRefusedOnItsPage(String path) throws Exception {
        mvc.perform(get(path).param("page", "2147483647").header(HttpHeaders.AUTHORIZATION, trader))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.page").value("MSG15"));
        mvc.perform(get(path).param("page", "99999999999").header(HttpHeaders.AUTHORIZATION, trader))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
            strings = {
                "/api/v1/admin/orders",
                "/api/v1/admin/audit-log",
                "/api/v1/admin/system-events",
                "/api/v1/admin/users",
                "/api/v1/admin/reports-queue"
            })
    void T118_anAdministratorsListPastTheLastOffset_isRefusedOnItsPage(String path) throws Exception {
        mvc.perform(get(path).param("page", "2147483647").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.page").value("MSG15"));
    }

    private String adminBearer() {
        UUID id = UUID.randomUUID();
        Timestamp at = Timestamp.from(Instant.now());
        sql.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status, created_at,
                                                  updated_at)
                        values (?, ?, 'x', 'ADMIN', 'ACTIVE', ?, ?)""").params(id, id + "@t118.invalid", at, at).update();
        Instant issued = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(JwtConfig.ISSUER)
                .subject(id.toString())
                .issuedAt(issued)
                .expiresAt(issued.plusSeconds(900))
                .claim(JwtConfig.ROLE_CLAIM, "ADMIN")
                .build();
        return "Bearer "
                + jwtEncoder
                        .encode(JwtEncoderParameters.from(
                                JwsHeader.with(JwtConfig.ALGORITHM).build(), claims))
                        .getTokenValue();
    }
}
