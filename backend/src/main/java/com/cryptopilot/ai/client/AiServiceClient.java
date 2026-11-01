package com.cryptopilot.ai.client;

import com.cryptopilot.ai.AiServiceException;
import com.cryptopilot.ai.NewsSummaryRequest;
import com.cryptopilot.ai.NewsSummaryResult;
import com.cryptopilot.ai.model.AiChatAnswer;
import com.cryptopilot.ai.model.AiChatRequest;
import com.cryptopilot.ai.model.ChatTurn;
import com.cryptopilot.ai.model.enums.SenderRole;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The HTTP side of the AI Service (TECHNICAL_DESIGN 7.14): JSON over HTTP with the shared secret in a header, every call
 * bounded in time. An error answer of the AI Service carries a stable code and whether a retry may succeed, which this
 * client passes on as an {@link AiServiceException}; a connection that fails or a service that does not answer in time
 * is {@code UNREACHABLE} and retryable.
 *
 * <p>Rule: NSF-11, NSF-16; ADR-004.
 */
public class AiServiceClient implements AutoCloseable {

    /** The header the AI Service checks, in constant time, before anything else. */
    public static final String SECRET_HEADER = "x-ai-service-secret";

    private final AiServiceProperties properties;
    private final JsonMapper json;
    private final HttpClient http;

    public AiServiceClient(AiServiceProperties properties, JsonMapper json) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.json = Objects.requireNonNull(json, "json must not be null");
        this.http = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
    }

    /** {@code POST /v1/news/summary}. */
    public NewsSummaryResult summarizeNews(NewsSummaryRequest request) {
        requireConfigured();
        Map<String, Object> article = new LinkedHashMap<>();
        article.put("title", request.title());
        if (request.source() != null) {
            article.put("source", request.source());
        }
        article.put("text", request.text());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.model());
        body.put("systemPrompt", request.systemPrompt());
        body.put("temperature", request.temperature());
        body.put("maxOutputTokens", request.maxOutputTokens());
        body.put("article", article);
        if (request.feedback() != null) {
            body.put("feedback", request.feedback());
        }
        JsonNode answer = post("/v1/news/summary", body, properties.requestTimeout());
        return new NewsSummaryResult(
                text(answer, "title"),
                text(answer, "summary"),
                text(answer, "sentiment"),
                answer.path("model").asString(request.model()),
                answer.path("inputTokens").asInt(0),
                answer.path("outputTokens").asInt(0),
                blankToNull(answer.path("body").asString(null)));
    }

    /**
     * {@code POST /v1/chat} (T-072, NSF-16): one question with its history and context; the answer is the model's
     * text as written, which the caller guards (BR-51).
     */
    public AiChatAnswer chat(AiChatRequest request) {
        requireConfigured();
        List<Map<String, Object>> history = new ArrayList<>(request.history().size());
        for (ChatTurn turn : request.history()) {
            history.add(
                    Map.of("role", turn.role() == SenderRole.ASSISTANT ? "assistant" : "user", "text", turn.text()));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.model());
        body.put("systemPrompt", request.systemPrompt());
        body.put("temperature", request.temperature().doubleValue());
        body.put("maxOutputTokens", request.maxOutputTokens());
        body.put("history", history);
        body.put("question", request.question());
        if (request.context() != null && request.context().isObject()) {
            body.put("context", request.context());
        }
        JsonNode answer = post("/v1/chat", body, request.timeout());
        return new AiChatAnswer(
                text(answer, "text"),
                answer.path("model").asString(request.model()),
                answer.path("inputTokens").asInt(0),
                answer.path("outputTokens").asInt(0));
    }

    @Override
    public void close() {
        http.close();
    }

    private void requireConfigured() {
        if (!properties.configured()) {
            throw new AiServiceException(
                    AiServiceException.NOT_CONFIGURED, false, "the AI Service shared secret is not set");
        }
    }

    private JsonNode post(String path, Object body, Duration timeout) {
        URI uri = properties.url().resolve(path);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header(SECRET_HEADER, properties.sharedSecret())
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new AiServiceException(
                    AiServiceException.UNREACHABLE, true, "the AI Service did not answer in time", e);
        } catch (IOException e) {
            throw new AiServiceException(
                    AiServiceException.UNREACHABLE, true, "the AI Service cannot be reached at " + uri, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiServiceException(AiServiceException.UNREACHABLE, true, "interrupted calling the AI Service", e);
        }
        JsonNode answer = parse(response.body());
        if (response.statusCode() / 100 == 2) {
            return answer;
        }
        String code = answer.path("code").asString("HTTP_" + response.statusCode());
        boolean retryable = answer.path("retryable").asBoolean(response.statusCode() >= 500);
        String message = answer.path("message").asString("the AI Service answered HTTP " + response.statusCode());
        throw new AiServiceException(code, retryable, message, null, response.statusCode());
    }

    private JsonNode parse(String body) {
        try {
            return json.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (JacksonException e) {
            throw new AiServiceException(
                    AiServiceException.BAD_ANSWER, true, "the AI Service answered with something that is not JSON", e);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String text(JsonNode answer, String field) {
        String value = answer.path(field).asString(null);
        if (value == null || value.isBlank()) {
            throw new AiServiceException(
                    AiServiceException.BAD_ANSWER, false, "the AI Service's answer has no " + field);
        }
        return value;
    }
}
