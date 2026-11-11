import type Bottleneck from "bottleneck";
import type { FastifyInstance } from "fastify";
import { LlmError, type LlmProvider } from "../llm/provider.js";
import { newsSummaryUserPrompt, unfence } from "../prompts/newsSummary.js";
import {
  NEWS_SUMMARY_RESPONSE_SCHEMA,
  NewsSummaryAnswerSchema,
  NewsSummaryRequestSchema,
  type NewsSummaryResponse,
} from "../schemas/newsSummary.js";
import { failure, invalid } from "./failures.js";

export interface NewsSummaryRouteDeps {
  provider: LlmProvider;
  limiter: Bottleneck;
}

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
      return invalid(reply, parsed.error.issues);
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

function json(text: string): unknown {
  try {
    return JSON.parse(unfence(text));
  } catch {
    return undefined;
  }
}
