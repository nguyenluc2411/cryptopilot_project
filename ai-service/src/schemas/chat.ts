import { z } from "zod";

/** TECHNICAL_DESIGN 7.14: the backend sends at most the ten most recent turns of the conversation. */
export const MAX_HISTORY_TURNS = 10;
/** One earlier turn; the backend cuts a longer answer before sending it, so a long history cannot burn the quota. */
export const MAX_TURN_CHARS = 8_000;
export const MAX_QUESTION_CHARS = 4_000;
/** The context snapshot as JSON text (T-074: indicators, setup, levels, futures metrics, plan, performance). */
export const MAX_CONTEXT_CHARS = 20_000;

const TurnSchema = z
  .object({
    role: z.enum(["user", "assistant"]),
    text: z.string().trim().min(1).max(MAX_TURN_CHARS),
  })
  .strict();

/**
 * What the backend sends for one question to the assistant (NSF-16). As for the news summary, the model, the system
 * prompt (BR-51: explain, never tell the Trader to buy or sell) and the generation settings come from the backend's
 * active AI configuration, so this service decides nothing about the task. It keeps no conversation: the backend
 * sends the history it stored with every question.
 */
export const ChatRequestSchema = z
  .object({
    model: z.string().regex(/^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$/, "must be a model identifier"),
    systemPrompt: z.string().min(1).max(20_000),
    temperature: z.number().min(0).max(2),
    maxOutputTokens: z.number().int().min(16).max(8_192),
    /** Oldest first, without the question itself. */
    history: z.array(TurnSchema).max(MAX_HISTORY_TURNS).default([]),
    question: z.string().trim().min(1).max(MAX_QUESTION_CHARS),
    /** The market and plan data the question is about; no personal data (BR-51), which the backend guarantees. */
    context: z
      .record(z.string(), z.unknown())
      .refine((value) => JSON.stringify(value).length <= MAX_CONTEXT_CHARS, {
        message: `must be at most ${MAX_CONTEXT_CHARS} characters as JSON`,
      })
      .optional(),
  })
  .strict();

export type ChatRequestBody = z.infer<typeof ChatRequestSchema>;

/** The answer as the model wrote it; the backend stores it and adds the disclaimer when it is missing (7.14 step 5). */
export interface ChatResponse {
  text: string;
  model: string;
  inputTokens: number;
  outputTokens: number;
}
