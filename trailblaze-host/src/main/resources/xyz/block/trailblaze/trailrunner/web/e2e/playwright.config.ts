import { defineConfig } from "@playwright/test";

const PORT = Number(process.env.PORT ?? 4321);

// Smoke-test config for the typed daemon-RPC bundle. Spins up the tiny static server (Bun) and runs
// the spec against headless Chromium. Hermetic — no daemon, no device, no CDN.
export default defineConfig({
  testDir: ".",
  use: { baseURL: `http://localhost:${PORT}` },
  webServer: {
    command: "bun run static-server.ts",
    url: `http://localhost:${PORT}/e2e/smoke.html`,
    env: { PORT: String(PORT) },
    reuseExistingServer: !process.env.CI,
    timeout: 30_000,
  },
});
