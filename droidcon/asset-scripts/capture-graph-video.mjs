// Records the ~28s asset-c-graph.mp4 pan/zoom tour of the waypoint graph.
//
//   GRAPH_HTML=/abs/path/waypoint-graph.html OUT_DIR=/abs/out bun capture-graph-video.mjs
//   then convert with the ffmpeg line this script prints (trims the load screen).
import { chromium } from 'playwright-core';

const GRAPH_HTML = process.env.GRAPH_HTML;
const OUT = process.env.OUT_DIR || '.';
const HUB_TEXT = process.env.HUB_TEXT || 'top-level Contacts list';
const FOCAL = process.env.FOCAL || 'contacts/ios/list';
if (!GRAPH_HTML) { console.error('set GRAPH_HTML'); process.exit(1); }

const browser = await chromium.launch({ channel: 'chrome', headless: true });
const ctx = await browser.newContext({
  viewport: { width: 1920, height: 1080 },
  recordVideo: { dir: OUT + '/video', size: { width: 1920, height: 1080 } },
});
const page = await ctx.newPage();
const t0 = Date.now();

await page.goto('file://' + GRAPH_HTML + '#focal=' + encodeURIComponent(FOCAL) + '&depth=3', { waitUntil: 'load' });
await page.waitForFunction(() => document.querySelectorAll('.react-flow__node').length > 50, null, { timeout: 60000 });
await page.waitForTimeout(3500);
const tReady = (Date.now() - t0) / 1000;

// Act 1: full map, hold
await page.waitForTimeout(2500);

// Act 2: glide toward the hub, zooming gently (no node clicks — focus-dim)
const hub = page.locator('.react-flow__node', { hasText: HUB_TEXT }).first();
for (let step = 0; step < 8; step++) {
  const box = await hub.boundingBox();
  if (!box) break;
  const dx = 960 - (box.x + box.width / 2), dy = 540 - (box.y + box.height / 2);
  if (Math.abs(dx) + Math.abs(dy) > 20) {
    await page.mouse.move(960, 230);
    await page.mouse.down();
    await page.mouse.move(960 + dx * 0.6, 230 + dy * 0.6, { steps: 18 });
    await page.mouse.up();
    await page.waitForTimeout(220);
  }
  await page.mouse.move(960, 540);
  await page.mouse.wheel(0, -210);
  await page.waitForTimeout(420);
}
await page.waitForTimeout(2500);

// Act 3: lateral drift along the row
await page.mouse.move(1400, 230);
await page.mouse.down();
await page.mouse.move(500, 230, { steps: 45 });
await page.mouse.up();
await page.waitForTimeout(1800);

// Act 4: subway finale (hash focal read on mount → concentric ring)
await page.getByRole('button', { name: 'Subway' }).click();
await page.waitForTimeout(6500);
await page.mouse.move(960, 540);
await page.mouse.wheel(0, 140);
await page.waitForTimeout(1200);
await page.mouse.wheel(0, 140);
await page.waitForTimeout(4000);

await ctx.close(); // flushes the webm
await browser.close();
console.log('READY_AT_SECONDS=' + tReady.toFixed(2));
console.log('Convert with:');
console.log(`  ffmpeg -y -ss ${tReady.toFixed(2)} -i ${OUT}/video/*.webm -c:v libx264 -pix_fmt yuv420p -movflags +faststart -crf 20 ${OUT}/asset-c-graph.mp4`);
