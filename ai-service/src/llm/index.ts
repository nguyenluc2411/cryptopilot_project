import type { Env } from "../config/env.js";
import { GeminiProvider } from "./gemini.js";
import type { LlmProvider } from "./provider.js";

/** The provider the environment names. Adding one is a case here and a class beside GeminiProvider. */
export function createProvider(env: Env, fetchFn: typeof fetch = fetch): LlmProvider {
  return build(env, env.LLM_API_KEY, env.LLM_TIMEOUT_MS, fetchFn);
}

/** The assistant's provider (NSF-16): the same model provider on its own keys, or on the shared keys when none is set. */
export function createChatProvider(env: Env, fetchFn: typeof fetch = fetch): LlmProvider {
  return build(env, env.LLM_CHAT_API_KEY ?? env.LLM_API_KEY, env.LLM_CHAT_TIMEOUT_MS, fetchFn);
}

function build(env: Env, apiKeys: readonly string[], timeoutMs: number, fetchFn: typeof fetch): LlmProvider {
  switch (env.LLM_PROVIDER) {
    case "gemini":
      return new GeminiProvider({
        apiKeys,
        timeoutMs,
        fetch: fetchFn,
        ...(env.LLM_BASE_URL !== undefined ? { baseUrl: env.LLM_BASE_URL } : {}),
        ...(env.GEMINI_THINKING_LEVEL !== undefined ? { thinkingLevel: env.GEMINI_THINKING_LEVEL } : {}),
      });
  }
}
