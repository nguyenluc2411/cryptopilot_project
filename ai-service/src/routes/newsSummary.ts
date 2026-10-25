import type Bottleneck from "bottleneck";
import type { FastifyInstance, FastifyReply } from "fastify";
import { LlmError, type LlmProvider } from "../llm/provider.js";
import { newsSummaryUserPrompt, unfence } from "../prompts/newsSummary.js";
import {
  NEWS_SUMMARY_RESPONSE_SCHEMA,
  NewsSummaryAnswerSchema,
  NewsSummaryRequestSchema,
  type NewsSummaryResponse,
} from "../schemas/newsSummary.js";

export interface NewsSummaryRouteDeps {
  provider: LlmProvider;
  limiter: Bottleneck;
}

/** The status and code each failure of the provider answers with; `retryable` tells the backend to try next run. */
const FAILURES: Record<LlmError["kind"], { status: number; code: string }> = {
  RATE_LIMITED: { status: 429, code: "LLM_RATE_LIMITED" },
  UNAVAILABLE: { status: 503, code: "LLM_UNAVAILABLE" },
  TIMEOUT: { status: 504, code: "LLM_TIMEOUT" },
  REJECTED: { status: 502, code: "LLM_REJECTED" },
  BAD_RESPONSE: { status: 502, code: "LLM_BAD_RESPONSE" },
};

/**
 * `POST /v1/news/summary`, NSF-11: one article in, its translated title, a summary, the article written again (D-129)
 * and a sentiment label out, with the token usage. The
 * request is checked before anything is sent to the provider, and the answer is checked before anything is returned:
 * a model that answers outside the shape is a failure, never a summary.
 */
export function registerNewsSummary(app: FastifyInstance, deps: NewsSummaryRouteDeps): void {
  app.post("/v1/news/summary", async (request, reply) => {
    const parsed = NewsSummaryRequestSchema.safeParse(request.body);
    if (!parsed.success) {
      return reply.code(400).send({
        code: "INVALID_REQUEST",
        retryable: false,
        issues: parsed.error.issues.map((issue) => ({ path: issue.path.join("."), message: issue.message })),
      });
    }
    const input = parsed.data;
    try {
      const result = await deps.limiter.schedule(() =>
        deps.provider.generateJson({
          model: input.model,
          systemPrompt: input.systemPrompt,
          userPrompt: newsSummaryUserPrompt(input),
          temperature: input.temperature,
          maxOutputTokens: input.maxOutputTokens,
          responseSchema: NEWS_SUMMARY_RESPONSE_SCHEMA,
        }),
      );
      const answer = NewsSummaryAnswerSchema.safeParse(json(result.text));
      if (!answer.success) {
        return failure(reply, new LlmError("BAD_RESPONSE", "the model's answer is not a title, a summary and a sentiment"));
      }
      const body: NewsSummaryResponse = {
        title: answer.data.title,
        summary: answer.data.summary,
        ...(answer.data.body === undefined ? {} : { body: answer.data.body }),
        sentiment: answer.data.sentiment,
        model: input.model,
        inputTokens: result.inputTokens,
        outputTokens: result.outputTokens,
      };
      return reply.send(body);
    } catch (error) {
      if (error instanceof LlmError) {
        request.log.warn({ kind: error.kind }, error.message);
        return failure(reply, error);
      }
      throw error;
    }
  });
}

function failure(reply: FastifyReply, error: LlmError): FastifyReply {
  const { status, code } = FAILURES[error.kind];
  return reply.code(status).send({ code, retryable: error.retryable, message: error.message });
}

function json(text: string): unknown {
  try {
    return JSON.parse(unfence(text));
  } catch {
    return undefined;
  }
}
