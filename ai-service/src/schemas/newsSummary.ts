import { z } from "zod";

export const SENTIMENTS = ["BULLISH", "NEUTRAL", "BEARISH"] as const;

/**
 * What the backend sends for one article (NSF-11). The model and the instructions come with it, from the backend's
 * configuration, so nothing about the task is fixed in this service. The model name is checked as a bare identifier
 * because it becomes part of the provider's URL path.
 */
export const NewsSummaryRequestSchema = z
  .object({
    model: z.string().regex(/^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$/, "must be a model identifier"),
    systemPrompt: z.string().min(1).max(20_000),
    temperature: z.number().min(0).max(2),
    maxOutputTokens: z.number().int().min(16).max(8_192),
    article: z
      .object({
        title: z.string().min(1).max(500),
        source: z.string().max(100).optional(),
        text: z.string().min(1).max(40_000),
      })
      .strict(),
    /** Why the backend refused the previous answer for this article, so the retry can correct it. */
    feedback: z.string().min(1).max(1_000).optional(),
  })
  .strict();

export type NewsSummaryRequest = z.infer<typeof NewsSummaryRequestSchema>;

/** What the model must return; anything else is a bad answer, never passed on. */
export const NewsSummaryAnswerSchema = z.object({
  summary: z.string().trim().min(1),
  sentiment: z.enum(SENTIMENTS),
});

export type NewsSummaryAnswer = z.infer<typeof NewsSummaryAnswerSchema>;

/** The same shape for the provider's structured output, in Gemini's OpenAPI dialect. */
export const NEWS_SUMMARY_RESPONSE_SCHEMA: Record<string, unknown> = {
  type: "OBJECT",
  properties: {
    summary: { type: "STRING" },
    sentiment: { type: "STRING", enum: [...SENTIMENTS] },
  },
  required: ["summary", "sentiment"],
  propertyOrdering: ["summary", "sentiment"],
};

export interface NewsSummaryResponse extends NewsSummaryAnswer {
  model: string;
  inputTokens: number;
  outputTokens: number;
}
