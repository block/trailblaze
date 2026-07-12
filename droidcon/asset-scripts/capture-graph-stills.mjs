// Captures asset-c-graph-normal.png + asset-c-graph-subway.png from a
// `trailblaze waypoint graph` HTML file. See public/asset-recipe-regenerate.txt.
//
//   GRAPH_HTML=/abs/path/waypoint-graph.html OUT_DIR=/abs/out bun capture-graph-stills.mjs
//
// Needs network: the graph HTML loads React Flow + dagre from esm.sh at runtime.
import { chromium } from 'playwright-core';

const GRAPH_HTML = process.env.GRAPH_HTML;
const OUT = process.env.OUT_DIR || '.';
const HUB_TEXT = process.env.HUB_TEXT || 'top-level Contacts list'; // node-card text of the hub waypoint
const FOCAL = process.env.FOCAL || 'contacts/ios/list';             // subway focal waypoint id
if (!GRAPH_HTML) { console.error('set GRAPH_HTML'); process.exit(1); }

const browser = await chromium.launch({ channel: 'chrome', headless: true });
const ctx = await browser.newContext({ viewport: { width: 1920, height: 1080 }, deviceScaleFactor: 2 });
const page = await ctx.newPage();
page.on('pageerror', (e) => console.log('PAGEERROR:', String(e).slice(0, 200)));

// Hash deep-link is read once when SubwayView mounts (on mode switch), so it can
// ride along from the start; map mode ignores it.
const hash = '#focal=' + encodeURIComponent(FOCAL) + '&depth=3';
await page.goto('file://' + GRAPH_HTML + hash, { waitUntil: 'load' });
await page.waitForFunction(() => document.querySelectorAll('.react-flow__node').length > 50, null, { timeout: 60000 });
await page.waitForTimeout(4000); // fitView animation settle

// ---- MAP: zoom onto the hub. NEVER click a node — selection triggers a
// focus-dim that Escape/pane-clicks do not reliably clear. Pan by dragging
// EMPTY canvas above the node row, zoom with wheel at viewport center.
const hub = page.locator('.react-flow__node', { hasText: HUB_TEXT }).first();
async function centerHub() {
  const box = await hub.boundingBox();
  if (!box) return;
  const dx = 960 - (box.x + box.width / 2), dy = 540 - (box.y + box.height / 2);
  if (Math.abs(dx) + Math.abs(dy) > 20) {
    await page.mouse.move(960, 200);
    await page.mouse.down();
    await page.mouse.move(960 + dx, 200 + dy, { steps: 8 });
    await page.mouse.up();
    await page.waitForTimeout(300);
  }
}
// 8 zoom ticks ≈ the framing with ~7 legible waypoint cards + edge labels.
for (let i = 0; i < 8; i++) { await centerHub(); await page.mouse.move(960, 540); await page.mouse.wheel(0, -420); await page.waitForTimeout(400); }
await centerHub(); await page.waitForTimeout(1200);
await page.screenshot({ path: OUT + '/asset-c-graph-normal.png' });
console.log('wrote asset-c-graph-normal.png');

// ---- SUBWAY: mode switch mounts SubwayView, which reads the hash focal.
await page.getByRole('button', { name: 'Subway' }).click();
await page.waitForTimeout(6000); // relayout + center animation
const focal = await page.evaluate(() => document.querySelector('.subway-trailhead-picker select')?.value || null);
if (focal !== FOCAL) {
  await page.locator('.subway-trailhead-picker select').first().selectOption({ value: FOCAL });
  await page.waitForTimeout(6000);
}
// Close the detail panel if a node auto-selected.
const closeBtn = page.locator('.detail-panel button', { hasText: '×' }).first();
if (await closeBtn.count()) { await closeBtn.click().catch(() => {}); await page.waitForTimeout(2500); }
const st = await page.evaluate(() => ({ nodes: document.querySelectorAll('.react-flow__node').length, panel: !!document.querySelector('.detail-panel') }));
console.log('subway state:', JSON.stringify(st)); // expect ~47 nodes, panel:false
await page.waitForTimeout(2000);
await page.screenshot({ path: OUT + '/asset-c-graph-subway.png' });
console.log('wrote asset-c-graph-subway.png');

await browser.close();
