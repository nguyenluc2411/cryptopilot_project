import { buildApp, SECRET_HEADER } from "./app.js";
import { loadEnv } from "./config/env.js";
import { createLimiter } from "./limiter/limiter.js";
import { createProvider } from "./llm/index.js";

const env = loadEnv();
const app = buildApp({
  sharedSecret: env.AI_SERVICE_SHARED_SECRET,
  provider: createProvider(env),
  limiter: createLimiter({ requestsPerMinute: env.LLM_REQUESTS_PER_MINUTE, maxConcurrent: env.LLM_MAX_CONCURRENT }),
  logger: { level: "info", redact: [`req.headers["${SECRET_HEADER}"]`, "req.headers.authorization"] },
});

const shutdown = async (): Promise<void> => {
  await app.close();
  process.exit(0);
};
process.on("SIGINT", () => void shutdown());
process.on("SIGTERM", () => void shutdown());

await app.listen({ host: env.HOST, port: env.PORT });
