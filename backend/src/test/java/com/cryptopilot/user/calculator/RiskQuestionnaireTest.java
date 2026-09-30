package com.cryptopilot.user.calculator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.cryptopilot.user.model.QuestionnaireResult;
import com.cryptopilot.user.model.RiskAnswerOption;
import com.cryptopilot.user.model.RiskQuestion;
import com.cryptopilot.user.model.enums.QuestionGroup;
import com.cryptopilot.user.model.enums.RiskProfile;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The risk questionnaire: its shape (SRS 3.2.5), the level of each group score on both sides of each threshold, the
 * lower-level rule, and the answers it refuses.
 *
 * <p>Rule: BR-66; SRS 3.2.5; D-53.
 */
class RiskQuestionnaireTest {

    @Test
    void BR66_theQuestionnaire_hasFiveToSevenQuestionsInTheTwoGroups() {
        List<RiskQuestion> questions = RiskQuestionnaire.questions();

        assertThat(questions).hasSizeBetween(5, 7);
        assertThat(questions.stream().filter(q -> q.group() == QuestionGroup.CAPACITY))
                .hasSize(3);
        assertThat(questions.stream().filter(q -> q.group() == QuestionGroup.ATTITUDE))
                .hasSize(3);
        assertThat(questions).extracting(RiskQuestion::code).doesNotHaveDuplicates();
        assertThat(questions).allSatisfy(q -> assertThat(q.options())
                .extracting(RiskAnswerOption::points)
                .containsExactly(1, 2, 3));
    }

    /** Group score to level: 3–4 low, 5–7 medium, 8–9 high, checked on each side of both thresholds. */
    @ParameterizedTest(name = "{0} points → {1}")
    @CsvSource({"3, CONSERVATIVE", "4, CONSERVATIVE", "5, BALANCED", "7, BALANCED", "8, AGGRESSIVE", "9, AGGRESSIVE"})
    void BR66_aGroupScore_givesTheLevelOfItsBand(int score, RiskProfile expected) {
        assertThat(RiskQuestionnaire.profileOf(score)).isEqualTo(expected);
    }

    /** The suggestion is the lower of the two group levels, whichever group is lower. */
    @ParameterizedTest(name = "capacity {0}, attitude {1} → {2}")
    @CsvSource({
        "1, 1, CONSERVATIVE",
        "3, 3, AGGRESSIVE",
        "2, 2, BALANCED",
        "3, 1, CONSERVATIVE",
        "1, 3, CONSERVATIVE",
        "3, 2, BALANCED",
        "2, 3, BALANCED"
    })
    void BR66_theSuggestion_isTheLowerOfTheTwoGroupLevels(
            int capacityPoints, int attitudePoints, RiskProfile expected) {
        QuestionnaireResult result = RiskQuestionnaire.score(answers(capacityPoints, attitudePoints));

        assertThat(result.capacityScore()).isEqualTo(3 * capacityPoints);
        assertThat(result.attitudeScore()).isEqualTo(3 * attitudePoints);
        assertThat(result.suggested()).isEqualTo(expected);
    }

    /** One answer moves a group across a threshold: 4 is low, 5 is medium; 7 is medium, 8 is high. */
    @Test
    void BR66_oneAnswerAcrossAThreshold_changesTheSuggestion() {
        Map<String, String> low = answers(2, 2);
        setGroup(low, QuestionGroup.CAPACITY, 1, 1, 2);
        assertThat(RiskQuestionnaire.score(low).suggested()).isEqualTo(RiskProfile.CONSERVATIVE);
        setGroup(low, QuestionGroup.CAPACITY, 1, 2, 2);
        assertThat(RiskQuestionnaire.score(low).suggested()).isEqualTo(RiskProfile.BALANCED);

        Map<String, String> high = answers(3, 3);
        setGroup(high, QuestionGroup.ATTITUDE, 3, 2, 2);
        assertThat(RiskQuestionnaire.score(high).suggested()).isEqualTo(RiskProfile.BALANCED);
        setGroup(high, QuestionGroup.ATTITUDE, 3, 3, 2);
        assertThat(RiskQuestionnaire.score(high).suggested()).isEqualTo(RiskProfile.AGGRESSIVE);
    }

    @Test
    void BR66_anUnansweredQuestion_isRefused() {
        Map<String, String> answers = answers(2, 2);
        answers.remove(RiskQuestionnaire.questions().get(0).code());

        assertThatIllegalArgumentException()
                .isThrownBy(() -> RiskQuestionnaire.score(answers))
                .withMessageContaining("not answered");
    }

    @Test
    void BR66_anAnswerThatIsNotTheQuestions_isRefused() {
        Map<String, String> answers = answers(2, 2);
        answers.put(RiskQuestionnaire.questions().get(0).code(), "SOMETHING_ELSE");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> RiskQuestionnaire.score(answers))
                .withMessageContaining("SOMETHING_ELSE");
    }

    @Test
    void BR66_aKeyThatIsNoQuestion_isRefused() {
        Map<String, String> answers = answers(2, 2);
        answers.put("FAVOURITE_COIN", "BTC");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> RiskQuestionnaire.score(answers))
                .withMessageContaining("FAVOURITE_COIN");
    }

    /** Every question of a group answered with the option worth the given points. */
    private static Map<String, String> answers(int capacityPoints, int attitudePoints) {
        Map<String, String> answers = new HashMap<>();
        setGroup(answers, QuestionGroup.CAPACITY, capacityPoints, capacityPoints, capacityPoints);
        setGroup(answers, QuestionGroup.ATTITUDE, attitudePoints, attitudePoints, attitudePoints);
        return answers;
    }

    private static void setGroup(Map<String, String> answers, QuestionGroup group, int... points) {
        List<RiskQuestion> questions = RiskQuestionnaire.questions().stream()
                .filter(q -> q.group() == group)
                .toList();
        for (int i = 0; i < questions.size(); i++) {
            RiskQuestion question = questions.get(i);
            int wanted = points[i];
            answers.put(
                    question.code(),
                    question.options().stream()
                            .filter(o -> o.points() == wanted)
                            .findFirst()
                            .orElseThrow()
                            .code());
        }
    }
}
