import { describe, expect, it } from "vitest";
import { loadEnv } from "../src/config/env.js";
import { createLimiter } from "../src/limiter/limiter.js";
import { createChatProvider, createProvider } from "../src/llm/index.js";
import { GeminiProvider } from "../src/llm/gemini.js";
import { unfence } from "../src/prompts/newsSummary.js";

const VALID = { AI_SERVICE_SHARED_SECRET: "x".repeat(32), LLM_API_KEY: " key-a , ,key-b " };

describe("environment", () => {
  it("applies the defaults and splits the key list", () => {
    const env = loadEnv(VALID);

    expect(env).toMatchObject({
      HOST: "127.0.0.1",
      PORT: 7070,
      LLM_PROVIDER: "gemini",
      LLM_API_KEY: ["key-a", "key-b"],
      LLM_TIMEOUT_MS: 60_000,
      LLM_REQUESTS_PER_MINUTE: 10,
      LLM_MAX_CONCURRENT: 2,
    });
    expect(env.GEMINI_THINKING_LEVEL).toBeUndefined();
    expect(env.LLM_CHAT_API_KEY).toBeUndefined();
    expect(env).toMatchObject({ LLM_CHAT_REQUESTS_PER_MINUTE: 10, LLM_CHAT_MAX_CONCURRENT: 2 });
    expect(2 * env.LLM_CHAT_TIMEOUT_MS + 1_000).toBeLessThanOrEqual(env.LLM_CHAT_BUDGET_MS);
    expect(env.LLM_CHAT_BUDGET_MS).toBe(60_000);
  });

  it("NSF16 reads the assistant's own keys and limits, and takes a blank key list as none", () => {
    const env = loadEnv({
      ...VALID,
      LLM_CHAT_API_KEY: " chat-a, chat-b ",
      LLM_CHAT_REQUESTS_PER_MINUTE: "5",
      LLM_CHAT_MAX_CONCURRENT: "1",
    });

    expect(env).toMatchObject({
      LLM_CHAT_API_KEY: ["chat-a", "chat-b"],
      LLM_CHAT_REQUESTS_PER_MINUTE: 5,
      LLM_CHAT_MAX_CONCURRENT: 1,
    });
    expect(loadEnv({ ...VALID, LLM_CHAT_API_KEY: "  " }).LLM_CHAT_API_KEY).toBeUndefined();
    expect(() => loadEnv({ ...VALID, LLM_CHAT_API_KEY: " , " })).toThrow(/LLM_CHAT_API_KEY: needs at least one key/);
  });

  it("names every wrong variable and echoes no value", () => {
    const attempt = () =>
      loadEnv({ AI_SERVICE_SHARED_SECRET: "too-short-secret", LLM_API_KEY: " , ", PORT: "99999", LLM_PROVIDER: "x" });

    expect(attempt).toThrow(/AI_SERVICE_SHARED_SECRET: must be at least 32 characters/);
    expect(attempt).toThrow(/LLM_API_KEY: needs at least one key/);
    expect(attempt).toThrow(/PORT/);
    expect(attempt).toThrow(/LLM_PROVIDER/);
    expect(attempt).not.toThrow(/too-short-secret/);
  });

  it("reads the optional provider settings", () => {
    const env = loadEnv({ ...VALID, LLM_BASE_URL: "https://proxy.example.com", GEMINI_THINKING_LEVEL: "low", PORT: "8081" });

    expect(env.LLM_BASE_URL).toBe("https://proxy.example.com");
    expect(env.GEMINI_THINKING_LEVEL).toBe("low");
    expect(env.PORT).toBe(8081);
  });
});

describe("wiring", () => {
  it("builds the provider the environment names, with and without the optional settings", () => {
    expect(createProvider(loadEnv(VALID))).toBeInstanceOf(GeminiProvider);
    const configured = createProvider(
      loadEnv({ ...VALID, LLM_BASE_URL: "https://proxy.example.com", GEMINI_THINKING_LEVEL: "minimal" }),
    );
    expect(configured.name).toBe("gemini");
  });

  it("NSF16 the assistant calls Gemini with its own key, or the shared one when it has none", async () => {
    const keys: string[] = [];
    const fetchFn = (async (_input: string | URL | Request, init?: RequestInit) => {
      keys.push((init?.headers as Record<string, string>)["x-goog-api-key"] ?? "");
      return new Response(JSON.stringify({ candidates: [{ content: { parts: [{ text: "ok" }] } }] }));
    }) as typeof fetch;
    const chat = {
      model: "m",
      systemPrompt: "s",
      turns: [{ role: "user" as const, text: "q" }],
      temperature: 0,
      maxOutputTokens: 16,
    };

    await createChatProvider(loadEnv({ ...VALID, LLM_CHAT_API_KEY: "chat-a" }), fetchFn).generateText(chat);
    await createChatProvider(loadEnv(VALID), fetchFn).generateText(chat);
    await createProvider(loadEnv({ ...VALID, LLM_CHAT_API_KEY: "chat-a" }), fetchFn).generateText(chat);

    expect(keys).toEqual(["chat-a", "key-a", "key-a"]);
  });

  it("builds a limiter that queues instead of refusing", async () => {
    const limiter = createLimiter({ requestsPerMinute: 2, maxConcurrent: 1 });

    await expect(Promise.all([1, 2].map((n) => limiter.schedule(async () => n)))).resolves.toEqual([1, 2]);
    expect(await limiter.currentReservoir()).toBe(0);
    await limiter.stop({ dropWaitingJobs: true });
  });

  it("unwraps a fenced answer and leaves a plain one alone", () => {
    expect(unfence('```json\n{"a":1}\n```')).toBe('{"a":1}');
    expect(unfence('```\n{"a":1}```')).toBe('{"a":1}');
    expect(unfence('  {"a":1} ')).toBe('{"a":1}');
  });
});
