package com.cryptopilot.news.calculator;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * No invented figure in a summary (NSF-11): every figure must be one of the article's, read in either separator
 * convention, with scale words expanded and rounding to two significant figures tolerated; dates and times are not
 * figures. The cases of the feasibility run, plus the one the first live call showed: a model writing {@code $81,000}
 * the Vietnamese way.
 *
 * <p>Rule: NSF-11; BR-51.
 */
class NumericGuardTest {

    private static final String ARTICLE = "Bitcoin fell 3.5% to $82,000 after touching $85,000 earlier. "
            + "Analysts noted ETF outflows of $120 million over the week.";

    @Test
    void NSF11_figuresOfTheArticle_areSupported() {
        assertThat(NumericGuard.unsupported(
                        "Bitcoin giảm 3.5% về $82,000 sau khi chạm $85,000; ETF rút ròng $120 million.", ARTICLE))
                .isEmpty();
    }

    @Test
    void NSF11_anInventedFigure_isNamed() {
        assertThat(NumericGuard.unsupported("Bitcoin chạm $90,000 rồi giảm 3.5%.", ARTICLE))
                .containsExactly("90,000");
    }

    @Test
    void NSF11_vietnameseSeparators_readAsTheSameValues() {
        String article = "BTC fell below $81,000 and recovered to around $81,600; ETFs saw $635 million of outflows,"
                + " the largest since June, and 2.5% of supply moved.";

        assertThat(NumericGuard.unsupported(
                        "Bitcoin giảm xuống dưới $81.000 và phục hồi về khoảng $81.600; ETF rút ròng $635 triệu,"
                                + " 2,5% nguồn cung dịch chuyển.",
                        article))
                .isEmpty();
        assertThat(NumericGuard.unsupported("Bitcoin về $79.000.", article)).containsExactly("79.000");
    }

    @Test
    void NSF11_aReformattedOrRoundedMagnitude_isSupported_butAWrongOneIsNot() {
        String article = "Bitcoin hit $83.3K after falling from $85,000.";

        assertThat(NumericGuard.unsupported("BTC quanh $83,000, giảm từ $85,000.", article))
                .isEmpty();
        assertThat(NumericGuard.unsupported("BTC chạm $88,000.", ARTICLE)).containsExactly("88,000");
    }

    @Test
    void NSF11_scaleWordsAndSuffixes_areExpanded() {
        String article = "Net inflows reached $120 million this week, and $1.2 billion this year.";

        assertThat(NumericGuard.unsupported("Dòng vốn 120M USD, tổng $1.2 tỷ trong năm; 120 triệu tuần này.", article))
                .isEmpty();
        assertThat(NumericGuard.unsupported("Dòng vốn 1,2 nghìn tỷ.", article)).containsExactly("1,2");
    }

    @Test
    void NSF11_datesTimesYearsAndOrdinals_areNotFigures() {
        String article = "Bitcoin rose to $82,000, up 3.5% in the session.";

        assertThat(NumericGuard.unsupported(
                        "Ngày 8 tháng 10 năm 2024, lúc 10:30 UTC (10 a.m.), Bitcoin tăng 3.5% lên $82,000; quý 3,"
                                + " 6 tháng, 24/10, 1st.",
                        article))
                .isEmpty();
    }

    @Test
    void NSF11_aYearWrittenAsAnAmountOrAPercent_isAFigure() {
        assertThat(NumericGuard.unsupported("Quỹ có $2024 và tăng 2025%.", ARTICLE))
                .containsExactly("2024", "2025");
    }

    @Test
    void NSF11_noFigure_orNoText_isClean() {
        assertThat(NumericGuard.unsupported("Thị trường đi ngang.", ARTICLE)).isEmpty();
        assertThat(NumericGuard.unsupported(null, ARTICLE)).isEmpty();
        assertThat(NumericGuard.unsupported("Giá 5%.", null)).containsExactly("5");
    }

    @Test
    void NSF11_eachFigureHasTheReadingsOfBothConventions() {
        assertThat(NumericGuard.readings("81,700"))
                .containsExactlyInAnyOrder(new BigDecimal("81700"), new BigDecimal("81.700"));
        assertThat(NumericGuard.readings("81.000"))
                .containsExactlyInAnyOrder(new BigDecimal("81000"), new BigDecimal("81.000"));
        assertThat(NumericGuard.readings("1,234.56")).containsExactly(new BigDecimal("1234.56"));
        assertThat(NumericGuard.readings("1.234,5")).containsExactly(new BigDecimal("1234.5"));
        assertThat(NumericGuard.readings("3,5")).containsExactly(new BigDecimal("3.5"));
        assertThat(NumericGuard.readings("42")).containsExactly(new BigDecimal("42"));
        assertThat(NumericGuard.readings("1.2.3,4.5")).isEmpty();
    }
}
