package com.cryptopilot.user.calculator;

import com.cryptopilot.user.RiskProfile;
import com.cryptopilot.user.model.QuestionGroup;
import com.cryptopilot.user.model.QuestionnaireResult;
import com.cryptopilot.user.model.RiskAnswerOption;
import com.cryptopilot.user.model.RiskQuestion;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The onboarding risk questionnaire and its scoring: six questions, three per group, each answered on a 1–3 point
 * scale, from which a risk profile is suggested.
 *
 * <p>Each group's points (3–9) give a level: 3–4 low, 5–7 medium, 8–9 high. Low is CONSERVATIVE, medium BALANCED and
 * high AGGRESSIVE. The suggestion is the <em>lower</em> of the two levels, so a Trader keen on risk whose finances
 * cannot bear the losses is not pointed to AGGRESSIVE, and neither is one who can bear losses but dislikes them.
 *
 * <p>The questions and scale follow the structure of a summed-score risk tolerance instrument; the thresholds are v1
 * values, chosen so that the middle of the scale is BALANCED, the default of BR-66. The class holds no state and uses
 * no framework, so the scoring is tested on its own.
 *
 * <p>Rule: BR-66; SRS 3.2.5 (5–7 questions on capacity and attitude suggest a profile; the Trader may choose another);
 * D-53.
 * <p>Reference: Grable, J. E., &amp; Lytton, R. H. (1999). Financial risk tolerance revisited: the development of a
 * risk assessment instrument. <i>Financial Services Review</i>, 8(3), 163–181 (summed item scores measure risk
 * tolerance).
 * <p>Reference: Maginn, J. L., Tuttle, D. L., Pinto, J. E., &amp; McLeavey, D. W. (2007). <i>Managing Investment
 * Portfolios: A Dynamic Process</i> (3rd ed.). Wiley, ch. 2 (ability and willingness to take risk are assessed apart;
 * when they disagree, the lower one governs).
 */
public final class RiskQuestionnaire {

    /** The highest group score that is still low. */
    static final int LOW_UP_TO = 4;

    /** The lowest group score that is high. */
    static final int HIGH_FROM = 8;

    private static final List<RiskQuestion> QUESTIONS = List.of(
            question(
                    "CAPACITY_SHARE_OF_SAVINGS",
                    QuestionGroup.CAPACITY,
                    "What share of your savings is the capital you trade with?",
                    option("MORE_THAN_HALF", "More than half", 1),
                    option("A_FIFTH_TO_HALF", "Between a fifth and a half", 2),
                    option("LESS_THAN_A_FIFTH", "Less than a fifth", 3)),
            question(
                    "CAPACITY_EMERGENCY_FUND",
                    QuestionGroup.CAPACITY,
                    "How many months of expenses could you cover without income and without this capital?",
                    option("UNDER_3_MONTHS", "Less than 3 months", 1),
                    option("3_TO_6_MONTHS", "3 to 6 months", 2),
                    option("OVER_6_MONTHS", "More than 6 months", 3)),
            question(
                    "CAPACITY_NEED_WITHIN_A_YEAR",
                    QuestionGroup.CAPACITY,
                    "Will you need this capital for living costs or plans within the next year?",
                    option("YES", "Yes", 1),
                    option("MAYBE", "Possibly", 2),
                    option("NO", "No", 3)),
            question(
                    "ATTITUDE_DROP_OF_20_PERCENT",
                    QuestionGroup.ATTITUDE,
                    "Your capital falls 20% in a month. What do you do?",
                    option("SELL_EVERYTHING", "Close everything to stop the loss", 1),
                    option("WAIT", "Hold and wait", 2),
                    option("ADD_MORE", "Add to the positions", 3)),
            question(
                    "ATTITUDE_PREFERRED_OUTCOME",
                    QuestionGroup.ATTITUDE,
                    "Which result over a year would you prefer?",
                    option("SMALL_STEADY", "A small, steady gain", 1),
                    option("MODERATE", "A moderate gain with moderate swings", 2),
                    option("LARGE_VOLATILE", "A large possible gain with large possible losses", 3)),
            question(
                    "ATTITUDE_EXPERIENCE",
                    QuestionGroup.ATTITUDE,
                    "How long have you traded crypto or other volatile assets?",
                    option("UNDER_1_YEAR", "Less than a year", 1),
                    option("1_TO_3_YEARS", "1 to 3 years", 2),
                    option("OVER_3_YEARS", "More than 3 years", 3)));

    private RiskQuestionnaire() {}

    /** The questions in the order they are asked. */
    public static List<RiskQuestion> questions() {
        return QUESTIONS;
    }

    /**
     * Scores a completed questionnaire.
     *
     * @param answers the chosen answer code by question code; every question answered, nothing else
     * @throws IllegalArgumentException when a question is unanswered, an answer is not one of its question's, or a
     *     key names no question
     */
    public static QuestionnaireResult score(Map<String, String> answers) {
        Objects.requireNonNull(answers, "answers");
        Set<String> unknown = new HashSet<>(answers.keySet());
        int capacity = 0;
        int attitude = 0;
        for (RiskQuestion question : QUESTIONS) {
            unknown.remove(question.code());
            int points = pointsOf(question, answers.get(question.code()));
            if (question.group() == QuestionGroup.CAPACITY) {
                capacity += points;
            } else {
                attitude += points;
            }
        }
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("not a question of the questionnaire: " + unknown);
        }
        RiskProfile byCapacity = profileOf(capacity);
        RiskProfile byAttitude = profileOf(attitude);
        RiskProfile suggested = byCapacity.compareTo(byAttitude) <= 0 ? byCapacity : byAttitude;
        return new QuestionnaireResult(capacity, attitude, suggested);
    }

    /** The profile of one group's score. */
    static RiskProfile profileOf(int groupScore) {
        if (groupScore <= LOW_UP_TO) {
            return RiskProfile.CONSERVATIVE;
        }
        return groupScore >= HIGH_FROM ? RiskProfile.AGGRESSIVE : RiskProfile.BALANCED;
    }

    private static int pointsOf(RiskQuestion question, String answer) {
        if (answer == null) {
            throw new IllegalArgumentException(question.code() + " is not answered");
        }
        return question.options().stream()
                .filter(option -> option.code().equals(answer))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(answer + " is not an answer to " + question.code()))
                .points();
    }

    private static RiskQuestion question(String code, QuestionGroup group, String text, RiskAnswerOption... options) {
        return new RiskQuestion(code, group, text, List.of(options));
    }

    private static RiskAnswerOption option(String code, String text, int points) {
        return new RiskAnswerOption(code, text, points);
    }
}
