package com.cryptopilot.ai.model;

/** The answer of {@code POST /v1/chat}: the model's text and the token usage (NSF-16). */
public record AiChatAnswer(String text, String model, int inputTokens, int outputTokens) {}
