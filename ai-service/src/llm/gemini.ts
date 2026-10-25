import { LlmError, type GenerateRequest, type GenerateResult, type LlmProvider } from "./provider.js";

export const GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta";

export type GeminiThinkingLevel = "minimal" | "low" | "medium" | "high";

export interface GeminiOptions {
  apiKeys: readonly string[];
  baseUrl?: string;
  timeoutMs: number;
  /** Sent as generationConfig.thinkingConfig.thinkingLevel (Gemini 3 models); omitted when not set. */
  thinkingLevel?: GeminiThinkingLevel;
  /** How long to wait before the one retry of a server error or a timeout. */
  retryDelayMs?: number;
  fetch?: typeof fetch;
  sleep?: (ms: number) => Promise<void>;
}

interface GeminiPart {
  text?: string;
  thought?: boolean;
}

interface GeminiResponse {
  candidates?: { content?: { parts?: GeminiPart[] }; finishReason?: string }[];
  usageMetadata?: { promptTokenCount?: number; candidatesTokenCount?: number; thoughtsTokenCount?: number };
}

/**
 * Google Gemini over its REST API (`models/{model}:generateContent`).
 *
 * The key travels in the `x-goog-api-key` header, never in the address, so it cannot end up in an access log or an
 * error message. A 429 moves to the next key, each key tried once per request: free-tier quotas are per key. A server
 * error, a timeout or a lost connection is retried once after a short pause (TECHNICAL_DESIGN 7.14: one retry on
 * 429/5xx). Any other refusal is final: the same request would be refused again.
 */
export class GeminiProvider implements LlmProvider {
  readonly name = "gemini";

  private readonly keys: readonly string[];
  private readonly baseUrl: string;
  private readonly fetchFn: typeof fetch;
  private readonly sleep: (ms: number) => Promise<void>;
  private nextKey = 0;

  constructor(private readonly options: GeminiOptions) {
    if (options.apiKeys.length === 0) {
      throw new Error("Gemini needs at least one API key");
    }
    this.keys = [...options.apiKeys];
    this.baseUrl = (options.baseUrl ?? GEMINI_BASE_URL).replace(/\/+$/, "");
    this.fetchFn = options.fetch ?? fetch;
    this.sleep = options.sleep ?? ((ms) => new Promise((resolve) => setTimeout(resolve, ms)));
  }

  async generateJson(request: GenerateRequest): Promise<GenerateResult> {
    const body = JSON.stringify(this.body(request));
    let rateLimited = 0;
    let retried = false;
    let index = this.nextKey;
    for (;;) {
      const keyIndex = index % this.keys.length;
      try {
        const result = await this.call(this.keys[keyIndex] as string, request.model, body);
        this.nextKey = keyIndex;
        return result;
      } catch (error) {
        if (!(error instanceof LlmError)) {
          throw error;
        }
        if (error.kind === "RATE_LIMITED" && ++rateLimited < this.keys.length) {
          index++;
          continue;
        }
        if ((error.kind === "UNAVAILABLE" || error.kind === "TIMEOUT") && !retried) {
          retried = true;
          await this.sleep(this.options.retryDelayMs ?? 1_000);
          continue;
        }
        throw error;
      }
    }
  }

  /** The request body; exported shape checked by the tests, as the official REST reference writes it. */
  body(request: GenerateRequest): Record<string, unknown> {
    const generationConfig: Record<string, unknown> = {
      temperature: request.temperature,
      maxOutputTokens: request.maxOutputTokens,
      responseMimeType: "application/json",
      responseSchema: request.responseSchema,
    };
    if (this.options.thinkingLevel !== undefined) {
      generationConfig["thinkingConfig"] = { thinkingLevel: this.options.thinkingLevel };
    }
    return {
      systemInstruction: { parts: [{ text: request.systemPrompt }] },
      contents: [{ role: "user", parts: [{ text: request.userPrompt }] }],
      generationConfig,
    };
  }

  private async call(key: string, model: string, body: string): Promise<GenerateResult> {
    const url = `${this.baseUrl}/models/${encodeURIComponent(model)}:generateContent`;
    let response: Response;
    try {
      response = await this.fetchFn(url, {
        method: "POST",
        headers: { "content-type": "application/json", "x-goog-api-key": key },
        body,
        signal: AbortSignal.timeout(this.options.timeoutMs),
      });
    } catch (error) {
      const name = error instanceof Error ? error.name : "";
      if (name === "TimeoutError" || name === "AbortError") {
        throw new LlmError("TIMEOUT", `Gemini did not answer within ${this.options.timeoutMs} ms`, { cause: error });
      }
      throw new LlmError("UNAVAILABLE", "Gemini could not be reached", { cause: error });
    }
    if (response.status === 429) {
      throw new LlmError("RATE_LIMITED", "Gemini rate limited the request (HTTP 429)");
    }
    if (response.status >= 500) {
      throw new LlmError("UNAVAILABLE", `Gemini failed (HTTP ${response.status})`);
    }
    if (!response.ok) {
      throw new LlmError("REJECTED", `Gemini refused the request (HTTP ${response.status}): ${await reason(response)}`);
    }
    let payload: GeminiResponse;
    try {
      payload = (await response.json()) as GeminiResponse;
    } catch (error) {
      throw new LlmError("BAD_RESPONSE", "Gemini answered with something that is not JSON", { cause: error });
    }
    const candidate = payload.candidates?.[0];
    const text = (candidate?.content?.parts ?? [])
      .filter((part) => part.thought !== true)
      .map((part) => part.text ?? "")
      .join("")
      .trim();
    if (text.length === 0) {
      throw new LlmError("BAD_RESPONSE", `Gemini returned no text (finishReason ${candidate?.finishReason ?? "none"})`);
    }
    const usage = payload.usageMetadata ?? {};
    return {
      text,
      inputTokens: usage.promptTokenCount ?? 0,
      outputTokens: (usage.candidatesTokenCount ?? 0) + (usage.thoughtsTokenCount ?? 0),
    };
  }
}

/** The provider's own message for a refusal, short; it names the field at fault and never the key. */
async function reason(response: Response): Promise<string> {
  try {
    const payload = (await response.json()) as { error?: { message?: string } };
    return (payload.error?.message ?? "no message").slice(0, 300);
  } catch {
    return "no message";
  }
}
