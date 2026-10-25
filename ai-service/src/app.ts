import { createHash, timingSafeEqual } from "node:crypto";
import type Bottleneck from "bottleneck";
import Fastify, { type FastifyInstance, type FastifyServerOptions } from "fastify";
import type { LlmProvider } from "./llm/provider.js";
import { registerNewsSummary } from "./routes/newsSummary.js";

/** The header the backend proves itself with; nothing but the backend calls this service. */
export const SECRET_HEADER = "x-ai-service-secret";

export interface AppDeps {
  sharedSecret: string;
  provider: LlmProvider;
  limiter: Bottleneck;
  logger?: FastifyServerOptions["logger"];
}

/**
 * The HTTP application, without listening: the tests drive it with `inject`. Every route but the health check needs
 * the shared secret, compared in constant time. The secret header is redacted from the logs, as is any other
 * authorization.
 */
export function buildApp(deps: AppDeps): FastifyInstance {
  const app = Fastify({
    logger: deps.logger ?? false,
    bodyLimit: 256 * 1024,
  });
  const expected = digest(deps.sharedSecret);

  app.get("/health", async () => ({ status: "UP", provider: deps.provider.name }));

  app.addHook("onRequest", async (request, reply) => {
    if (request.url.split("?")[0] === "/health") {
      return;
    }
    const presented = request.headers[SECRET_HEADER];
    if (typeof presented !== "string" || !timingSafeEqual(digest(presented), expected)) {
      return reply.code(401).send({ code: "UNAUTHORIZED", retryable: false });
    }
  });

  registerNewsSummary(app, { provider: deps.provider, limiter: deps.limiter });
  return app;
}

/** A fixed-length digest, so two secrets of different lengths compare in the same time. */
function digest(value: string): Buffer {
  return createHash("sha256").update(value, "utf8").digest();
}
