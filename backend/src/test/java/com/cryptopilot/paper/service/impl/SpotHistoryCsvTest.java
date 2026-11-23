package com.cryptopilot.paper.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.paper.service.PaperOrderService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The cells of the Spot history CSV files (B7): quoting of RFC 4180, formula text neutralised (OWASP CSV injection),
 * and numbers left as numbers.
 */
class SpotHistoryCsvTest {

    @Test
    void B7_aCellWithASeparatorAQuoteOrALineBreak_isQuoted_andItsQuotesDoubled() {
        assertThat(SpotHistoryCsv.cell("a,b")).isEqualTo("\"a,b\"");
        assertThat(SpotHistoryCsv.cell("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(SpotHistoryCsv.cell("one\ntwo")).isEqualTo("\"one\ntwo\"");
        assertThat(SpotHistoryCsv.cell("plain")).isEqualTo("plain");
    }

    @Test
    void B7_textThatASpreadsheetWouldRunAsAFormula_getsAnApostrophe_butANegativeNumberDoesNot() {
        for (String formula : List.of("=1+1", "+1", "-cmd", "@sum", "\tx", "\rx")) {
            assertThat(SpotHistoryCsv.cell(formula)).as(formula).contains("'");
        }
        assertThat(SpotHistoryCsv.cell(new BigDecimal("-1.50"))).isEqualTo("-1.5");
        assertThat(SpotHistoryCsv.cell(-3L)).isEqualTo("-3");
    }

    @Test
    void B7_anAbsentValueIsAnEmptyCell_amountsArePlain_andInstantsAreUtc() {
        assertThat(SpotHistoryCsv.cell(null)).isEmpty();
        assertThat(SpotHistoryCsv.cell("")).isEmpty();
        assertThat(SpotHistoryCsv.cell(new BigDecimal("1E+2"))).isEqualTo("100");
        assertThat(SpotHistoryCsv.cell(new BigDecimal("0.00000000"))).isEqualTo("0");
        assertThat(SpotHistoryCsv.cell(Instant.parse("2026-11-23T01:02:03Z"))).isEqualTo("2026-11-23T01:02:03Z");
    }

    @Test
    void B7_anEmptyHistory_isTheHeaderAlone() {
        assertThat(SpotHistoryCsv.orders(List.of())).isEqualTo(SpotHistoryCsv.ORDER_HEADER + "\r\n");
        assertThat(SpotHistoryCsv.fills(List.of())).isEqualTo(SpotHistoryCsv.FILL_HEADER + "\r\n");
    }

    @Test
    void B7_anExportIsTruncated_onlyWhenItHoldsFewerRowsThanMatched() {
        assertThat(new PaperOrderService.Export("", 10_001, 10_000).truncated()).isTrue();
        assertThat(new PaperOrderService.Export("", 3, 3).truncated()).isFalse();
    }
}
