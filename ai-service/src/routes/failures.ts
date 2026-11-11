import type { FastifyReply } from "fastify";
import type { LlmError } from "../llm/provider.js";

/** The status and code each failure of the provider answers with; `retryable` tells the backend it may send it again. */
const FAILURES: Record<LlmError["kind"], { status: number; code: string }> = {
  RATE_LIMITED: { status: 429, code: "LLM_RATE_LIMITED" },
  UNAVAILABLE: { status: 503, code: "LLM_UNAVAILABLE" },
  TIMEOUT: { status: 504, code: "LLM_TIMEOUT" },
  REJECTED: { status: 502, code: "LLM_REJECTED" },
  BAD_RESPONSE: { status: 502, code: "LLM_BAD_RESPONSE" },
};

/** The reply for a provider failure, the same on every route. */
export function failure(reply: FastifyReply, error: LlmError): FastifyReply {
  const { status, code } = FAILURES[error.kind];
  return reply.code(status).send({ code, retryable: error.retryable, message: error.message });
}

/** The reply for a request that breaks the contract, naming each field at fault. */
export function invalid(reply: FastifyReply, issues: readonly { path: PropertyKey[]; message: string }[]): FastifyReply {
  return reply.code(400).send({
    code: "INVALID_REQUEST",
    retryable: false,
    issues: issues.map((issue) => ({ path: issue.path.map(String).join("."), message: issue.message })),
  });
}
