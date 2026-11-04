package com.cryptopilot.market.model.enums;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.cryptopilot.market.LeverageTier;
import com.cryptopilot.market.service.impl.PairAdminServiceImplBrackets;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Every template passes the checks of a typed table (BR-26, BR-27), and the default never refuses the ceiling it is
 * chosen for.
 *
 * <p>Rule: UC-45, UC-46; BR-26, BR-27.
 */
class BracketTemplateTest {

    @ParameterizedTest
    @EnumSource(BracketTemplate.class)
    void everyTemplate_isAValidTable(BracketTemplate template) {
        assertThatCode(() -> PairAdminServiceImplBrackets.checked(template.rows()))
                .doesNotThrowAnyException();
        assertThat(template.rows().getFirst().maxLeverage()).isEqualTo(template.firstLeverage());
    }

    @Test
    void theMajorTemplate_hasBinancesMaintenanceAmounts() {
        List<LeverageTier> tiers = PairAdminServiceImplBrackets.checked(BracketTemplate.MAJOR.rows());

        assertThat(tiers).hasSize(12);
        assertThat(tiers.get(1).maintenanceAmount()).isEqualByComparingTo(new BigDecimal("50"));
        assertThat(tiers.get(4).maintenanceAmount()).isEqualByComparingTo(new BigDecimal("131450"));
        assertThat(tiers.getLast().maintenanceAmount()).isEqualByComparingTo(new BigDecimal("421481450"));
    }

    @ParameterizedTest(name = "{0} at {1}x → {2}")
    @CsvSource({
        "BTC, 20, MAJOR",
        "ETH, 125, MAJOR",
        "TRX, 7, ALTCOIN",
        "TRX, 75, ALTCOIN",
        "TRX, 76, MAJOR",
        "'', 20, ALTCOIN",
    })
    void theDefault_isMajorForBtcAndEth_andAlwaysAllowsTheCeiling(String coin, int ceiling, BracketTemplate expected) {
        BracketTemplate chosen = BracketTemplate.defaultFor(coin, ceiling);

        assertThat(chosen).isEqualTo(expected);
        assertThat(chosen.firstLeverage()).isGreaterThanOrEqualTo(ceiling);
    }
}
