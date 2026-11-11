package com.cryptopilot.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cryptopilot.admin.model.enums.SystemEventCategory;
import com.cryptopilot.admin.model.enums.SystemEventSource;
import com.cryptopilot.admin.repository.SystemEventStore;
import com.cryptopilot.admin.service.impl.ExternalCallTracker;
import com.cryptopilot.admin.service.impl.SystemEventRecorder;
import com.cryptopilot.auth.config.JwtConfig;
import com.cryptopilot.common.exception.GlobalExceptionHandler;
import com.cryptopilot.common.exception.ProblemDetails;
import com.cryptopilot.market.client.BinanceRestClient;
import com.cryptopilot.support.TestcontainersConfig;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ProblemDetail;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

/**
 * SCR-44, the system log: a failure is written outside the failing transaction and a repeat is merged into its row; an
 * unexpected error of a request is recorded with the reference its caller got, a refused request is not; the log is
 * filtered and paged; the health panel weighs failures against answers; old rows are deleted after 90 days. Each test
 * names its operations with a tag of its own and removes them afterwards.
 *
 * <p>Rule: UC-52; NSF-17.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class SystemMonitorApiTest {

    private static final String API = "/api/v1/admin";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient sql;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private SystemEventRecorder recorder;

    @Autowired
    private SystemEventStore store;

    @Autowired
    private ExternalCallTracker tracker;

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private BinanceRestClient binance;

    @Autowired
    private GlobalExceptionHandler handler;

    @Autowired
    private com.cryptopilot.admin.job.SystemLogRetentionJob retention;

    private final String tag = "uc46-" + UUID.randomUUID().toString().substring(0, 8);
    private final List<UUID> accounts = new ArrayList<>();
    private UUID admin;
    private UUID trader;

    @BeforeEach
    void accounts() {
        admin = account("ADMIN");
        trader = account("TRADER");
    }

    @AfterEach
    void removeRows() {
        sql.sql("delete from system_event where operation like ?")
                .param("%" + tag + "%")
                .update();
        for (UUID account : accounts) {
            sql.sql("delete from user_account where user_id = ?").param(account).update();
        }
    }

    @Test
    void UC52_aRepeatWithinTenMinutes_isOneRowWithACount_anotherOperationIsAnotherRow() {
        recorder.record(
                SystemEventCategory.EXTERNAL_API_FAILURE,
                SystemEventSource.AI_SERVICE,
                "client." + tag,
                new RuntimeException("first\nline"),
                null);
        recorder.record(
                SystemEventCategory.EXTERNAL_API_FAILURE,
                SystemEventSource.AI_SERVICE,
                "client." + tag,
                new RuntimeException("second"),
                null);
        recorder.record(
                SystemEventCategory.EXTERNAL_API_FAILURE,
                SystemEventSource.AI_SERVICE,
                "other." + tag,
                new RuntimeException("third"),
                null);

        written();
        List<Map<String, Object>> rows = rows();
        assertThat(rows).hasSize(2);
        Map<String, Object> merged = rows.stream()
                .filter(row -> row.get("operation").equals("client." + tag))
                .findFirst()
                .orElseThrow();
        assertThat(merged.get("occurrences")).isEqualTo(2);
        assertThat(merged.get("message")).isEqualTo("second");
        assertThat(merged.get("error_type")).isEqualTo("java.lang.RuntimeException");
    }

    @Test
    void UC52_aRepeatAfterTheWindow_startsANewRow() {
        Instant first = Instant.parse("2031-01-01T00:00:00Z");
        Instant later = first.plus(Duration.ofMinutes(11));
        store.record(
                SystemEventCategory.SYSTEM_ERROR,
                SystemEventSource.SCHEDULED_JOB,
                "job." + tag,
                "E",
                "m",
                null,
                "f-" + tag,
                first,
                first.minus(Duration.ofMinutes(10)));
        store.record(
                SystemEventCategory.SYSTEM_ERROR,
                SystemEventSource.SCHEDULED_JOB,
                "job." + tag,
                "E",
                "m",
                null,
                "f-" + tag,
                later,
                later.minus(Duration.ofMinutes(10)));

        assertThat(rows()).hasSize(2);
    }

    /** Buckets are fixed from their first failure: a failure every minute for 35 minutes is four rows, not one. */
    @Test
    void UC52_aContinuousOutage_isOneRowPerTenMinutes() {
        Instant start = Instant.parse("2032-01-01T00:00:00Z");
        for (int minute = 0; minute < 35; minute++) {
            Instant at = start.plus(Duration.ofMinutes(minute));
            store.record(
                    SystemEventCategory.EXTERNAL_API_FAILURE,
                    SystemEventSource.AI_SERVICE,
                    "outage." + tag,
                    "E",
                    "m",
                    null,
                    "f-outage-" + tag,
                    at,
                    at.minus(Duration.ofMinutes(10)));
        }

        List<Map<String, Object>> rows = rows();
        assertThat(rows).hasSize(4);
        assertThat(rows.stream().mapToInt(row -> ((Number) row.get("occurrences")).intValue()))
                .containsExactlyInAnyOrder(10, 10, 10, 5);
    }

    /** The 24-hour figure of a source failing for three days counts the last day, give or take one bucket. */
    @Test
    void UC52_theDayFigure_ofAThreeDayOutage_countsOnlyTheLastDay() {
        Instant start = Instant.parse("2040-01-01T00:03:00Z");
        Instant now = start.plus(Duration.ofDays(3));
        for (Instant at = start; at.isBefore(now); at = at.plus(Duration.ofMinutes(5))) {
            store.record(
                    SystemEventCategory.EXTERNAL_API_FAILURE,
                    SystemEventSource.MAIL,
                    "smtp." + tag,
                    "E",
                    "m",
                    null,
                    "f-days-" + tag,
                    at,
                    at.minus(Duration.ofMinutes(10)));
        }

        long lastDay = store.failuresBySource(now.minus(Duration.ofDays(1)))
                .get(SystemEventSource.MAIL)
                .failuresSince();
        // 288 failures in the last 24 hours, plus at most the 2 of the bucket that began before it.
        assertThat(lastDay).isBetween(288L, 290L);
    }

    @Test
    void UC52_aFailureInATransactionThatRollsBack_isStillRecorded() {
        transaction.executeWithoutResult(status -> {
            recorder.record(
                    SystemEventCategory.SYSTEM_ERROR,
                    SystemEventSource.APPLICATION,
                    "rollback." + tag,
                    new IllegalStateException("x"),
                    null);
            status.setRollbackOnly();
        });

        written();
        assertThat(rows()).hasSize(1);
    }

    @Test
    void UC52_anUnexpectedErrorOfARequest_isRecordedWithItsReference() {
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(new MockHttpServletRequest("GET", "/api/v1/admin/" + tag)));
        try {
            ProblemDetail body = handler.handleUnexpectedException(new IllegalStateException("uc46 boom"))
                    .getBody();

            written();
            List<Map<String, Object>> rows = rows();
            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst().get("operation")).isEqualTo("GET /api/v1/admin/" + tag);
            assertThat(rows.getFirst().get("category")).isEqualTo("SYSTEM_ERROR");
            assertThat(rows.getFirst().get("source")).isEqualTo("APPLICATION");
            assertThat(rows.getFirst().get("message")).isEqualTo("uc46 boom");
            assertThat(rows.getFirst().get("reference"))
                    .isEqualTo(body.getProperties().get(ProblemDetails.TRACE_ID));
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    /** The failures of one endpoint share a row whatever ids it was called with (the route, not the URI). */
    @Test
    void UC52_twoFailuresOfOneRoute_withDifferentIds_areOneRow() {
        for (UUID plan : List.of(UUID.randomUUID(), UUID.randomUUID())) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/" + tag + "/" + plan);
            request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/" + tag + "/{planId}");
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
            try {
                handler.handleUnexpectedException(new IllegalStateException("uc46 route"));
            } finally {
                RequestContextHolder.resetRequestAttributes();
            }
        }

        written();
        List<Map<String, Object>> rows = rows();
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().get("operation")).isEqualTo("GET /api/v1/" + tag + "/{planId}");
        assertThat(rows.getFirst().get("occurrences")).isEqualTo(2);
    }

    /** No handler matched: each UUID or number in the path is an id, and the query is never kept. */
    @Test
    void UC52_aFailureWithoutARoute_hidesTheIdsOfItsPath() {
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/api/v1/" + tag + "/" + UUID.randomUUID() + "/items/42");
        request.setQueryString("email=someone@example.com");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            handler.handleUnexpectedException(new IllegalStateException("uc46 no route"));
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }

        written();
        assertThat(rows().getFirst().get("operation")).isEqualTo("POST /api/v1/" + tag + "/{id}/items/{id}");
    }

    @Test
    void UC52_theSystemLog_isFilteredAndPaged_andOnlyAdminsRead() throws Exception {
        recorder.record(
                SystemEventCategory.EXTERNAL_API_FAILURE,
                SystemEventSource.NEWS_SOURCE,
                "rss." + tag,
                new RuntimeException("feed down"),
                null);
        recorder.record(
                SystemEventCategory.SYSTEM_ERROR,
                SystemEventSource.SCHEDULED_JOB,
                "job." + tag,
                new RuntimeException("job failed"),
                "ref-" + tag);
        written();

        as(admin, get(API + "/system-events?source=NEWS_SOURCE&pageSize=100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.operation == 'rss." + tag + "')].message")
                        .value("feed down"));
        as(admin, get(API + "/system-events?category=SYSTEM_ERROR&source=NEWS_SOURCE"))
                .andExpect(jsonPath("$.total").value(0));
        as(admin, get(API + "/system-events?category=SYSTEM_ERROR&pageSize=100"))
                .andExpect(jsonPath("$.items[?(@.operation == 'job." + tag + "')].reference")
                        .value("ref-" + tag))
                .andExpect(
                        jsonPath("$.items[?(@.operation == 'rss." + tag + "')]").isEmpty());
        as(admin, get(API + "/system-events?pageSize=1"))
                .andExpect(jsonPath("$.items.length()").value(1));
        as(admin, get(API + "/system-events?from=2031-01-02&to=2031-01-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.from").value("MSG15"));
        as(admin, get(API + "/system-events?source=NOPE")).andExpect(status().isBadRequest());
        assertThat(sql.sql("select count(*) from system_event where operation like 'GET /api/v1/admin/system-events%'")
                        .query(Long.class)
                        .single())
                .as("a refused request is not a system error")
                .isZero();
        as(trader, get(API + "/system-events")).andExpect(status().isForbidden());
        as(trader, get(API + "/system-health")).andExpect(status().isForbidden());
        mvc.perform(get(API + "/system-events")).andExpect(status().isUnauthorized());
        mvc.perform(get(API + "/system-health")).andExpect(status().isUnauthorized());
    }

    @Test
    void UC52_theHealthPanel_listsTheExternalServices_andTheFeeds() throws Exception {
        recorder.record(
                SystemEventCategory.EXTERNAL_API_FAILURE,
                SystemEventSource.MAIL,
                "mail." + tag,
                new RuntimeException("smtp refused"),
                null);
        written();
        tracker.succeeded(SystemEventSource.MAIL);

        as(admin, get(API + "/system-health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recentWindowMinutes").value(15))
                .andExpect(jsonPath("$.staleAfterMinutes").value(17))
                .andExpect(jsonPath("$.systemLogDropped").value(0))
                .andExpect(jsonPath("$.services.length()").value(5))
                .andExpect(jsonPath("$.services[?(@.source == 'APPLICATION')]").isEmpty())
                .andExpect(jsonPath("$.services[?(@.source == 'MAIL')].status").value("DEGRADED"))
                .andExpect(
                        jsonPath("$.services[?(@.source == 'MAIL')].lastError").value("smtp refused"))
                .andExpect(jsonPath("$.feeds.length()").value(2))
                .andExpect(jsonPath("$.feeds[0].market").value("SPOT"));
    }

    @Test
    void UC52_theClientsOfExternalServices_areWatched() {
        assertThat(AopUtils.isAopProxy(binance)).isTrue();
    }

    @Test
    void NSF17_rowsLastSeenMoreThan90DaysAgo_areDeleted() {
        Instant now = Instant.now();
        insert("old." + tag, now.minus(Duration.ofDays(91)));
        insert("kept." + tag, now.minus(Duration.ofDays(89)));

        retention.purge();

        assertThat(rows()).extracting(row -> row.get("operation")).containsExactly("kept." + tag);
    }

    // ------------------------------------------------------------------------------------------

    /** Recording is handed to a writer thread: wait for it before reading. */
    private void written() {
        assertThat(recorder.awaitWritten(Duration.ofSeconds(10))).isTrue();
    }

    private List<Map<String, Object>> rows() {
        return sql.sql("select * from system_event where operation like ?")
                .param("%" + tag + "%")
                .query()
                .listOfRows();
    }

    private void insert(String operation, Instant lastSeen) {
        sql.sql("""
                        insert into system_event (system_event_id, category, source, operation, error_type,
                                                  fingerprint, occurrences, first_seen_at, last_seen_at)
                        values (?, 'SYSTEM_ERROR', 'SCHEDULED_JOB', ?, 'E', ?, 1, ?, ?)""")
                .params(
                        UUID.randomUUID(),
                        operation,
                        "%064d".formatted(0),
                        Timestamp.from(lastSeen),
                        Timestamp.from(lastSeen))
                .update();
    }

    private UUID account(String role) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        sql.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status, created_at,
                                                  updated_at)
                        values (?, ?, 'x', ?, 'ACTIVE', ?, ?)""").params(id, id + "@uc46.invalid", role, now, now).update();
        accounts.add(id);
        return id;
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
