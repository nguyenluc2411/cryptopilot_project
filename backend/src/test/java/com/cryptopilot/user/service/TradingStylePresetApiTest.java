package com.cryptopilot.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cryptopilot.auth.config.JwtConfig;
import com.cryptopilot.market.MarketTestData;
import com.cryptopilot.market.SetupStyleSource;
import com.cryptopilot.support.TestcontainersConfig;
import com.cryptopilot.user.model.enums.UserRole;
import com.cryptopilot.user.service.impl.ProfileSetupStyleSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
 * The analysis of UC-10 read by Traders whose profiles choose different trading styles: the setup score comes back
 * under the preset the profile selects, and under DAY_TRADING when the profile selects none, when the account has no
 * profile, and for a Guest.
 *
 * <p>Every test rolls back.
 *
 * <p>Rule: BR-13, UC-10; SRS 3.2.5; D-53.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@Transactional
class TradingStylePresetApiTest {

    private static final String ANALYSIS = "/api/v1/analysis/spot/BTCUSDT";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient sql;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private SetupStyleSource styleSource;

    @BeforeEach
    void enablePair() {
        new MarketTestData(sql, Instant.now()).pair("BTCUSDT", true, false, "TRADING", null, 0);
    }

    /** The market module reads the preset through the profile, not through a default. */
    @Test
    void BR13_theStyleSourceOfTheApplication_isTheProfile() {
        assertThat(styleSource).isInstanceOf(ProfileSetupStyleSource.class);
    }

    @ParameterizedTest(name = "{0} reads {1}")
    @CsvSource({"SCALPING, SCALPING", "DAY, DAY_TRADING", "SWING, SWING", "POSITION, SWING", ", DAY_TRADING"})
    void BR13_theAnalysis_isReadUnderThePresetOfTheCallersTradingStyle(String tradingStyle, String preset)
            throws Exception {
        UUID trader = account();
        profile(trader, tradingStyle);

        mvc.perform(get(ANALYSIS).header(HttpHeaders.AUTHORIZATION, bearer(trader)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.setupScore.preset").value(preset));
    }

    @Test
    void BR13_aTraderWithoutAProfile_readsDayTrading() throws Exception {
        UUID trader = account();

        mvc.perform(get(ANALYSIS).header(HttpHeaders.AUTHORIZATION, bearer(trader)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.setupScore.preset").value("DAY_TRADING"));
    }

    private UUID account() {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        sql.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status, created_at,
                                                  updated_at)
                        values (?, ?, 'x', 'TRADER', 'ACTIVE', ?, ?)""").params(id, id + "@t109.invalid", now, now).update();
        return id;
    }

    private void profile(UUID userId, String tradingStyle) {
        Timestamp now = Timestamp.from(Instant.now());
        sql.sql("""
                        insert into user_profile (user_id, display_name, trading_style, created_at, updated_at)
                        values (?, 'Trader', ?, ?, ?)""").params(userId, tradingStyle, now, now).update();
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
