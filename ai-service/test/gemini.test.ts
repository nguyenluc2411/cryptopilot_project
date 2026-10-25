import { describe, expect, it } from "vitest";
import { GEMINI_BASE_URL, GeminiProvider, type GeminiOptions } from "../src/llm/gemini.js";
import { LlmError, type GenerateRequest } from "../src/llm/provider.js";

const REQUEST: GenerateRequest = {
  model: "gemini-test-lite",
  systemPrompt: "Summarize.",
  userPrompt: "TITLE: Bitcoin holds",
  temperature: 0.2,
  maxOutputTokens: 256,
  responseSchema: { type: "OBJECT" },
};

interface Call {
  url: string;
  init: RequestInit;
}

/** A fetch that answers from a script, one response per call, and records what it was asked. */
function scriptedFetch(responses: (Response | Error)[]): { fetch: typeof fetch; calls: Call[] } {
  const calls: Call[] = [];
  const fetchFn = (async (input: string | URL | Request, init?: RequestInit) => {
    calls.push({ url: String(input), init: init ?? {} });
    const next = responses.shift();
    if (next === undefined) {
      throw new Error("no scripted response left");
    }
    if (next instanceof Error) {
      throw next;
    }
    return next;
  }) as typeof fetch;
  return { fetch: fetchFn, calls };
}

function answer(text: string, usage = { promptTokenCount: 120, candidatesTokenCount: 30, thoughtsTokenCount: 5 }) {
  return new Response(
    JSON.stringify({ candidates: [{ content: { parts: [{ text }] }, finishReason: "STOP" }], usageMetadata: usage }),
    { status: 200, headers: { "content-type": "application/json" } },
  );
}

function status(code: number, body: unknown = { error: { message: "nope" } }) {
  return new Response(JSON.stringify(body), { status: code, headers: { "content-type": "application/json" } });
}

function provider(fetchFn: typeof fetch, overrides: Partial<GeminiOptions> = {}) {
  return new GeminiProvider({
    apiKeys: ["key-one", "key-two"],
    timeoutMs: 1_000,
    fetch: fetchFn,
    sleep: async () => {},
    ...overrides,
  });
}

function headerOf(call: Call | undefined, name: string): string | undefined {
  return (call?.init.headers as Record<string, string> | undefined)?.[name];
}

