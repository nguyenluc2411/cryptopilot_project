import { z } from "zod";

/** A comma-separated list, blanks dropped: several keys of one provider, tried in turn when one is rate limited. */
const commaList = z
  .string()
  .transform((value) =>
    value
      .split(",")
      .map((item) => item.trim())
      .filter((item) => item.length > 0),
  );

/**
 * The environment the service runs in. Only this service reads the LLM key (ADR-004): the backend never holds it.
 * The model, the prompt and the generation settings are not here; the backend sends them with every request, so
 * changing them needs no deployment of this service (TECHNICAL_DESIGN 7.14).
 */
export const EnvSchema = z.object({
  HOST: z.string().min(1).default("127.0.0.1"),
  PORT: z.coerce.number().int().min(1).max(65_535).default(7070),
  AI_SERVICE_SHARED_SECRET: z.string().min(32, "must be at least 32 characters"),
  LLM_PROVIDER: z.enum(["gemini"]).default("gemini"),
  LLM_API_KEY: commaList.pipe(z.array(z.string()).min(1, "needs at least one key")),
  LLM_BASE_URL: z.url().optional(),
  LLM_TIMEOUT_MS: z.coerce.number().int().positive().default(60_000),
  LLM_REQUESTS_PER_MINUTE: z.coerce.number().int().positive().default(10),
  LLM_MAX_CONCURRENT: z.coerce.number().int().positive().default(2),
  GEMINI_THINKING_LEVEL: z.enum(["minimal", "low", "medium", "high"]).optional(),
});

export type Env = z.infer<typeof EnvSchema>;

/** The validated environment; the error names every wrong variable and never echoes a value. */
export function loadEnv(source: Record<string, string | undefined> = process.env): Env {
  const parsed = EnvSchema.safeParse(source);
  if (!parsed.success) {
    const problems = parsed.error.issues.map((issue) => `${issue.path.join(".")}: ${issue.message}`);
    throw new Error(`The AI Service environment is not valid: ${problems.join("; ")}`);
  }
  return parsed.data;
}
