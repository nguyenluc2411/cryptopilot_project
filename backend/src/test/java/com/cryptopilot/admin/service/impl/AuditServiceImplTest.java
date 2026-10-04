package com.cryptopilot.admin.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.cryptopilot.admin.AdminApi;
import com.cryptopilot.admin.AuditEntry;
import com.cryptopilot.admin.model.enums.AuditAction;
import com.cryptopilot.admin.model.enums.AuditedEntity;
import com.cryptopilot.common.config.TrustedProxyProperties;
import com.cryptopilot.common.util.UuidV7;
import com.cryptopilot.common.web.ClientAddressFilter;
import com.cryptopilot.support.TestcontainersConfig;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The audit entry and the command it records commit or roll back together. Each test runs a stand-in command in a
 * real transaction — a {@code system_setting} row the admin module owns, plus the audit call — and commits it, so the
 * rows it leaves are removed afterwards.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class AuditServiceImplTest {

    /** The bootstrap administrator V3 seeds. */
    private static final UUID ADMIN = UUID.fromString("019b76da-a800-7000-8000-000000000001");

    private static final String SETTING = "T081_AUDITED_SETTING";

    @Autowired
    private AdminApi admin;

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private JdbcClient sql;

    private final UUID target = UuidV7.next();

    @AfterEach
    void removeRows() {
        RequestContextHolder.resetRequestAttributes();
        sql.sql("delete from audit_log where entity_id = ?").param(target).update();
        sql.sql("delete from system_setting where setting_key = ?")
                .param(SETTING)
                .update();
    }

    @Test
    void NSF18_aCommandThatCommits_storesExactlyOneEntryWithItsActorActionAndTarget() {
        transaction.executeWithoutResult(status -> {
            changeSetting();
            admin.audit(entry(ADMIN, Map.of("value", "1"), Map.of("value", "2")));
        });

        List<Map<String, Object>> rows = auditRows();
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.getFirst();
        assertThat(row)
                .containsEntry("user_id", ADMIN)
                .containsEntry("action_code", "CONFIGURATION_CHANGED")
                .containsEntry("entity_type", "SYSTEM_SETTING")
                .containsEntry("entity_id", target)
                .containsEntry("old_value", "{\"value\": \"1\"}")
                .containsEntry("new_value", "{\"value\": \"2\"}");
        assertThat(row.get("created_at")).isNotNull();
        assertThat(settingStored()).isTrue();
    }

    @Test
    void NSF18_aCommandThatFailsAfterTheAuditCall_leavesNoEntryAndNoChange() {
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
                    changeSetting();
                    admin.audit(entry(ADMIN, null, Map.of("value", "2")));
                    throw new IllegalStateException("the command fails after auditing");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(auditRows()).isEmpty();
        assertThat(settingStored()).isFalse();
    }

    @Test
    void NSF18_anAuditEntryThatCannotBeStored_rollsTheCommandBack() {
        UUID noSuchAccount = UuidV7.next();

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
                    changeSetting();
                    admin.audit(entry(noSuchAccount, null, Map.of("value", "2")));
                }))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(auditRows()).isEmpty();
        assertThat(settingStored()).isFalse();
    }

    @Test
    void NSF18_anAuditCallOutsideATransaction_isRefused() {
        assertThatThrownBy(() -> admin.audit(entry(ADMIN, null, Map.of("value", "2"))))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(auditRows()).isEmpty();
    }

    @Test
    void NSF18_secretsInTheValues_neverReachTheStoredEntry() {
        transaction.executeWithoutResult(status -> admin.audit(entry(
                ADMIN,
                Map.of("passwordHash", "$2a$10$abcdefghijklmnopqrstuv", "status", "ACTIVE"),
                Map.of("apiKey", "sk-live-123", "resetToken", "r3s3t", "status", "LOCKED"))));

        Map<String, Object> row = auditRows().getFirst();
        String stored = row.get("old_value") + " " + row.get("new_value");
        assertThat(stored)
                .doesNotContain("$2a$10$abcdefghijklmnopqrstuv", "sk-live-123", "r3s3t")
                .contains("passwordHash", "apiKey", "resetToken", AuditValueRedactor.REDACTED, "LOCKED");
    }

    /**
     * A snapshot passed as a record, with an array inside it, is serialised field by field; its secrets are redacted
     * as those of a map are.
     */
    @Test
    void NSF18_aRecordWithAPasswordHash_isRedacted() {
        AccountSnapshot snapshot = new AccountSnapshot(
                "trader@cryptopilot.invalid", "$2a$12$recordhashrecordhashre", new Session[] {new Session("s3ss10n")});

        transaction.executeWithoutResult(status -> admin.audit(entry(ADMIN, null, Map.of("account", snapshot))));

        String stored = String.valueOf(auditRows().getFirst().get("new_value"));
        assertThat(stored)
                .doesNotContain("$2a$12$recordhashrecordhashre", "s3ss10n")
                .contains("passwordHash", "sessionId", AuditValueRedactor.REDACTED, "trader@cryptopilot.invalid");
    }

    @Test
    void NSF18_anEntryWrittenDuringARequest_recordsTheClientAddress_andOneFromAJobRecordsNone() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.7");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        transaction.executeWithoutResult(status -> admin.audit(entry(ADMIN, null, Map.of("value", "2"))));
        RequestContextHolder.resetRequestAttributes();
        transaction.executeWithoutResult(status -> admin.audit(entry(null, null, Map.of("value", "3"))));

        assertThat(auditRows())
                .extracting(row -> row.get("user_id"), row -> row.get("ip_address"))
                .containsExactlyInAnyOrder(tuple(ADMIN, "203.0.113.7"), tuple(null, null));
    }

    /** Behind a trusted proxy the entry records the forwarded client, through the one resolution every reader uses. */
    @Test
    void NSF18_behindATrustedProxy_theEntryRecordsTheForwardedClient() throws Exception {
        recordThroughTheFilter("10.0.0.5", "203.0.113.7");

        assertThat(auditRows().getFirst()).containsEntry("ip_address", "203.0.113.7");
    }

    /** A forwarded address from a peer that is not a trusted proxy is ignored; the peer is recorded. */
    @Test
    void NSF18_aSpoofedForwardedAddress_isNotRecorded() throws Exception {
        recordThroughTheFilter("198.51.100.20", "203.0.113.7");

        assertThat(auditRows().getFirst()).containsEntry("ip_address", "198.51.100.20");
    }

    @Test
    void NSF18_aClientAddressLongerThanTheColumn_isCutToFit() {
        String scopedIpv6 = "fe80:0000:0000:0000:0204:61ff:fe9d:f156%ethernet-adapter-0";
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(scopedIpv6);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        transaction.executeWithoutResult(status -> admin.audit(entry(ADMIN, null, null)));

        assertThat(auditRows().getFirst()).containsEntry("ip_address", scopedIpv6.substring(0, 45));
    }

    private void recordThroughTheFilter(String peer, String forwardedFor) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(peer);
        request.addHeader("X-Forwarded-For", forwardedFor);
        new ClientAddressFilter(new TrustedProxyProperties(List.of("10.0.0.5")))
                .doFilter(request, new MockHttpServletResponse(), (filtered, response) -> {
                    RequestContextHolder.setRequestAttributes(
                            new ServletRequestAttributes((HttpServletRequest) filtered));
                    transaction.executeWithoutResult(status -> admin.audit(entry(ADMIN, null, Map.of("value", "2"))));
                });
    }

    private AuditEntry entry(UUID actor, Map<String, Object> oldValue, Map<String, Object> newValue) {
        return new AuditEntry(
                actor, AuditAction.CONFIGURATION_CHANGED, AuditedEntity.SYSTEM_SETTING, target, oldValue, newValue);
    }

    /** The shape of an account snapshot an administration command may audit. */
    private record AccountSnapshot(String email, String passwordHash, Session[] sessions) {}

    private record Session(String sessionId) {}

    private void changeSetting() {
        sql.sql("""
                        insert into system_setting (setting_key, setting_value, value_type, updated_at)
                        values (?, '2', 'INT', now())""").param(SETTING).update();
    }

    private boolean settingStored() {
        return sql.sql("select count(*) from system_setting where setting_key = ?")
                        .param(SETTING)
                        .query(Long.class)
                        .single()
                > 0;
    }

    private List<Map<String, Object>> auditRows() {
        return sql.sql("""
                        select user_id, action_code, entity_type, entity_id, old_value::text as old_value,
                               new_value::text as new_value, ip_address, created_at
                        from audit_log where entity_id = ?""").param(target).query().listOfRows();
    }
}
