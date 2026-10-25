import Bottleneck from "bottleneck";

export interface LimiterOptions {
  requestsPerMinute: number;
  maxConcurrent: number;
}

/**
 * Keeps the calls to the provider under its rate limit (TECHNICAL_DESIGN 7.14: requests are queued to respect the
 * provider's limit): at most this many per rolling minute and this many at once. A request over the limit waits in
 * the queue rather than being refused, so a burst of articles is spread out instead of earning 429s.
 */
export function createLimiter(options: LimiterOptions): Bottleneck {
  return new Bottleneck({
    maxConcurrent: options.maxConcurrent,
    reservoir: options.requestsPerMinute,
    reservoirRefreshAmount: options.requestsPerMinute,
    reservoirRefreshInterval: 60_000,
  });
}
