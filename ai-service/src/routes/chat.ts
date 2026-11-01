import type Bottleneck from "bottleneck";
import type { FastifyInstance } from "fastify";
import { LlmError, type LlmProvider } from "../llm/provider.js";
import { chatTurns } from "../prompts/chat.js";
import { ChatRequestSchema, type ChatResponse } from "../schemas/chat.js";
import { failure, invalid } from "./failures.js";

export interface ChatRouteDeps {
  provider: LlmProvider;
  limiter: Bottleneck;
}

/**
 * `POST /v1/chat`, NSF-16 (TECHNICAL_DESIGN 7.14 step 3): one question with its history and context in, the model's
 * answer and the token usage out. The request is checked before anything reaches the provider; the call goes through
 * the assistant's own limiter, so it never queues behind the news summaries. Nothing is stored here.
 */
export function registerChat(app: FastifyInstance, deps: ChatRouteDeps): void {
  app.post("/v1/chat", async (request, reply) => {
    const parsed = ChatRequestSchema.safeParse(request.body);
    if (!parsed.success) {
      return invalid(reply, parsed.error.issues);
    }
    const input = parsed.data;
    try {
      const result = await deps.limiter.schedule(() =>
        deps.provider.generateText({
          model: input.model,
          systemPrompt: input.systemPrompt,
          turns: chatTurns(input),
          temperature: input.temperature,
          maxOutputTokens: input.maxOutputTokens,
        }),
      );
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
    }
  });
}
