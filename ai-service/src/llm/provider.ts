/** One request for a JSON answer, everything the provider needs and nothing it should decide. */
export interface GenerateRequest {
  model: string;
  systemPrompt: string;
  userPrompt: string;
  temperature: number;
  maxOutputTokens: number;
  /** The shape the answer must have, in the provider's schema dialect (OpenAPI subset for Gemini). */
  responseSchema: Record<string, unknown>;
}

export interface GenerateResult {
  text: string;
  inputTokens: number;
  /** Visible and reasoning tokens together: both are billed as output. */
  outputTokens: number;
}

/** A language model behind one API. A new provider is a new implementation; nothing else changes. */
export interface LlmProvider {
  readonly name: string;
  generateJson(request: GenerateRequest): Promise<GenerateResult>;
}

/** Why a call failed, as the route turns it into a status the backend can act on. */
export type LlmErrorKind = "RATE_LIMITED" | "UNAVAILABLE" | "TIMEOUT" | "REJECTED" | "BAD_RESPONSE";

export class LlmError extends Error {
  readonly kind: LlmErrorKind;

  constructor(kind: LlmErrorKind, message: string, options?: { cause?: unknown }) {
    super(message, options);
    this.name = "LlmError";
    this.kind = kind;
  }

  /** Whether the same request may succeed later: the backend retries it at its next run (NSF-11). */
  get retryable(): boolean {
    return this.kind === "RATE_LIMITED" || this.kind === "UNAVAILABLE" || this.kind === "TIMEOUT";
  }
}
