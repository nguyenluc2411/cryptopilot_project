import { defineConfig } from "vitest/config";

// TECHNICAL_DESIGN 11: the AI Service keeps at least 80 % coverage. The entry point only wires the real
// environment and the network together, which the tests replace, so it is left out of the measure.
export default defineConfig({
  test: {
    include: ["test/**/*.test.ts"],
    coverage: {
      provider: "v8",
      include: ["src/**/*.ts"],
      exclude: ["src/server.ts"],
      thresholds: { lines: 80, branches: 80, functions: 80, statements: 80 },
    },
  },
});