describe("GeminiProvider", () => {
  it("NSF11 sends the request as the REST reference writes it, the key in a header and never in the address", async () => {
    const { fetch, calls } = scriptedFetch([answer('{"summary":"s","sentiment":"NEUTRAL"}')]);

    const result = await provider(fetch, { thinkingLevel: "minimal" }).generateJson(REQUEST);

    expect(result).toEqual({ text: '{"summary":"s","sentiment":"NEUTRAL"}', inputTokens: 120, outputTokens: 35 });
    expect(calls).toHaveLength(1);
    expect(calls[0]?.url).toBe(`${GEMINI_BASE_URL}/models/gemini-test-lite:generateContent`);
    expect(calls[0]?.url).not.toContain("key");
    expect(headerOf(calls[0], "x-goog-api-key")).toBe("key-one");
    expect(JSON.parse(String(calls[0]?.init.body))).toEqual({
      systemInstruction: { parts: [{ text: "Summarize." }] },
      contents: [{ role: "user", parts: [{ text: "TITLE: Bitcoin holds" }] }],
      generationConfig: {
        temperature: 0.2,
        maxOutputTokens: 256,
        responseMimeType: "application/json",
        responseSchema: { type: "OBJECT" },
        thinkingConfig: { thinkingLevel: "minimal" },
      },
    });
  });

  it("NSF11 leaves the thinking setting out when none is configured, and joins the visible parts only", async () => {
    const body = {
      candidates: [{ content: { parts: [{ text: "reasoning", thought: true }, { text: '{"a":' }, { text: "1}" }] } }],
    };
    const { fetch, calls } = scriptedFetch([new Response(JSON.stringify(body), { status: 200 })]);

    const result = await provider(fetch).generateJson(REQUEST);

    expect(result).toEqual({ text: '{"a":1}', inputTokens: 0, outputTokens: 0 });
    expect(JSON.parse(String(calls[0]?.init.body)).generationConfig).not.toHaveProperty("thinkingConfig");
  });

  it("NSF11 moves to the next key on a 429, and keeps using the key that worked", async () => {
    const { fetch, calls } = scriptedFetch([status(429), answer("{}"), answer("{}")]);
    const gemini = provider(fetch);

    await gemini.generateJson(REQUEST);
    await gemini.generateJson(REQUEST);

    expect(calls.map((call) => headerOf(call, "x-goog-api-key"))).toEqual(["key-one", "key-two", "key-two"]);
  });

  it("NSF11 is rate limited only once every key was refused", async () => {
    const { fetch, calls } = scriptedFetch([status(429), status(429)]);

    await expect(provider(fetch).generateJson(REQUEST)).rejects.toMatchObject({ kind: "RATE_LIMITED", retryable: true });
    expect(calls).toHaveLength(2);
  });

  it("NSF11 retries a server error once, then gives up", async () => {
    const recovered = scriptedFetch([status(503), answer("{}")]);
    await expect(provider(recovered.fetch).generateJson(REQUEST)).resolves.toMatchObject({ text: "{}" });

    const down = scriptedFetch([status(500), status(502)]);
    await expect(provider(down.fetch).generateJson(REQUEST)).rejects.toMatchObject({ kind: "UNAVAILABLE" });
    expect(down.calls).toHaveLength(2);
  });

  it("NSF11 reports a timeout and a lost connection as retryable, after one retry", async () => {
    const timeout = Object.assign(new Error("timed out"), { name: "TimeoutError" });
    const slow = scriptedFetch([timeout, timeout]);
    await expect(provider(slow.fetch).generateJson(REQUEST)).rejects.toMatchObject({ kind: "TIMEOUT", retryable: true });

    const lost = scriptedFetch([new TypeError("fetch failed"), new TypeError("fetch failed")]);
    await expect(provider(lost.fetch).generateJson(REQUEST)).rejects.toMatchObject({ kind: "UNAVAILABLE" });
  });

  it("NSF11 does not retry a refusal, and quotes the provider's reason without the key", async () => {
    const { fetch, calls } = scriptedFetch([status(400, { error: { message: "model not found" } })]);

    const failure = provider(fetch).generateJson(REQUEST);

    await expect(failure).rejects.toMatchObject({ kind: "REJECTED", retryable: false });
    await expect(failure).rejects.toThrow("model not found");
    await expect(failure).rejects.not.toThrow("key-one");
    expect(calls).toHaveLength(1);
  });

  it("NSF11 a refusal without a readable reason still says so", async () => {
    const { fetch } = scriptedFetch([new Response("<html>", { status: 403 })]);

    await expect(provider(fetch).generateJson(REQUEST)).rejects.toThrow("no message");
  });

  it("NSF11 an answer that is not JSON, or has no text, is a bad response", async () => {
    const notJson = scriptedFetch([new Response("<html>", { status: 200 })]);
    await expect(provider(notJson.fetch).generateJson(REQUEST)).rejects.toMatchObject({ kind: "BAD_RESPONSE" });

    const empty = scriptedFetch([new Response(JSON.stringify({ candidates: [{ finishReason: "SAFETY" }] }))]);
    await expect(provider(empty.fetch).generateJson(REQUEST)).rejects.toThrow("finishReason SAFETY");

    const nothing = scriptedFetch([new Response("{}")]);
    await expect(provider(nothing.fetch).generateJson(REQUEST)).rejects.toThrow("finishReason none");
  });

  it("NSF11 a body that cannot be read is a bad response of the provider", async () => {
    const broken = (async () => ({
      status: 200,
      ok: true,
      json: () => Promise.reject(new SyntaxError("Unexpected end of JSON input")),
    })) as unknown as typeof fetch;

    await expect(provider(broken).generateJson(REQUEST)).rejects.toBeInstanceOf(LlmError);
    await expect(provider(broken).generateJson(REQUEST)).rejects.toMatchObject({ kind: "BAD_RESPONSE" });
  });

  it("NSF11 needs a key, and trims a trailing slash from a configured address", async () => {
    expect(() => new GeminiProvider({ apiKeys: [], timeoutMs: 1 })).toThrow("at least one API key");

    const { fetch, calls } = scriptedFetch([answer("{}")]);
    await provider(fetch, { baseUrl: "https://proxy.example.com/v1beta/" }).generateJson(REQUEST);
    expect(calls[0]?.url).toBe("https://proxy.example.com/v1beta/models/gemini-test-lite:generateContent");
  });

  it("NSF11 waits before the retry with the default pause when none is configured", async () => {
    const waits: number[] = [];
    const { fetch } = scriptedFetch([status(503), answer("{}")]);

    await provider(fetch, { sleep: async (ms) => void waits.push(ms) }).generateJson(REQUEST);

    expect(waits).toEqual([1_000]);
  });
});
