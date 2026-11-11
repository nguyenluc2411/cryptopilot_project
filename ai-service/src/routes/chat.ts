import type Bottleneck from "bottleneck";
import type { FastifyInstance } from "fastify";
import { LlmError, type LlmProvider } from "../llm/provider.js";
import { chatTurns } from "../prompts/chat.js";
import { ChatRequestSchema, type ChatResponse } from "../schemas/chat.js";
import { failure, invalid } from "./failures.js";

/** The header the backend states its own wait in, in milliseconds (the answer timeout of NSF-16). */
export const BUDGET_HEADER = "x-request-budget-ms";

/** The budget when neither the backend nor the configuration names one: the backend waits 60 s (SRS 3.9.1). */
export const DEFAULT_CHAT_BUDGET_MS = 60_000;

export interface ChatRouteDeps {
  provider: LlmProvider;
  limiter: Bottleneck;
  /** The longest a question may take here, queue included; a shorter budget from the backend wins. */
  budgetMs?: number;
  /** The signal that fires after a budget; the tests fire it themselves. */
  deadline?: (budgetMs: number) => AbortSignal;
}

/**
 * `POST /v1/chat`, NSF-16 (TECHNICAL_DESIGN 7.14 step 3): one question with its history and context in, the model's
 * answer and the token usage out. The request is checked before anything reaches the provider; the call goes through
 * the assistant's own limiter, so it never queues behind the news summaries. Nothing is stored here.
 *
 * Every question has a deadline, counted from its arrival: the backend's budget, or the configured one. A question
 * still queued when the deadline passes, or when the backend has hung up, is dropped without calling the provider,
 * and a call in flight is aborted at the deadline (Nygard 2018: work the caller abandoned is shed, or it takes the
 * capacity the next caller needs).
 */
export function registerChat(app: FastifyInstance, deps: ChatRouteDeps): void {
  const configuredBudget = deps.budgetMs ?? DEFAULT_CHAT_BUDGET_MS;
  const deadlineAfter = deps.deadline ?? ((ms: number) => AbortSignal.timeout(ms));
  app.post("/v1/chat", async (request, reply) => {
    const parsed = ChatRequestSchema.safeParse(request.body);
    if (!parsed.success) {
      return invalid(reply, parsed.error.issues);
    }
    const input = parsed.data;
    const caller = new AbortController();
    const hungUp = (): void => {
      if (!reply.raw.writableEnded) {
        caller.abort();
      }
    };
    reply.raw.once("close", hungUp);
    const signal = AbortSignal.any([
      deadlineAfter(budgetOf(request.headers[BUDGET_HEADER], configuredBudget)),
      caller.signal,
    ]);
    try {
      const result = await deps.limiter.schedule(async () => {
        if (signal.aborted) {
          throw new LlmError("TIMEOUT", "the question was dropped: its caller stopped waiting while it was queued");
        }
        return deps.provider.generateText({
          model: input.model,
          systemPrompt: input.systemPrompt,
          turns: chatTurns(input),
          temperature: input.temperature,
          maxOutputTokens: input.maxOutputTokens,
          signal,
        });
      });
      const body: ChatResponse = {
        text: result.text,
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
    } finally {
      reply.raw.off("close", hungUp);
    }
  });
}

/** The backend's budget when it sends a valid one no longer than the configured budget, else the configured one. */
function budgetOf(header: string | string[] | undefined, configured: number): number {
  const stated = typeof header === "string" ? Number(header) : Number.NaN;
  return Number.isInteger(stated) && stated > 0 ? Math.min(stated, configured) : configured;
}
