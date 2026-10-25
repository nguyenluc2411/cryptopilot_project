import type { Env } from "../config/env.js";
import { GeminiProvider } from "./gemini.js";
import type { LlmProvider } from "./provider.js";

/** The provider the environment names. Adding one is a case here and a class beside GeminiProvider. */
export function createProvider(env: Env, fetchFn: typeof fetch = fetch): LlmProvider {
  switch (env.LLM_PROVIDER) {
    case "gemini":
      return new GeminiProvider({
        apiKeys: env.LLM_API_KEY,
        timeoutMs: env.LLM_TIMEOUT_MS,
        fetch: fetchFn,
        ...(env.LLM_BASE_URL !== undefined ? { baseUrl: env.LLM_BASE_URL } : {}),
        ...(env.GEMINI_THINKING_LEVEL !== undefined ? { thinkingLevel: env.GEMINI_THINKING_LEVEL } : {}),
      });
  }
}
