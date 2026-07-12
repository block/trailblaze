// Captures asset-a2-report-1.png (overview/timeline), asset-a2-report-3.png (LLM tab)
// and asset-a2-report-extra-grid.png (grid storyboard) from a self-contained
// `trailblaze report <session-id>` HTML file.
//
//   REPORT_HTML=/abs/path/report-interactive.html OUT_DIR=/abs/out bun capture-report-tabs.mjs
import { chromium } from 'playwright-core';

const REPORT_HTML = process.env.REPORT_HTML;
const OUT = process.env.OUT_DIR || '.';
if (!REPORT_HTML) { console.error('set REPORT_HTML'); process.exit(1); }

const browser = await chromium.launch({ channel: 'chrome', headless: true });
const ctx = await browser.newContext({ viewport: { width: 1920, height: 1080 }, deviceScaleFactor: 2 });
const page = await ctx.newPage();
await page.goto('file://' + REPORT_HTML, { waitUntil: 'load' });
await page.waitForTimeout(3500);

// Default view = Timeline: PASSED header + 39-step timeline + device pane.
await page.screenshot({ path: OUT + '/asset-a2-report-1.png' });
console.log('wrote asset-a2-report-1.png');

// LLM tab: session totals (calls, tokens, $cost, cache %) + per-call list.
await page.getByText(/LLM \(\d+\)/).first().click();
await page.waitForTimeout(2000);
await page.screenshot({ path: OUT + '/asset-a2-report-3.png' });
console.log('wrote asset-a2-report-3.png');

// Grid tab: storyboard of every step's device screenshot.
await page.locator('text=Grid').first().click();
await page.waitForTimeout(2000);
await page.screenshot({ path: OUT + '/asset-a2-report-extra-grid.png' });
console.log('wrote asset-a2-report-extra-grid.png');

await browser.close();
