package com.cryptopilot.user.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.support.TestcontainersConfig;
import com.cryptopilot.user.PlanDefaults;
import com.cryptopilot.user.RiskProfileApi;
import com.cryptopilot.user.model.enums.RiskProfile;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/** What a new plan starts from, read through the user module's API (SRS 3.2.5, BR-66). */
@SpringBootTest
@Import(TestcontainersConfig.class)
@Transactional
class PlanDefaultsTest {

    @Autowired
    private RiskProfileApi riskProfiles;

    @Autowired
    private JdbcClient sql;

    @Test
    void SRS325_theProfilesDefaultsAndRiskProfile_areThePlanDefaults() {
        UUID trader = account();
        sql.sql("""
                        insert into user_profile (user_id, display_name, default_capital, default_risk_percent,
                                                  risk_profile, created_at, updated_at)
                        values (?, 'Trader', 2500, 1.5, 'AGGRESSIVE', ?, ?)""").params(trader, now(), now()).update();

        PlanDefaults defaults = riskProfiles.planDefaultsOf(trader);

        assertThat(defaults.riskProfile().profile()).isEqualTo(RiskProfile.AGGRESSIVE);
        assertThat(defaults.defaultCapital()).isEqualByComparingTo(new BigDecimal("2500"));
        assertThat(defaults.defaultRiskPercent()).isEqualByComparingTo(new BigDecimal("1.5"));
    }

    @Test
    void D64_anAccountWithoutAProfile_startsConservativeWithoutDefaults() {
        PlanDefaults defaults = riskProfiles.planDefaultsOf(account());

        assertThat(defaults.riskProfile().profile()).isEqualTo(RiskProfile.CONSERVATIVE);
        assertThat(defaults.defaultCapital()).isNull();
        assertThat(defaults.defaultRiskPercent()).isNull();
    }

    private UUID account() {
        UUID id = UUID.randomUUID();
        sql.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status, created_at,
                                                  updated_at)
                        values (?, ?, 'x', 'TRADER', 'ACTIVE', ?, ?)""").params(id, id + "@t039.invalid", now(), now()).update();
        return id;
    }

    private static Timestamp now() {
        return Timestamp.from(Instant.now());
    }
}
