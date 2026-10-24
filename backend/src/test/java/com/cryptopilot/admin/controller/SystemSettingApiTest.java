package com.cryptopilot.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cryptopilot.admin.SystemSettingApi;
import com.cryptopilot.auth.config.JwtConfig;
import com.cryptopilot.support.TestcontainersConfig;
import com.cryptopilot.user.model.enums.UserRole;
import java.math.BigDecimal;
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
 * SCR-43: an administrator reads and changes the system settings; a change is checked against the setting's type and
 * range, audited, and read by other modules at once, through the cache, without a redeployment.
 *
 * <p>Rule: SRS 3.11.8, UC-51; BR-57.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class SystemSettingApiTest {

    private static final String SETTINGS = "/api/v1/admin/settings";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient sql;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private SystemSettingApi settings;

    private final List<UUID> accounts = new ArrayList<>();
    private UUID admin;
    private UUID trader;

    @BeforeEach
    void setUp() {
        admin = account(UserRole.ADMIN);
        trader = account(UserRole.TRADER);
    }

    @AfterEach
    void restore() throws Exception {
        // Through the API, so the reader's cache is evicted as well.
        change("SPOT_TAKER_FEE", "0.00100000");
        change("MAX_IMAGES_PER_POST", "4");
        sql.sql("""
                        update system_setting set updated_by = null, updated_at = timestamptz '2026-01-01 00:00:00+00'
                         where updated_by = ?""").param(admin).update();
        sql.sql("delete from audit_log where user_id = ?").param(admin).update();
        for (UUID account : accounts) {
            sql.sql("delete from user_account where user_id = ?").param(account).update();
        }
    }

    @Test
    void UC51_theSettings_areListedForAdministrators_withTheirRange() throws Exception {
        as(admin, get(SETTINGS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.key == 'SPOT_TAKER_FEE')].valueType").value("DECIMAL"))
                .andExpect(jsonPath("$[?(@.key == 'SPOT_TAKER_FEE')].max").value("0.1"))
                .andExpect(jsonPath("$[?(@.key == 'MAX_IMAGES_PER_POST')].min").value("1"));
        as(admin, get(SETTINGS + "/ORDER_EXPIRY_MINUTES"))
                .andExpect(jsonPath("$.value").value("15"))
                .andExpect(jsonPath("$.description").exists());
        as(admin, get(SETTINGS + "/NOT_A_SETTING")).andExpect(status().isNotFound());
        as(trader, get(SETTINGS)).andExpect(status().isForbidden());
    }

    @Test
    void UC51_aChange_isReadAtOnce_andAudited() throws Exception {
        assertThat(settings.decimal("SPOT_TAKER_FEE")).isEqualByComparingTo("0.001");
        assertThat(settings.integer("MAX_IMAGES_PER_POST")).isEqualTo(4);

        change("SPOT_TAKER_FEE", "0.0008")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value("0.0008"))
                .andExpect(jsonPath("$.updatedBy").value(admin.toString()));
        change("MAX_IMAGES_PER_POST", " 6 ").andExpect(jsonPath("$.value").value("6"));

        assertThat(settings.decimal("SPOT_TAKER_FEE")).isEqualByComparingTo(new BigDecimal("0.0008"));
        assertThat(settings.integer("MAX_IMAGES_PER_POST")).isEqualTo(6);
        assertThat(sql.sql("""
                                select new_value ->> 'value' from audit_log
                                 where user_id = ? and action_code = 'CONFIGURATION_CHANGED'
                                   and entity_type = 'SYSTEM_SETTING' and old_value ->> 'key' = 'SPOT_TAKER_FEE'""").param(admin).query(String.class).list()).containsExactly("0.0008");
    }

    @Test
    void UC51_aValueOfTheWrongType_orOutOfRange_isRefused_andNothingChanges() throws Exception {
        for (String value : List.of("0.5", "-0.001", "abc", "0.000000001")) {
            change("SPOT_TAKER_FEE", value)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.value").value("MSG15"));
        }
        for (String value : List.of("0", "11", "4.5")) {
            change("MAX_IMAGES_PER_POST", value).andExpect(status().isBadRequest());
        }
        change("SPOT_TAKER_FEE", "").andExpect(jsonPath("$.errors.value").value("MSG01"));
        change("NOT_A_SETTING", "1").andExpect(status().isNotFound());
        as(trader, put(SETTINGS + "/SPOT_TAKER_FEE"), "{\"value\": \"0.0001\"}").andExpect(status().isForbidden());

        assertThat(settings.decimal("SPOT_TAKER_FEE")).isEqualByComparingTo("0.001");
        assertThat(sql.sql("select count(*) from audit_log where user_id = ?")
                        .param(admin)
                        .query(Long.class)
                        .single())
                .isZero();
    }

    /**
     * BR-69: the entry deviation that asks for a confirmation never rises above the one that is refused, whichever of
     * the two moves. The rows are TP-A's (V32); they are written here so the rule is proved before that lands.
     */
    @Test
    void BR69_theConfirmBound_staysAtOrBelowTheBlockBound() throws Exception {
        boolean seeded = sql.sql("select count(*) from system_setting where setting_key = 'ENTRY_DEVIATION_BLOCK_R'")
                        .query(Long.class)
                        .single()
                > 0;
        if (!seeded) {
            sql.sql("""
                            insert into system_setting (setting_key, setting_value, value_type, description, updated_at)
                            values ('ENTRY_DEVIATION_CONFIRM_R', '0.25000000', 'DECIMAL', 'BR-69', now()),
                                   ('ENTRY_DEVIATION_BLOCK_R', '0.75000000', 'DECIMAL', 'BR-69', now())""").update();
        }
        try {
            change("ENTRY_DEVIATION_CONFIRM_R", "0.8")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.value").value("MSG15"));
            change("ENTRY_DEVIATION_BLOCK_R", "0.2").andExpect(status().isBadRequest());
            change("ENTRY_DEVIATION_CONFIRM_R", "0.75").andExpect(status().isOk());
            change("ENTRY_DEVIATION_BLOCK_R", "1").andExpect(status().isOk());
            change("ENTRY_DEVIATION_CONFIRM_R", "0.9").andExpect(status().isOk());
        } finally {
            if (seeded) {
                change("ENTRY_DEVIATION_CONFIRM_R", "0.25000000");
                change("ENTRY_DEVIATION_BLOCK_R", "0.75000000");
            } else {
                sql.sql("delete from system_setting where setting_key like 'ENTRY_DEVIATION_%'")
                        .update();
            }
        }
    }

    // ------------------------------------------------------------------------------------------

    private ResultActions change(String key, String value) throws Exception {
        return as(admin, put(SETTINGS + "/" + key), "{\"value\": \"" + value + "\"}");
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
                        values (?, ?, 'x', ?, 'ACTIVE', ?, ?)""").params(id, id + "@t082.invalid", role.name(), now, now).update();
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
