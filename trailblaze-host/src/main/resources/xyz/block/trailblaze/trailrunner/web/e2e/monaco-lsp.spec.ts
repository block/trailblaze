// Hermetic Playwright smoke for the scripted-tool editor's LSP client module. It serves only
// `app/editor/monaco-lsp.js` (via the e2e static server) and asserts the module:
//   1. parses + runs in a real browser without throwing, and
//   2. publishes `window.TBMonaco.mountTypescript` — the exact contract `tools.jsx` feature-detects
//      to choose the Monaco editor over the CodeMirror fallback.
// A syntax error or a broken IIFE would otherwise silently disable the whole LSP path (the editor
// would just quietly fall back), so this guards the registration contract.
//
// It does NOT load Monaco itself (that's lazy-loaded from the CDN only when mountTypescript() is
// actually called), so the test stays hermetic — no network, no daemon. Like devices.spec.ts, this
// is a LOCAL/manual check, not a CI gate (Playwright isn't resolvable via the configured registry).
// Run: `bun run test` from web/e2e/.
import { test, expect } from "@playwright/test";

test("monaco-lsp.js publishes window.TBMonaco.mountTypescript", async ({ page }) => {
  const pageErrors: string[] = [];
  page.on("pageerror", (e) => pageErrors.push(String(e)));

  await page.goto("/e2e/monaco-smoke.html");

  // (1) the module ran without throwing
  expect(pageErrors, `unexpected page errors: ${pageErrors.join("; ")}`).toEqual([]);

  // (2) it published the mount contract the React editor depends on
  expect(await page.evaluate(() => typeof (window as { TBMonaco?: { mountTypescript?: unknown } }).TBMonaco?.mountTypescript)).toBe("function");
});
