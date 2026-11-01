package com.cryptopilot.ai.calculator;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AnswerGuardTest {

    private static final String DISCLAIMER = "Thông tin chỉ mang tính tham khảo, không phải lời khuyên đầu tư.";

    @Test
    void BR51_anAnswerWithoutTheDisclaimer_getsItAppendedOnItsOwnParagraph() {
        assertThat(AnswerGuard.withDisclaimer("  RSI 72 là vùng quá mua.  ", DISCLAIMER))
                .isEqualTo("RSI 72 là vùng quá mua.\n\n" + DISCLAIMER);
    }

    @Test
    void BR51_anAnswerThatAlreadyEndsWithIt_isLeftAlone_whateverTheCaseAccentsOrPunctuation() {
        String exact = "RSI 72 là vùng quá mua.\n\n" + DISCLAIMER;
        String loose = "RSI 72 là vùng quá mua. THONG TIN chi mang tinh tham khao - khong phai loi khuyen dau tu!";

        assertThat(AnswerGuard.withDisclaimer(exact, DISCLAIMER)).isEqualTo(exact);
        assertThat(AnswerGuard.withDisclaimer(loose, DISCLAIMER)).isEqualTo(loose);
    }

    @Test
    void BR51_aPartialDisclaimer_doesNotCount() {
        String partial = "Xu hướng tăng. Thông tin chỉ mang tính tham khảo.";

        assertThat(AnswerGuard.withDisclaimer(partial, DISCLAIMER)).endsWith("\n\n" + DISCLAIMER);
    }

    @Test
    void BR51_anEmptyAnswer_becomesTheDisclaimer_andNoDisclaimerConfigured_changesNothing() {
        assertThat(AnswerGuard.withDisclaimer("  ", DISCLAIMER)).isEqualTo(DISCLAIMER);
        assertThat(AnswerGuard.withDisclaimer(" text ", " ")).isEqualTo("text");
        assertThat(AnswerGuard.withDisclaimer(" text ", null)).isEqualTo("text");
    }

    @Test
    void foldingDropsAccentsCasePunctuationAndTheVietnameseD() {
        assertThat(AnswerGuard.fold("Đầu Tư, ĐƯỢC!")).isEqualTo("dautuduoc");
    }
}
