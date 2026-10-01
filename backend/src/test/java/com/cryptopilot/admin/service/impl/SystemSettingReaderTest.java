package com.cryptopilot.admin.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cryptopilot.admin.SystemSettingApi;
import com.cryptopilot.support.TestcontainersConfig;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/** The system settings other modules read, against the rows V3 seeded. */
@SpringBootTest
@Import(TestcontainersConfig.class)
@Transactional
class SystemSettingReaderTest {

    @Autowired
    private SystemSettingApi settings;

    @Autowired
    private JdbcClient sql;

    @Test
    void BR29_theSeededWarningThresholds_areReadAsDecimals() {
        assertThat(settings.decimal("WARN_LOW_RR_RATIO")).isEqualByComparingTo(new BigDecimal("1.5"));
        assertThat(settings.decimal("WARN_WIDE_STOP_LOSS_PERCENT")).isEqualByComparingTo(BigDecimal.TEN);
        assertThat(settings.decimal("WARN_HIGH_FUNDING_RATE")).isEqualByComparingTo(new BigDecimal("0.001"));
    }

    @Test
    void aSettingThatIsNotStored_isADeploymentDefect() {
        assertThatThrownBy(() -> settings.decimal("NOT_A_SETTING"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not stored");
    }

    @Test
    void aSettingOfAnotherType_isNotReadAsADecimal() {
        sql.sql("""
                        insert into system_setting (setting_key, setting_value, value_type, updated_at)
                        values ('T039_INT_SETTING', '5', 'INT', now())""").update();

        assertThatThrownBy(() -> settings.decimal("T039_INT_SETTING"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INT");
    }
}
