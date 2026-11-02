package com.cryptopilot.ai.controller;

import static org.assertj.core.api.Assertions.assertThat;
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
 * SCR-42 end to end: an administrator adds versions of the AI configuration, which start inactive and are never
 * edited, and activates one, which deactivates the previous; each change is audited; the Trader has no access.
 *
 * <p>Rule: UC-50; BR-57; SRS 3.11.7.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class AdminAiConfigurationApiTest {

    private static final String CONFIGURATIONS = "/api/v1/admin/ai-configurations";
    private static final String SEEDED = "019b76da-a800-7002-8000-0000000a1001";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient sql;

    @Autowired
    private JwtEncoder jwtEncoder;

    private final List<UUID> accounts = new ArrayList<>();
    private UUID admin;
    private UUID trader;

    @BeforeEach
    void setUp() {
        admin = account(UserRole.ADMIN);
        trader = account(UserRole.TRADER);
    }

    @AfterEach
    void restoreTheSeededVersion() {
        sql.sql("update ai_configuration set is_active = false where config_id <> ?::uuid")
                .param(SEEDED)
                .update();
        sql.sql("update ai_configuration set is_active = true, updated_by = null where config_id = ?::uuid")
                .param(SEEDED)
                .update();
        sql.sql("delete from ai_configuration where config_id <> ?::uuid")
                .param(SEEDED)
                .update();
        sql.sql("delete from audit_log where user_id = ?").param(admin).update();
        for (UUID account : accounts) {
            sql.sql("delete from user_account where user_id = ?").param(account).update();
        }
    }

    @Test
    void UC50_theConsole_isForAdministratorsOnly() throws Exception {
        as(trader, get(CONFIGURATIONS)).andExpect(status().isForbidden());
        as(trader, post(CONFIGURATIONS), body("0.30", 1024)).andExpect(status().isForbidden());
    }

    @Test
    void UC50_theSeededVersion_isListedAsTheActiveOne() throws Exception {
        as(admin, get(CONFIGURATIONS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + SEEDED + "')].active").value(true))
                .andExpect(jsonPath("$[?(@.id == '" + SEEDED + "')].modelName").value("gemini-3.5-flash-lite"));
    }

    @Test
    void UC50_aNewVersion_startsInactive_isAudited_andActivatingItReplacesThePreviousOne() throws Exception {
        String created = as(admin, post(CONFIGURATIONS), body("0.45", 2048))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.modelName").value("gemini-test-model"))
                .andExpect(jsonPath("$.temperature").value("0.45"))
                .andExpect(jsonPath("$.maxOutputTokens").value(2048))
                .andExpect(jsonPath("$.disclaimer").value("Chỉ để tham khảo."))
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.updatedBy").value(admin.toString()))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String id = JsonPath.read(created, "$.id");
        assertThat(audits("CONFIGURATION_CREATED", id)).isEqualTo(1);
        assertThat(activeId()).isEqualTo(SEEDED);

        as(admin, post(CONFIGURATIONS + "/" + id + "/activate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));

        assertThat(activeId()).isEqualTo(id);
        assertThat(audits("CONFIGURATION_CHANGED", id)).isEqualTo(1);
        as(admin, get(CONFIGURATIONS))
                .andExpect(jsonPath("$[0].id").value(id))
                .andExpect(jsonPath("$[?(@.id == '" + SEEDED + "')].active").value(false));

        // Activating the active version again changes nothing and records nothing.
        as(admin, post(CONFIGURATIONS + "/" + id + "/activate")).andExpect(status().isOk());
        assertThat(audits("CONFIGURATION_CHANGED", id)).isEqualTo(1);

        // The previous version is kept and can be activated back.
        as(admin, post(CONFIGURATIONS + "/" + SEEDED + "/activate"))
                .andExpect(jsonPath("$.active").value(true));
        assertThat(activeId()).isEqualTo(SEEDED);
    }

    @Test
    void UC50_valuesOutOfRange_areRefused() throws Exception {
        as(admin, post(CONFIGURATIONS), body("1.50", 1024))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.temperature").value("MSG15"));
        as(admin, post(CONFIGURATIONS), body("0.30", 100))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.maxOutputTokens").value("MSG15"));
        as(admin, post(CONFIGURATIONS), body("0.30", 8192))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.maxOutputTokens").value("MSG15"));
        as(
                        admin,
                        post(CONFIGURATIONS),
                        "{\"modelName\":\"bad model\",\"systemPrompt\":\"\",\"temperature\":0.3,"
                                + "\"maxOutputTokens\":1024,\"disclaimer\":\"x\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.modelName").value("MSG15"))
                .andExpect(jsonPath("$.errors.systemPrompt").value("MSG01"));
        assertThat(sql.sql("select count(*) from ai_configuration")
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
    }

    @Test
    void UC50_anUnknownVersion_isNotFound() throws Exception {
        as(admin, post(CONFIGURATIONS + "/" + UUID.randomUUID() + "/activate"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.messageCode").value("MSG41"));
    }

    private static String body(String temperature, int maxOutputTokens) {
        return "{\"modelName\":\"gemini-test-model\",\"systemPrompt\":\"Explain, never advise.\",\"temperature\":"
                + temperature + ",\"maxOutputTokens\":" + maxOutputTokens + ",\"disclaimer\":\"Chỉ để tham khảo.\"}";
    }

    private String activeId() {
        return sql.sql("select cast(config_id as text) from ai_configuration where is_active")
                .query(String.class)
                .single();
    }

    private long audits(String action, String entityId) {
        return sql.sql("select count(*) from audit_log where user_id = ? and action_code = ? and entity_type ="
                        + " 'AI_CONFIGURATION' and entity_id = ?::uuid")
                .params(admin, action, entityId)
                .query(Long.class)
                .single();
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

    private UUID account(UserRole role) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        sql.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status, created_at,
                                                  updated_at)
                        values (?, ?, 'x', ?, 'ACTIVE', ?, ?)""")
                .params(id, id + "@ai-config.invalid", role.name(), now, now)
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
