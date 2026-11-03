package com.cryptopilot.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cryptopilot.auth.config.JwtConfig;
import com.cryptopilot.support.TestcontainersConfig;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * SCR-44: the audit log is searched by actor, action, entity and period, newest first, and exported as the same rows.
 * The fixture lives in May 2032, a month no other test writes audit entries in, so every search is bounded by it and
 * its figures are exact; and it sits on the edges of the business day, UTC+7 (CR-01).
 *
 * <p>Rule: SRS 3.11.9; UC-46; BR-57.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class AuditLogApiTest {

    private static final String API = "/api/v1/admin/audit-log";
    private static final String MAY = "from=2032-05-01&to=2032-05-31";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient sql;

    @Autowired
    private JwtEncoder jwtEncoder;

    private final List<UUID> accounts = new ArrayList<>();
    private final List<UUID> entries = new ArrayList<>();
    private final String tag = UUID.randomUUID().toString().substring(0, 8);
    private UUID admin;
    private UUID trader;
    private UUID target;

    @BeforeEach
    void fixture() {
        admin = account("ADMIN", "auditor-" + tag + "@uc46.invalid", "Kiem Toan " + tag);
        trader = account("TRADER", "trader-" + tag + "@uc46.invalid", "Nha Dau Tu 50%_" + tag);
        target = UUID.randomUUID();
        // Oldest to newest.
        entry(
                admin,
                "ACCOUNT_LOCKED",
                "USER_ACCOUNT",
                target,
                "2032-05-01T00:00:00+07:00",
                "{\"status\": \"ACTIVE\"}",
                "{\"status\": \"LOCKED\", \"reason\": \"=HYPERLINK(1), spam\"}");
        entry(
                null,
                "CONFIGURATION_CHANGED",
                "SYSTEM_SETTING",
                null,
                "2032-05-10T12:00:00+07:00",
                "{\"key\": \"MAX_ALERTS\", \"value\": \"10\"}",
                "{\"key\": \"MAX_ALERTS\", \"value\": \"20\"}");
        entry(
                admin,
                "ACCOUNT_UNLOCKED",
                "USER_ACCOUNT",
                target,
                "2032-05-20T09:00:00+07:00",
                "{\"status\": \"LOCKED\"}",
                "{\"status\": \"ACTIVE\"}");
        entry(trader, "POST_HIDDEN", "FORUM_POST", UUID.randomUUID(), "2032-05-31T23:59:59+07:00", null, null);
        // Outside the month on both sides, by one second.
        entry(admin, "ROLE_CHANGED", "USER_ACCOUNT", target, "2032-04-30T23:59:59+07:00", null, null);
        entry(admin, "ROLE_CHANGED", "USER_ACCOUNT", target, "2032-06-01T00:00:00+07:00", null, null);
    }

    @AfterEach
    void removeRows() {
        for (UUID entry : entries) {
            sql.sql("delete from audit_log where audit_id = ?").param(entry).update();
        }
        for (UUID account : accounts) {
            sql.sql("delete from user_profile where user_id = ?").param(account).update();
            sql.sql("delete from user_account where user_id = ?").param(account).update();
        }
    }

    @Test
    void SCR44_theMonth_isListedNewestFirst_acrossPages_withActorAndValues() throws Exception {
        as(admin, get(API + "?" + MAY + "&pageSize=3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(4))
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].action").value("POST_HIDDEN"))
                .andExpect(jsonPath("$.items[0].actor.email").value("trader-" + tag + "@uc46.invalid"))
                .andExpect(jsonPath("$.items[0].actor.role").value("TRADER"))
                .andExpect(jsonPath("$.items[0].oldValue").isEmpty())
                .andExpect(jsonPath("$.items[1].action").value("ACCOUNT_UNLOCKED"))
                .andExpect(jsonPath("$.items[1].actor.displayName").value("Kiem Toan " + tag))
                .andExpect(jsonPath("$.items[1].entityType").value("USER_ACCOUNT"))
                .andExpect(jsonPath("$.items[1].entityId").value(target.toString()))
                .andExpect(jsonPath("$.items[1].ipAddress").value("203.0.113.7"))
                .andExpect(jsonPath("$.items[1].oldValue.status").value("LOCKED"))
                .andExpect(jsonPath("$.items[1].newValue.status").value("ACTIVE"))
                .andExpect(jsonPath("$.items[2].action").value("CONFIGURATION_CHANGED"))
                .andExpect(jsonPath("$.items[2].actor").isEmpty())
                .andExpect(jsonPath("$.items[2].newValue.value").value("20"));
        as(admin, get(API + "?" + MAY + "&pageSize=3&page=2"))
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].action").value("ACCOUNT_LOCKED"));
    }

    @Test
    void SCR44_theActorFilter_matchesEmailOrName_andTakesPercentLiterally() throws Exception {
        as(admin, get(API + "?" + MAY + "&actor=AUDITOR-" + tag))
                .andExpect(jsonPath("$.total").value(2));
        as(admin, get(API + "?" + MAY).param("actor", "dau tu 50%_" + tag))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].action").value("POST_HIDDEN"));
        as(admin, get(API + "?" + MAY).param("actor", "Nha Dau Tu 5%"))
                .andExpect(jsonPath("$.total").value(0));
        as(admin, get(API + "?" + MAY + "&actor=nobody-" + tag))
                .andExpect(jsonPath("$.total").value(0));
        as(admin, get(API + "?" + MAY + "&actorId=" + trader))
                .andExpect(jsonPath("$.total").value(1));
        as(admin, get(API + "?" + MAY + "&actor=auditor-" + tag + "&actorId=" + trader))
                .andExpect(jsonPath("$.total").value(0));
        as(admin, get(API + "?" + MAY + "&actorType=SYSTEM"))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].action").value("CONFIGURATION_CHANGED"));
        as(admin, get(API + "?" + MAY + "&actorType=USER"))
                .andExpect(jsonPath("$.total").value(3));
        as(admin, get(API + "?" + MAY + "&actorType=SYSTEM&actorId=" + admin))
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void SCR44_actionsEntityAndPeriod_narrowTogether() throws Exception {
        as(admin, get(API + "?" + MAY + "&action=ACCOUNT_LOCKED&action=ACCOUNT_UNLOCKED"))
                .andExpect(jsonPath("$.total").value(2));
        as(admin, get(API + "?" + MAY + "&entityType=USER_ACCOUNT&entityId=" + target))
                .andExpect(jsonPath("$.total").value(2));
        as(admin, get(API + "?" + MAY + "&entityType=FORUM_POST"))
                .andExpect(jsonPath("$.total").value(1));
        as(admin, get(API + "?" + MAY + "&action=POST_HIDDEN&entityType=USER_ACCOUNT"))
                .andExpect(jsonPath("$.total").value(0));
        // Business days, UTC+7: 00:00 of the first day is in, 23:59:59 of the last day is in.
        as(admin, get(API + "?from=2032-05-01&to=2032-05-01"))
                .andExpect(jsonPath("$.total").value(1));
        as(admin, get(API + "?from=2032-05-31&to=2032-05-31"))
                .andExpect(jsonPath("$.total").value(1));
        as(admin, get(API + "?entityId=" + target))
                .andExpect(jsonPath("$.total").value(4));
    }

    @Test
    void SCR44_badFilters_areRefused_andOnlyAdminsRead() throws Exception {
        as(admin, get(API + "?from=2032-05-02&to=2032-05-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.from").value("MSG15"));
        as(admin, get(API + "?action=NOT_AN_ACTION")).andExpect(status().isBadRequest());
        as(admin, get(API + "?pageSize=101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.pageSize").value("MSG15"));
        as(trader, get(API)).andExpect(status().isForbidden());
        as(trader, get(API + "/export")).andExpect(status().isForbidden());
        mvc.perform(get(API)).andExpect(status().isUnauthorized());
    }

    @Test
    void SCR44_theExport_isTheSameRows_asCsv_withFormulasDefused() throws Exception {
        String csv = as(admin, get(API + "/export?" + MAY + "&actorId=" + admin))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/csv;charset=UTF-8"))
                .andExpect(header().string(
                                HttpHeaders.CONTENT_DISPOSITION,
                                "attachment; filename=\"cryptopilot-audit-log-2032-05-01-2032-05-31.csv\""))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String[] lines = csv.split("\r\n");
        assertThat(lines).hasSize(3);
        assertThat(lines[0])
                .isEqualTo("recordedAt,actorEmail,actorId,action,entityType,entityId,ipAddress,oldValue,newValue");
        assertThat(lines[1])
                .startsWith("2032-05-20T09:00:00+07:00,auditor-" + tag + "@uc46.invalid," + admin
                        + ",ACCOUNT_UNLOCKED,USER_ACCOUNT," + target + ",203.0.113.7,");
        assertThat(lines[2])
                .startsWith("2032-05-01T00:00:00+07:00,")
                .endsWith(",\"{\"\"reason\"\":\"\"=HYPERLINK(1), spam\"\",\"\"status\"\":\"\"LOCKED\"\"}\"");
    }

    // ------------------------------------------------------------------------------------------

    private UUID account(String role, String email, String displayName) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        sql.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status, created_at,
                                                  updated_at)
                        values (?, ?, 'x', ?, 'ACTIVE', ?, ?)""").params(id, email, role, now, now).update();
        sql.sql("""
                        insert into user_profile (user_id, display_name, created_at, updated_at)
                        values (?, ?, ?, ?)""").params(id, displayName, now, now).update();
        accounts.add(id);
        return id;
    }

    private void entry(
            UUID actor, String action, String entityType, UUID entityId, String at, String oldValue, String newValue) {
        UUID id = UUID.randomUUID();
        Timestamp when = Timestamp.from(OffsetDateTime.parse(at).toInstant());
        sql.sql("""
                        insert into audit_log (audit_id, user_id, action_code, entity_type, entity_id, old_value,
                                               new_value, ip_address, created_at, updated_at)
                        values (?, ?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb), '203.0.113.7', ?, ?)""")
                .params(id, actor, action, entityType, entityId, oldValue, newValue, when, when)
                .update();
        entries.add(id);
    }

    private ResultActions as(UUID user, MockHttpServletRequestBuilder request) throws Exception {
        request.header(HttpHeaders.AUTHORIZATION, bearer(user));
        return mvc.perform(request);
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
