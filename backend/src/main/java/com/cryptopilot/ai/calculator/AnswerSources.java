package com.cryptopilot.ai.calculator;

import com.cryptopilot.ai.model.AnswerSource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * The articles an answer cites (T-074): every {@code [n]}, or {@code [n, m]}, in the answer whose number is an article
 * of the question's context, in the order the answer first cites them. A number the context does not hold cites
 * nothing, so a link shown under an answer is always one the system gave the model.
 *
 * <p>Rule: BR-51; T-074.
 */
public final class AnswerSources {

    /** {@code [1]}, {@code [1, 2]}, {@code [1,2,3]}. */
    private static final Pattern CITATION = Pattern.compile("\\[(\\d{1,2}(?:\\s*,\\s*\\d{1,2})*)]");

    private AnswerSources() {}

    /** The cited articles of {@code context}, which is the question's context snapshot; empty when none is cited. */
    public static List<AnswerSource> cited(String answer, JsonNode context) {
        if (answer == null || context == null) {
            return List.of();
        }
        Map<Integer, JsonNode> byRef = new HashMap<>();
        JsonNode articles = context.path("news").path("articles");
        for (int i = 0; i < articles.size(); i++) {
            JsonNode article = articles.get(i);
            if (article.path("ref").isIntegralNumber()) {
                byRef.put(article.path("ref").asInt(), article);
            }
        }
        if (byRef.isEmpty()) {
            return List.of();
        }
        Set<Integer> order = new LinkedHashSet<>();
        Matcher matcher = CITATION.matcher(answer);
        while (matcher.find()) {
            for (String number : matcher.group(1).split(",")) {
                int ref = Integer.parseInt(number.strip());
                if (byRef.containsKey(ref)) {
                    order.add(ref);
                }
            }
        }
        List<AnswerSource> sources = new ArrayList<>(order.size());
        for (int ref : order) {
            JsonNode article = byRef.get(ref);
            String url = text(article, "url");
            if (url == null) {
                continue;
            }
            String origin = text(article, "origin");
            sources.add(new AnswerSource(
                    ref,
                    origin == null ? AnswerSource.SYSTEM : origin,
                    text(article, "headline"),
                    text(article, "outlet"),
                    text(article, "publishedAt"),
                    url));
        }
        return sources;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() && !value.asString().isBlank() ? value.asString() : null;
    }
}
