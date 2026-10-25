package com.cryptopilot.news.calculator;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * What a summary must be before it is stored (NSF-11): present, at most 60 words, no advice to trade, no invented
 * figure; and a summary whose only fault is its length is cut rather than lost.
 *
 * <p>Rule: NSF-11; the hard scope boundary (no investment advice).
 */
class SummaryCheckTest {

    private static final String ARTICLE = "Bitcoin fell 3.5% to $82,000 as ETF outflows reached $120 million.";

    @Test
    void NSF11_aSoundSummary_passes() {
        assertThat(SummaryCheck.problem("Bitcoin giảm 3.5% về $82,000 khi ETF rút $120 million.", ARTICLE, 60))
                .isEmpty();
    }

    @Test
    void NSF11_anEmptySummary_isRefused() {
        assertThat(SummaryCheck.problem("  ", ARTICLE, 60)).contains("the summary is empty");
        assertThat(SummaryCheck.problem(null, ARTICLE, 60)).contains("the summary is empty");
    }

    @Test
    void NSF11_aSummaryOverTheWordLimit_isRefused_withTheCount() {
        String long61 = "từ ".repeat(61).strip();

        assertThat(SummaryCheck.problem(long61, ARTICLE, 60)).contains("the summary has 61 words, more than 60");
        assertThat(SummaryCheck.problem("từ ".repeat(60).strip(), ARTICLE, 60)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "Nhà đầu tư nên mua Bitcoin lúc này.",
                "Bạn hãy bán ETH ngay.",
                "Mua ngay trước khi giá tăng.",
                "Chuyên gia khuyến nghị nắm giữ BTC.",
                "Traders should buy the dip.",
                "Sell now, analysts say.",
                "It is time to buy.",
                "We recommend selling altcoins."
            })
    void NSF11_anyInstructionToTrade_isRefused(String summary) {
        assertThat(SummaryCheck.problem(summary, ARTICLE, 60))
                .hasValueSatisfying(reason -> assertThat(reason).startsWith("the summary tells the reader to trade"));
    }

    @Test
    void NSF11_reportingThatPeopleBoughtOrSold_isNotAdvice() {
        assertThat(SummaryCheck.problem("Nhà đầu tư tổ chức đã bán ra; người mua quay lại.", ARTICLE, 60))
                .isEmpty();
        assertThat(SummaryCheck.problem("Whales bought while retail sold.", ARTICLE, 60))
                .isEmpty();
    }

    @Test
    void NSF11_anInventedFigure_isRefused_byName() {
        assertThat(SummaryCheck.problem("Bitcoin về $90,000.", ARTICLE, 60))
                .contains("these figures are not in the article: 90,000");
    }

    @Test
    void NSF11_aSummaryWhoseOnlyFaultIsItsLength_isCutWithAMark() {
        String tooLong = "Bitcoin giảm 3.5% về $82,000, " + "thị trường thận trọng ".repeat(25);

        assertThat(SummaryCheck.onlyTooLong(tooLong, ARTICLE, 60)).isTrue();
        String cut = SummaryCheck.cut(tooLong, 60);
        assertThat(SummaryCheck.words(cut)).isEqualTo(60);
        assertThat(cut).endsWith("…").doesNotEndWith(",…");
        assertThat(SummaryCheck.cut("Câu ngắn.", 60)).isEqualTo("Câu ngắn.");
        assertThat(SummaryCheck.cut("một hai ba. bốn", 3)).isEqualTo("một hai ba.");
    }

    @Test
    void NSF11_aLongSummaryWithAnotherFault_isNotSavedByCutting() {
        String inventedEarly = "Bitcoin về $90,000, " + "thị trường thận trọng ".repeat(25);

        assertThat(SummaryCheck.onlyTooLong(inventedEarly, ARTICLE, 60)).isFalse();
        assertThat(SummaryCheck.onlyTooLong("ngắn", ARTICLE, 60)).isFalse();
        assertThat(SummaryCheck.onlyTooLong(null, ARTICLE, 60)).isFalse();
        assertThat(SummaryCheck.words("")).isZero();
    }
}
