package com.cryptopilot.drawing.controller;

import static org.assertj.core.api.Assertions.assertThat;
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
 * The chart layout API end to end: numbering with the smallest number free, names, copies, removal with the drawings,
 * ownership, the listed pair and the per-pair limit.
 *
 * <p>Every test writes its own Traders and pairs and {@link #removeRows()} deletes them.
 *
 * <p>Rule: D-149; T-119.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class ChartLayoutApiTest {

    private static final String LAYOUTS = "/api/v1/chart-layouts";
    private static final String DRAWINGS = "/api/v1/chart-drawings";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient sql;

    @Autowired
    private JwtEncoder jwtEncoder;

    private final List<UUID> accounts = new ArrayList<>();
    private UUID pair;
    private UUID otherPair;
    private UUID unlistedPair;

    @BeforeEach
    void insertPairs() {
        MarketTestData market = new MarketTestData(sql, Instant.now());
        String run = Long.toString(System.nanoTime() % 1_000_000_000L);
        pair = market.pair("CL" + run + "AUSDT", true, true, "TRADING", "TRADING", 0);
        otherPair = market.pair("CL" + run + "BUSDT", true, false, "TRADING", null, 1);
        unlistedPair = market.pair("CL" + run + "XUSDT", false, false, null, null, 2);
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
        List.of(pair, otherPair, unlistedPair).forEach(market::remove);
    }

    @Test
    void D149_newLayouts_takeTheNextNumbersAndAreListedWithTheirDrawings() throws Exception {
        UUID trader = trader();
        as(trader, get(LAYOUTS + "?pairId=" + pair), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        String first = id(as(trader, post(LAYOUTS), "{\"pairId\":\"%s\"}".formatted(pair))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pairId").value(pair.toString()))
                .andExpect(jsonPath("$.number").value(1))
                .andExpect(jsonPath("$.name").doesNotExist())
                .andExpect(jsonPath("$.drawings").value(0))
                .andExpect(jsonPath("$.createdAt").exists()));
        as(trader, post(LAYOUTS), "{\"pairId\":\"%s\",\"name\":\"  Wave count  \"}".formatted(pair))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.number").value(2))
                .andExpect(jsonPath("$.name").value("Wave count"));
        as(trader, post(LAYOUTS), "{\"pairId\":\"%s\"}".formatted(otherPair))
                .andExpect(jsonPath("$.number").value(1));
        drawOn(trader, first);
        drawOn(trader, first);

        as(trader, get(LAYOUTS + "?pairId=" + pair), null)
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(first))
                .andExpect(jsonPath("$[0].drawings").value(2))
                .andExpect(jsonPath("$[1].number").value(2))
                .andExpect(jsonPath("$[1].drawings").value(0));
    }

    @Test
    void D149_aRemovedLayout_freesItsNumberAndTakesItsDrawingsWithIt() throws Exception {
        UUID trader = trader();
        create(trader, pair);
        String second = create(trader, pair);
        create(trader, pair);
        drawOn(trader, second);

        as(trader, delete(LAYOUTS + "/" + second), null).andExpect(status().isNoContent());
        assertThat(drawings(trader)).isZero();

        as(trader, post(LAYOUTS), "{\"pairId\":\"%s\"}".formatted(pair))
                .andExpect(jsonPath("$.number").value(2));
        as(trader, post(LAYOUTS), "{\"pairId\":\"%s\"}".formatted(pair))
                .andExpect(jsonPath("$.number").value(4));
        as(trader, delete(LAYOUTS + "/" + second), null).andExpect(status().isNotFound());
    }

    @Test
    void D149_aRename_setsOrClearsTheName() throws Exception {
        UUID trader = trader();
        String id = create(trader, pair);
        drawOn(trader, id);

        as(trader, put(LAYOUTS + "/" + id), "{\"name\":\" Swing plan \"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Swing plan"))
                .andExpect(jsonPath("$.number").value(1))
                .andExpect(jsonPath("$.drawings").value(1));
        as(trader, put(LAYOUTS + "/" + id), "{\"name\":\"   \"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").doesNotExist());
        as(trader, put(LAYOUTS + "/" + id), "{\"name\":\"%s\"}".formatted("x".repeat(61)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").value("MSG01"));
    }

    @Test
    void D149_aCopy_startsWithItsOwnCopiesOfTheDrawings() throws Exception {
        UUID trader = trader();
        String source = create(trader, pair);
        String original = drawOn(trader, source);
        drawOn(trader, source);

        String copy = id(as(trader, post(LAYOUTS), "{\"pairId\":\"%s\",\"copyFrom\":\"%s\"}".formatted(pair, source))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.number").value(2))
                .andExpect(jsonPath("$.drawings").value(2)));
        as(trader, get(DRAWINGS + "?layoutId=" + copy), null)
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].type").value("TREND_LINE"))
                .andExpect(jsonPath("$[0].text").value("idea"));

        // The copies are rows of their own: removing the original leaves them.
        as(trader, delete(DRAWINGS + "/" + original), null).andExpect(status().isNoContent());
        as(trader, get(DRAWINGS + "?layoutId=" + copy), null)
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void D149_aCopyOfALayoutOnAnotherPairOrOfAnotherTrader_isRefused() throws Exception {
        UUID trader = trader();
        UUID stranger = trader();
        String elsewhere = create(trader, otherPair);
        String foreign = create(stranger, pair);

        as(trader, post(LAYOUTS), "{\"pairId\":\"%s\",\"copyFrom\":\"%s\"}".formatted(pair, elsewhere))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.copyFrom").value("MSG01"));
        as(trader, post(LAYOUTS), "{\"pairId\":\"%s\",\"copyFrom\":\"%s\"}".formatted(pair, foreign))
                .andExpect(status().isNotFound());
        assertThat(layouts(trader)).isEqualTo(1);
    }

    @Test
    void D149_anotherTradersLayout_isNeitherListedNorRenamedNorRemoved() throws Exception {
        UUID owner = trader();
        UUID stranger = trader();
        String id = create(owner, pair);

        as(stranger, get(LAYOUTS + "?pairId=" + pair), null)
                .andExpect(jsonPath("$.length()").value(0));
        as(stranger, put(LAYOUTS + "/" + id), "{\"name\":\"mine\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        as(stranger, delete(LAYOUTS + "/" + id), null).andExpect(status().isNotFound());
        as(stranger, post(LAYOUTS), "{\"pairId\":\"%s\"}".formatted(pair))
                .andExpect(jsonPath("$.number").value(1));

        as(owner, get(LAYOUTS + "?pairId=" + pair), null)
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").doesNotExist());
    }

    @Test
    void D149_anUnknownOrUnlistedPair_isNotFound() throws Exception {
        UUID trader = trader();
        as(trader, post(LAYOUTS), "{\"pairId\":\"%s\"}".formatted(unlistedPair)).andExpect(status().isNotFound());
        as(trader, post(LAYOUTS), "{\"pairId\":\"%s\"}".formatted(UUID.randomUUID()))
                .andExpect(status().isNotFound());
        as(trader, post(LAYOUTS), "{}").andExpect(status().isBadRequest());
        as(trader, get(LAYOUTS), null).andExpect(status().isBadRequest());
        assertThat(layouts(trader)).isZero();
    }

    @Test
    void D149_thePairHoldsAtMost10LayoutsOfOneTrader() throws Exception {
        UUID trader = trader();
        for (int i = 0; i < 10; i++) {
            create(trader, pair);
        }

        as(trader, post(LAYOUTS), "{\"pairId\":\"%s\"}".formatted(pair))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHART_LAYOUT_LIMIT_REACHED"))
                .andExpect(jsonPath("$.messageArgs[0]").value(10));
        as(trader, post(LAYOUTS), "{\"pairId\":\"%s\"}".formatted(otherPair)).andExpect(status().isCreated());
        assertThat(layouts(trader)).isEqualTo(11);
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    private String create(UUID trader, UUID onPair) throws Exception {
        return id(as(trader, post(LAYOUTS), "{\"pairId\":\"%s\"}".formatted(onPair))
                .andExpect(status().isCreated()));
    }

    private String drawOn(UUID trader, String layout) throws Exception {
        return id(as(trader, post(DRAWINGS), """
                        {"layoutId":"%s","type":"TREND_LINE","text":"idea",
                         "points":[{"time":"2026-11-10T00:00:00Z","price":"66000"},
                                   {"time":"2026-11-20T00:00:00Z","price":"70000"}],
                         "style":{"lineColor":"#2962ff","lineWidth":2,"lineStyle":"SOLID"}}""".formatted(layout)).andExpect(status().isCreated()));
    }

    private static String id(ResultActions created) throws Exception {
        return JsonPath.read(created.andReturn().getResponse().getContentAsString(), "$.id");
    }

    private long layouts(UUID trader) {
        return sql.sql("select count(*) from chart_layout where user_id = ?")
                .param(trader)
                .query(Long.class)
                .single();
    }

    private long drawings(UUID trader) {
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
