package com.cryptopilot.drawing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cryptopilot.auth.config.JwtConfig;
import com.cryptopilot.market.MarketTestData;
import com.cryptopilot.support.TestcontainersConfig;
import com.cryptopilot.user.model.enums.UserRole;
import com.jayway.jsonpath.JsonPath;
import java.sql.SQLException;
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
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
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
 * The chart drawing API end to end: create, list, update, delete one and delete all, ownership, the checks of the
 * anchors and the style, the optimistic version and the per-layout limit.
 *
 * <p>Every test writes its own Traders, pairs and layouts and {@link #removeRows()} deletes them.
 *
 * <p>Rule: D-149 (2)(4)(5); T-119.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class ChartDrawingApiTest {

    private static final String DRAWINGS = "/api/v1/chart-drawings";

    private static final String STYLE = """
            {"lineColor":"#2962ff","lineWidth":2,"lineStyle":"SOLID","extendLeft":false,"extendRight":true}""";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient sql;

    @Autowired
    private JwtEncoder jwtEncoder;

    private final List<UUID> accounts = new ArrayList<>();
    private UUID pair;
    private int layouts;

    @BeforeEach
    void insertPairs() {
        MarketTestData market = new MarketTestData(sql, Instant.now());
        String run = Long.toString(System.nanoTime() % 1_000_000_000L);
        pair = market.pair("CD" + run + "AUSDT", true, true, "TRADING", "TRADING", 0);
    }

    @AfterEach
    void removeRows() {
        for (UUID account : accounts) {
            sql.sql("delete from chart_drawing where user_id = ?")
                    .param(account)
                    .update();
            sql.sql("delete from chart_layout where user_id = ?").param(account).update();
            sql.sql("delete from user_account where user_id = ?").param(account).update();
        }
        MarketTestData market = new MarketTestData(sql, Instant.now());
        market.remove(pair);
    }

    // ------------------------------------------------------------------------------------------
    // Create, list, update, delete
    // ------------------------------------------------------------------------------------------

    @Test
    void D149_aTrendLine_isStoredAndListedWithTheValuesSent() throws Exception {
        UUID trader = trader();
        UUID layout = layout(trader, pair);
        UUID otherLayout = layout(trader, pair);
        as(trader, post(DRAWINGS), """
                        {"layoutId":"%s","type":"TREND_LINE",
                         "points":[{"time":"2026-11-10T00:00:00Z","price":"66000.50"},
                                   {"time":"2026-11-20T00:00:00Z","price":70000}],
                         "style":%s,"text":"  breakout  "}""".formatted(layout, STYLE))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.layoutId").value(layout.toString()))
                .andExpect(jsonPath("$.type").value("TREND_LINE"))
                .andExpect(jsonPath("$.points[0].time").value("2026-11-10T00:00:00Z"))
                .andExpect(jsonPath("$.points[0].price").value("66000.5"))
                .andExpect(jsonPath("$.points[1].price").value("70000"))
                .andExpect(jsonPath("$.style.lineColor").value("#2962FF"))
                .andExpect(jsonPath("$.style.extendRight").value(true))
                .andExpect(jsonPath("$.style.fillColor").doesNotExist())
                .andExpect(jsonPath("$.text").value("breakout"))
                .andExpect(jsonPath("$.locked").value(false))
                .andExpect(jsonPath("$.hidden").value(false))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.createdAt").exists());

        as(trader, get(DRAWINGS + "?layoutId=" + layout), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].type").value("TREND_LINE"))
                .andExpect(jsonPath("$[0].points[1].time").value("2026-11-20T00:00:00Z"));
        as(trader, get(DRAWINGS + "?layoutId=" + otherLayout), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void D149_aZone_keepsItsFillAndAFibonacciItsLevels() throws Exception {
        UUID trader = trader();
        UUID layout = layout(trader, pair);
        as(trader, post(DRAWINGS), """
                        {"layoutId":"%s","type":"RECTANGLE","locked":true,"hidden":true,
                         "points":[{"time":"2026-11-10T00:00:00Z","price":"64000"},
                                   {"time":"2026-11-12T00:00:00Z","price":"65000"}],
                         "style":{"lineColor":"#26a69a","lineWidth":1,"lineStyle":"DASHED","fillColor":"#26a69a33",
                                  "extendLeft":false,"extendRight":true,"fontSize":14}}""".formatted(layout))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.style.fillColor").value("#26A69A33"))
                .andExpect(jsonPath("$.style.lineStyle").value("DASHED"))
                .andExpect(jsonPath("$.style.fontSize").value(14))
                .andExpect(jsonPath("$.locked").value(true))
                .andExpect(jsonPath("$.hidden").value(true))
                .andExpect(jsonPath("$.text").doesNotExist());

        as(trader, post(DRAWINGS), """
                        {"layoutId":"%s","type":"FIB_RETRACEMENT",
                         "points":[{"time":"2026-11-10T00:00:00Z","price":"60000"},
                                   {"time":"2026-11-12T00:00:00Z","price":"70000"}],
                         "style":{"lineColor":"#787b86","lineWidth":1,"lineStyle":"SOLID",
                                  "fibLevels":[0, 0.382, "0.6180", 1]}}""".formatted(layout))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.style.fibLevels.length()").value(4))
                .andExpect(jsonPath("$.style.fibLevels[2]").value("0.618"));
    }

    @Test
    void D149_aPositionAndAText_areStoredWhenTheirAnchorsFit() throws Exception {
        UUID trader = trader();
        UUID layout = layout(trader, pair);
        as(trader, post(DRAWINGS), position(layout, "LONG_POSITION", "66000", "64000", "70000"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.points.length()").value(3));
        as(trader, post(DRAWINGS), position(layout, "SHORT_POSITION", "66000", "68000", "62000"))
                .andExpect(status().isCreated());
        as(trader, post(DRAWINGS), """
                        {"layoutId":"%s","type":"TEXT","text":"FOMC tonight",
                         "points":[{"time":"2026-11-10T00:00:00Z","price":"66000"}],"style":%s}""".formatted(layout, STYLE))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.text").value("FOMC tonight"));
    }

    @Test
    void D149_aPatternOfSevenAnchors_isStoredInItsOrder() throws Exception {
        UUID trader = trader();
        UUID layout = layout(trader, pair);
        StringBuilder points = new StringBuilder();
        for (int i = 0; i < 7; i++) {
            points.append(i == 0 ? "" : ",")
                    .append("{\"time\":\"2026-11-1%dT00:00:00Z\",\"price\":\"%d\"}"
                            .formatted(i, i % 2 == 0 ? 60000 : 62000 + i * 100));
        }
        as(trader, post(DRAWINGS), """
                        {"layoutId":"%s","type":"HEAD_AND_SHOULDERS","points":[%s],"style":%s}""".formatted(layout, points, STYLE))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("HEAD_AND_SHOULDERS"))
                .andExpect(jsonPath("$.points.length()").value(7))
                .andExpect(jsonPath("$.points[6].time").value("2026-11-16T00:00:00Z"));
        as(trader, post(DRAWINGS), """
                        {"layoutId":"%s","type":"XABCD_PATTERN","points":[%s],"style":%s}""".formatted(layout, points, STYLE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.points").value("MSG01"));
    }

    @Test
    void D149_anUpdate_replacesTheDrawingAndMovesItsVersion() throws Exception {
        UUID trader = trader();
        UUID layout = layout(trader, pair);
        String id = create(trader, layout);

        as(trader, put(DRAWINGS + "/" + id), """
                        {"version":0,"locked":true,"text":"moved",
                         "points":[{"time":"2026-11-11T00:00:00Z","price":"65000"},
                                   {"time":"2026-11-21T00:00:00Z","price":"71000"}],
                         "style":{"lineColor":"#f23645","lineWidth":3,"lineStyle":"DOTTED"}}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.locked").value(true))
                .andExpect(jsonPath("$.text").value("moved"))
                .andExpect(jsonPath("$.points[0].price").value("65000"))
                .andExpect(jsonPath("$.style.lineColor").value("#F23645"))
                .andExpect(jsonPath("$.style.lineWidth").value(3));

        // The version the chart read is now stale: nothing is written.
        as(trader, put(DRAWINGS + "/" + id), update(0, "1", "2"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DATA_CONFLICT"));
        as(trader, get(DRAWINGS + "?layoutId=" + layout), null)
                .andExpect(jsonPath("$[0].text").value("moved"));
    }

    @Test
    void D149_aDrawing_isRemovedAloneOrWithEveryDrawingOfTheLayout() throws Exception {
        UUID trader = trader();
        UUID layout = layout(trader, pair);
        UUID otherLayout = layout(trader, pair);
        String first = create(trader, layout);
        create(trader, layout);
        create(trader, otherLayout);

        as(trader, delete(DRAWINGS + "/" + first), null).andExpect(status().isNoContent());
        as(trader, get(DRAWINGS + "?layoutId=" + layout), null)
                .andExpect(jsonPath("$.length()").value(1));

        as(trader, delete(DRAWINGS + "?layoutId=" + layout), null).andExpect(status().isNoContent());
        as(trader, get(DRAWINGS + "?layoutId=" + layout), null)
                .andExpect(jsonPath("$.length()").value(0));
        as(trader, get(DRAWINGS + "?layoutId=" + otherLayout), null)
                .andExpect(jsonPath("$.length()").value(1));
        as(trader, delete(DRAWINGS + "/" + first), null).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------------------------------
    // Ownership
    // ------------------------------------------------------------------------------------------

    @Test
    void D149_anotherTradersDrawing_isNeitherListedNorChangedNorRemoved() throws Exception {
        UUID owner = trader();
        UUID stranger = trader();
        UUID layout = layout(owner, pair);
        String id = create(owner, layout);

        as(stranger, get(DRAWINGS + "?layoutId=" + layout), null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        as(stranger, post(DRAWINGS), trendLine(layout))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        as(stranger, put(DRAWINGS + "/" + id), update(0, "1", "2"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        as(stranger, delete(DRAWINGS + "/" + id), null).andExpect(status().isNotFound());
        as(stranger, delete(DRAWINGS + "?layoutId=" + layout), null).andExpect(status().isNotFound());

        as(owner, get(DRAWINGS + "?layoutId=" + layout), null)
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].version").value(0));
    }

    // ------------------------------------------------------------------------------------------
    // Checks
    // ------------------------------------------------------------------------------------------

    @Test
    void D149_anUnknownLayout_isNotFound() throws Exception {
        UUID trader = trader();
        as(trader, post(DRAWINGS), trendLine(UUID.randomUUID())).andExpect(status().isNotFound());
        assertThat(count(trader)).isZero();
    }

    /**
     * A layout removed in another tab answers 404 like a missing one, so the chart learns it is gone.
     *
     * <p>Reference: OWASP Foundation (2023). <i>API Security Top 10</i>, API1:2023 "Broken Object Level
     * Authorization".
     */
    @Test
    void D149_aRemovedLayout_isNotFoundWhenListedOrCleared() throws Exception {
        UUID trader = trader();
        UUID layout = layout(trader, pair);
        create(trader, layout);
        as(trader, delete("/api/v1/chart-layouts/" + layout), null).andExpect(status().isNoContent());

        as(trader, get(DRAWINGS + "?layoutId=" + layout), null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        as(trader, delete(DRAWINGS + "?layoutId=" + layout), null).andExpect(status().isNotFound());
    }

    /**
     * The composite foreign key refuses a drawing whose owner is not its layout's owner.
     *
     * <p>Reference: PostgreSQL Global Development Group. <i>PostgreSQL 16 Documentation</i>, §5.4.5 "Foreign Keys".
     */
    @Test
    void D149_aDrawingOwnedByAnotherTraderThanItsLayout_isRefusedByTheDatabase() {
        UUID owner = trader();
        UUID stranger = trader();
        UUID layout = layout(owner, pair);

        assertThatThrownBy(() -> sql.sql("""
                                insert into chart_drawing (drawing_id, user_id, layout_id, drawing_type, points, style,
                                                           created_at, updated_at)
                                values (?, ?, ?, 'HORIZONTAL_LINE', '[]'::jsonb, '{}'::jsonb, ?, ?)""")
                        .params(UUID.randomUUID(), stranger, layout, now(), now())
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(((SQLException) NestedExceptionUtils.getMostSpecificCause(e)).getSQLState())
                        .isEqualTo("23503"));
        assertThat(count(stranger)).isZero();
    }

    @Test
    void D149_anchorsThatDoNotFitTheType_areRefusedField() throws Exception {
        UUID trader = trader();
        UUID layout = layout(trader, pair);
        as(trader, post(DRAWINGS), """
                        {"layoutId":"%s","type":"TREND_LINE",
                         "points":[{"time":"2026-11-10T00:00:00Z","price":"66000"}],"style":%s}""".formatted(layout, STYLE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.points").value("MSG01"));
        as(trader, post(DRAWINGS), position(layout, "LONG_POSITION", "66000", "67000", "70000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.points").value("MSG01"));
        as(trader, post(DRAWINGS), """
                        {"layoutId":"%s","type":"TEXT","text":"   ",
                         "points":[{"time":"2026-11-10T00:00:00Z","price":"66000"}],"style":%s}""".formatted(layout, STYLE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.text").value("MSG01"));

        String id = create(trader, layout);
        as(trader, put(DRAWINGS + "/" + id), """
                        {"version":0,"points":[{"time":"2026-11-10T00:00:00Z","price":"1"}],"style":%s}""".formatted(STYLE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.points").value("MSG01"));
        assertThat(count(trader)).isEqualTo(1);
    }

    @Test
    void D149_aStyleOrPriceOutsideItsBounds_isRefusedField() throws Exception {
        UUID trader = trader();
        UUID layout = layout(trader, pair);
        as(trader, post(DRAWINGS), """
                        {"layoutId":"%s","type":"HORIZONTAL_LINE",
                         "points":[{"time":"2026-11-10T00:00:00Z","price":"0"}],
                         "style":{"lineColor":"red","lineWidth":9,"lineStyle":"SOLID","fillColor":"#12345",
                                  "fontSize":11,"fibLevels":[11]}}""".formatted(layout))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['points[0].price']").value("MSG15"))
                .andExpect(jsonPath("$.errors['style.lineColor']").value("MSG01"))
                .andExpect(jsonPath("$.errors['style.lineWidth']").value("MSG01"))
                .andExpect(jsonPath("$.errors['style.fillColor']").value("MSG01"))
                .andExpect(jsonPath("$.errors['style.fontSize']").value("MSG01"));
        as(trader, post(DRAWINGS), """
                        {"layoutId":"%s","type":"HORIZONTAL_LINE","points":[],"style":%s}""".formatted(layout, STYLE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.points").value("MSG01"));
        as(trader, get(DRAWINGS), null).andExpect(status().isBadRequest());
        assertThat(count(trader)).isZero();
    }

    @Test
    void D149_aLayoutHoldsAtMost200Drawings() throws Exception {
        UUID trader = trader();
        UUID layout = layout(trader, pair);
        UUID otherLayout = layout(trader, pair);
        for (int i = 0; i < 199; i++) {
            sql.sql("""
                            insert into chart_drawing (drawing_id, user_id, layout_id, drawing_type, points, style,
                                                       created_at, updated_at)
                            values (?, ?, ?, 'HORIZONTAL_LINE', '[]'::jsonb, '{}'::jsonb, ?, ?)""").params(UUID.randomUUID(), trader, layout, now(), now()).update();
        }
        as(trader, post(DRAWINGS), trendLine(layout)).andExpect(status().isCreated());

        as(trader, post(DRAWINGS), trendLine(layout))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHART_DRAWING_LIMIT_REACHED"))
                .andExpect(jsonPath("$.messageArgs[0]").value(200));
        as(trader, post(DRAWINGS), trendLine(otherLayout)).andExpect(status().isCreated());
        assertThat(count(trader)).isEqualTo(201);
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    private String create(UUID trader, UUID layout) throws Exception {
        String body = as(trader, post(DRAWINGS), trendLine(layout))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private static String trendLine(UUID layout) {
        return """
                {"layoutId":"%s","type":"TREND_LINE",
                 "points":[{"time":"2026-11-10T00:00:00Z","price":"66000"},
                           {"time":"2026-11-20T00:00:00Z","price":"70000"}],"style":%s}""".formatted(layout, STYLE);
    }

    /** A layout of the Trader on the pair, written directly: the layout API has its own test. */
    private UUID layout(UUID trader, UUID onPair) {
        UUID id = UUID.randomUUID();
        sql.sql("""
                        insert into chart_layout (layout_id, user_id, pair_id, seq_no, created_at, updated_at)
                        values (?, ?, ?, ?, ?, ?)""").params(id, trader, onPair, ++layouts, now(), now()).update();
        return id;
    }

    private static String position(UUID layout, String type, String entry, String stop, String target) {
        return """
                {"layoutId":"%s","type":"%s",
                 "points":[{"time":"2026-11-10T00:00:00Z","price":"%s"},
                           {"time":"2026-11-15T00:00:00Z","price":"%s"},
                           {"time":"2026-11-15T00:00:00Z","price":"%s"}],"style":%s}""".formatted(layout, type, entry, stop, target, STYLE);
    }

    private static String update(long version, String first, String second) {
        return """
                {"version":%d,
                 "points":[{"time":"2026-11-10T00:00:00Z","price":"%s"},
                           {"time":"2026-11-20T00:00:00Z","price":"%s"}],"style":%s}""".formatted(version, first, second, STYLE);
    }

    private long count(UUID trader) {
        return sql.sql("select count(*) from chart_drawing where user_id = ?")
                .param(trader)
                .query(Long.class)
                .single();
    }

    private ResultActions as(UUID userId, MockHttpServletRequestBuilder request, String json) throws Exception {
        request.header(HttpHeaders.AUTHORIZATION, bearer(userId));
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mvc.perform(request);
    }

    private UUID trader() {
        UUID id = UUID.randomUUID();
        sql.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status, created_at,
                                                  updated_at)
                        values (?, ?, 'x', 'TRADER', 'ACTIVE', ?, ?)""").params(id, id + "@t119.invalid", now(), now()).update();
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

    private static Timestamp now() {
        return Timestamp.from(Instant.now());
    }
}
