import Bottleneck from "bottleneck";
import { afterEach, describe, expect, it } from "vitest";
import { buildApp, SECRET_HEADER } from "../src/app.js";
import { LlmError, type GenerateRequest, type GenerateResult, type LlmProvider } from "../src/llm/provider.js";

const SECRET = "s".repeat(48);

const BODY = {
  model: "gemini-test-lite",
  systemPrompt: "Summarize in Vietnamese, at most 60 words.",
  temperature: 0.2,
  maxOutputTokens: 256,
  article: { title: "Bitcoin holds $81K", source: "CoinDesk Markets", text: "Bitcoin traded flat at $81,000." },
};

/** A provider that answers from a function and remembers what it was asked. */
class FakeProvider implements LlmProvider {
  readonly name = "fake";
  readonly requests: GenerateRequest[] = [];

  constructor(private readonly answer: (request: GenerateRequest) => GenerateResult | Promise<GenerateResult>) {}

  async generateJson(request: GenerateRequest): Promise<GenerateResult> {
    this.requests.push(request);
    return this.answer(request);
  }
}

const apps: ReturnType<typeof buildApp>[] = [];

function app(provider: LlmProvider) {
  const built = buildApp({ sharedSecret: SECRET, provider, limiter: new Bottleneck() });
  apps.push(built);
  return built;
}

function post(provider: LlmProvider, payload: unknown, headers: Record<string, string> = { [SECRET_HEADER]: SECRET }) {
  return app(provider).inject({ method: "POST", url: "/v1/news/summary", payload: payload as object, headers });
}

afterEach(async () => {
  await Promise.all(apps.splice(0).map((built) => built.close()));
});

describe("POST /v1/news/summary", () => {
  it("NSF11 returns the model's title, summary and sentiment with the token usage", async () => {
    const provider = new FakeProvider(() => ({
      text: '```json\n{"title":"Bitcoin giữ mức $81K","summary":"Bitcoin đi ngang quanh $81,000.","sentiment":"NEUTRAL"}\n```',
      inputTokens: 210,
      outputTokens: 40,
    }));

    const response = await post(provider, BODY);

    expect(response.statusCode).toBe(200);
    expect(response.json()).toEqual({
      title: "Bitcoin giữ mức $81K",
      summary: "Bitcoin đi ngang quanh $81,000.",
      sentiment: "NEUTRAL",
      model: "gemini-test-lite",
      inputTokens: 210,
      outputTokens: 40,
    });
    const sent = provider.requests[0];
    expect(sent?.model).toBe("gemini-test-lite");
    expect(sent?.systemPrompt).toBe(BODY.systemPrompt);
    expect(sent?.temperature).toBe(0.2);
    expect(sent?.maxOutputTokens).toBe(256);
    expect(sent?.userPrompt).toContain("TITLE: Bitcoin holds $81K");
    expect(sent?.userPrompt).toContain("SOURCE: CoinDesk Markets");
    expect(sent?.responseSchema).toMatchObject({ required: ["title", "summary", "body", "sentiment"] });
  });

  it("D125 passes on the article written again when the model wrote one", async () => {
    const provider = new FakeProvider(() => ({
      text: '{"title":"t","summary":"x","body":"  Đoạn một.\\n\\nĐoạn hai.  ","sentiment":"BULLISH"}',
      inputTokens: 1,
      outputTokens: 1,
    }));

    const response = await post(provider, BODY);

    expect(response.statusCode).toBe(200);
    expect(response.json()).toMatchObject({ summary: "x", body: "Đoạn một.\n\nĐoạn hai." });
  });

  it("NSF11 passes the backend's reason for a retry to the model", async () => {
    const provider = new FakeProvider(() => ({
      text: '{"title":"t","summary":"x","sentiment":"BULLISH"}',
      inputTokens: 1,
      outputTokens: 1,
    }));

    await post(provider, { ...BODY, feedback: "the number 90,000 is not in the article" });

    expect(provider.requests[0]?.userPrompt).toMatch(/^Your previous answer for this article was rejected: the number/);
  });

  it("NSF11 refuses a caller without the shared secret before anything else", async () => {
    const provider = new FakeProvider(() => {
      throw new Error("must not be called");
    });

    expect((await post(provider, BODY, {})).statusCode).toBe(401);
    expect((await post(provider, BODY, { [SECRET_HEADER]: "wrong" })).statusCode).toBe(401);
    expect((await post(provider, BODY, { [SECRET_HEADER]: SECRET.slice(1) })).json()).toEqual({
      code: "UNAUTHORIZED",
      retryable: false,
    });
    expect(provider.requests).toHaveLength(0);
  });

  it("NSF11 refuses a request that breaks the contract, naming each field", async () => {
    const provider = new FakeProvider(() => ({ text: "{}", inputTokens: 0, outputTokens: 0 }));

    const response = await post(provider, { ...BODY, model: "../../keys", temperature: 3, article: { title: "" }, extra: 1 });

    expect(response.statusCode).toBe(400);
    const paths = (response.json().issues as { path: string }[]).map((issue) => issue.path);
    expect(paths).toEqual(expect.arrayContaining(["model", "temperature", "article.title", "article.text"]));
    expect(response.json()).toMatchObject({ code: "INVALID_REQUEST", retryable: false });
    expect(provider.requests).toHaveLength(0);
  });

  it("NSF11 an answer outside the shape is a bad response, never a summary", async () => {
    for (const text of [
      '{"title":"t","summary":"x","sentiment":"VERY_BULLISH"}',
      '{"title":"t","summary":"  "}',
      '{"summary":"x","sentiment":"NEUTRAL"}',
      '{"title":" ","summary":"x","sentiment":"NEUTRAL"}',
      "not json",
    ]) {
      const provider = new FakeProvider(() => ({ text, inputTokens: 1, outputTokens: 1 }));

      const response = await post(provider, BODY);

      expect(response.statusCode).toBe(502);
      expect(response.json()).toMatchObject({ code: "LLM_BAD_RESPONSE", retryable: false });
    }
  });

  it.each([
    ["RATE_LIMITED", 429, "LLM_RATE_LIMITED", true],
    ["UNAVAILABLE", 503, "LLM_UNAVAILABLE", true],
    ["TIMEOUT", 504, "LLM_TIMEOUT", true],
    ["REJECTED", 502, "LLM_REJECTED", false],
  ] as const)("NSF11 a provider failure %s answers %i %s", async (kind, statusCode, code, retryable) => {
    const provider = new FakeProvider(() => {
      throw new LlmError(kind, `failed: ${kind}`);
    });

    const response = await post(provider, BODY);

    expect(response.statusCode).toBe(statusCode);
    expect(response.json()).toEqual({ code, retryable, message: `failed: ${kind}` });
  });

  it("NSF11 a fault that is not the provider's is a 500, not a provider status", async () => {
    const provider = new FakeProvider(() => {
      throw new TypeError("a bug");
    });

    expect((await post(provider, BODY)).statusCode).toBe(500);
  });
});

describe("GET /health", () => {
  it("answers without the secret and names the provider", async () => {
    const provider = new FakeProvider(() => ({ text: "{}", inputTokens: 0, outputTokens: 0 }));

    const response = await app(provider).inject({ method: "GET", url: "/health?probe=1" });

    expect(response.statusCode).toBe(200);
    expect(response.json()).toEqual({ status: "UP", provider: "fake" });
  });
});
