import { describe, expect, it } from "vitest";
import { loadEnv } from "../src/config/env.js";
import { createLimiter } from "../src/limiter/limiter.js";
import { createProvider } from "../src/llm/index.js";
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
