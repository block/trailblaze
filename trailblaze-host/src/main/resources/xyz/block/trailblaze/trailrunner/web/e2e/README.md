# Trail Runner web — Playwright smoke test

`devices.spec.ts` is a hermetic Playwright smoke test for the typed daemon-RPC bundle: it serves
only `daemon.bundle.js`, mocks the daemon endpoint, and asserts the bundle publishes a working
`window.TbRpc` that round-trips a typed call.

## ⚠️ This does NOT run in CI

Automated `bun test` runs are scoped to `web/app`, which deliberately **excludes** this `e2e/`
directory: `*.spec.ts` here needs `@playwright/test` plus a downloaded browser
(`bun run install-browser`), which the hermetic test gate deliberately avoids. The browser-free
behavior gate (`app/rpc/bundle.test.ts`) is what actually gates the bundle: it loads the built
`daemon.bundle.js` and asserts `window.TbRpc` works. The bundle is generated at build time
(`:trailblaze-host:bundleTrailRunnerDaemon`, gitignored), so there's no separate committed-bundle
drift gate. Treat this spec as a local/manual check, not a merge gate: run the Gradle task first
(or a desktop build) so the static server can serve the bundle.

## Run it locally

From this `e2e/` directory:

```bash
bun install
bun run install-browser   # playwright install chromium
bun run test              # playwright test
```
