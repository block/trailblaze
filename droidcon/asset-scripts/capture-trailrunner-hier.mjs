// Captures asset-a2-report-2.png: TrailRunner with a run open, stepped to a
// moment that has view-hierarchy data, hierarchy bounding-box overlay toggled ON.
// Needs a daemon serving /trailrunner (see public/asset-recipe-regenerate.txt §5).
//
//   TR_URL=http://localhost:55155/trailrunner/ OUT_DIR=/abs/out bun capture-trailrunner-hier.mjs
//
// Row targeting: the runs filter matches TITLES only (not session ids), so the
// row is picked by title + duration + status text, which must uniquely identify
// the run (e.g. 'create a contact' + '1m 50s' + 'Passed').
import { chromium } from 'playwright-core';

const TR_URL = process.env.TR_URL || 'http://localhost:55155/trailrunner/';
const OUT = process.env.OUT_DIR || '.';
const FILTER = process.env.RUN_FILTER || 'create a contact';
const ROW_TOKENS = (process.env.ROW_TOKENS || 'create a contact,1m 50s,Passed').split(',');

const browser = await chromium.launch({ channel: 'chrome', headless: true });
const ctx = await browser.newContext({ viewport: { width: 1920, height: 1080 }, deviceScaleFactor: 2, colorScheme: 'dark' });
const page = await ctx.newPage();
// TrailRunner keeps visited screens MOUNTED (hidden) — always filter visible.
const vis = (sel) => page.locator(sel).filter({ visible: true });

await page.goto(TR_URL, { waitUntil: 'load' });
await page.waitForTimeout(8000);

// Go to Runs (⌘O), retry via the Completed nav item. The filter input's
// placeholder is NOT in body.innerText — wait on the element itself.
let ok = false;
for (let a = 0; a < 6 && !ok; a++) {
  if (a % 2 === 0) await page.keyboard.press('Meta+o').catch(() => {});
  else await vis('text=Completed').last().click({ timeout: 5000 }).catch(() => {});
  ok = await page.waitForFunction(() => { const i = document.querySelector('input[placeholder*="Filter runs"]'); return i && i.offsetParent !== null; }, null, { timeout: 8000 }).then(() => true).catch(() => false);
}
await vis('input[placeholder*="Filter runs"]').fill(FILTER);
await page.waitForTimeout(2500);

// Geometry-click the row matching all tokens (avoids hidden-DOM duplicates).
const rowPt = await page.evaluate((tokens) => {
  const rows = [...document.querySelectorAll('div,li,button,a')].filter(e => {
    const t = e.innerText || '';
    if (!tokens.every(tok => t.includes(tok))) return false;
    if (t.length > 400) return false;
    const r = e.getBoundingClientRect();
    return r.width > 100 && r.height > 20 && r.height < 200 && e.offsetParent !== null;
  });
  if (!rows.length) return null;
  const r = rows[rows.length - 1].getBoundingClientRect();
  return { x: r.x + r.width / 2, y: r.y + r.height / 2 };
}, ROW_TOKENS);
if (!rowPt) { console.error('run row not found for tokens', ROW_TOKENS); await browser.close(); process.exit(1); }
await page.mouse.click(rowPt.x, rowPt.y);
await page.waitForFunction(() => { const b = document.querySelector('[data-testid=view-hierarchy-btn]'); return b && b.offsetParent !== null; }, null, { timeout: 120000 });
await page.waitForTimeout(3000);

// Walk moments with the step transport (title="Next step" — do NOT click the
// seedling "1 ›" badges, those expand delegation chains). The hierarchy button
// enables only on moments whose log carries viewHierarchy; take the 2nd hit
// (usually a rich mid-run screen).
const btn = vis('[data-testid=view-hierarchy-btn]').first();
const next = vis('button[title="Next step"]').first();
let enabled = await btn.isEnabled();
let clicks = 0;
const hits = [];
while (clicks < 45) {
  if (enabled) {
    hits.push(await page.evaluate(() => (document.body.innerText.match(/Step \d+ \/ \d+/) || [''])[0]));
    if (hits.length >= 2) break;
  }
  if (!(await next.isEnabled().catch(() => false))) break;
  await next.click();
  clicks++;
  await page.waitForTimeout(450);
  enabled = await btn.isEnabled();
}
console.log('hierarchy-enabled moments hit:', JSON.stringify(hits));
if (!enabled) { console.error('no hierarchy-enabled moment found'); await browser.close(); process.exit(1); }
await btn.click(); // toggles overlay; button becomes "Hide hierarchy"
await page.waitForTimeout(3500);
await page.screenshot({ path: OUT + '/asset-a2-report-2.png' });
console.log('wrote asset-a2-report-2.png');
await browser.close();
