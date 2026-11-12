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

/** One turn of a conversation; the assistant's turns are the model's earlier answers. */
export interface ChatTurn {
  role: "user" | "assistant";
  text: string;
}

/** One request for a free-text answer to the last turn of a conversation (NSF-16). */
export interface ChatRequest {
  model: string;
  systemPrompt: string;
  /** Oldest first; the last turn is the user's question. */
  turns: readonly ChatTurn[];
  temperature: number;
  maxOutputTokens: number;
  /** Aborted once the caller's deadline passes or it hangs up: the call in flight stops and is not retried. */
  signal?: AbortSignal;
}

/** A language model behind one API. A new provider is a new implementation; nothing else changes. */
export interface LlmProvider {
  readonly name: string;
  generateJson(request: GenerateRequest): Promise<GenerateResult>;
  generateText(request: ChatRequest): Promise<GenerateResult>;
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

  /** Whether the same request may succeed later: the backend may send it again (NSF-11 next run, NSF-16 retry). */
  get retryable(): boolean {
    return this.kind === "RATE_LIMITED" || this.kind === "UNAVAILABLE" || this.kind === "TIMEOUT";
  }
}
