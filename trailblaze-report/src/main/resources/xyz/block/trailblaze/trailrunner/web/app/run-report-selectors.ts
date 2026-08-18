// Selector suggestions for the report's UI Inspector: pure helpers around the embedded Kotlin/JS
// selector engine (:trailblaze-selector-engine-js — the daemon's own TrailblazeNodeSelectorGenerator
// / Resolver compiled to JS, so what the inspector suggests is byte-identical to what the recorder
// writes into a trail). No DOM and no viewer state: the viewer (run-report-viewer.ts) owns the
// suggestions container's lifecycle and event wiring; everything here is testable with plain JSON.
//
// The engine rides in the report as an inert gzip+base64 chunk (#tb-selector-engine, embedded by
// buildMultiReportHtml only when a session carries hierarchies) and is inflated + evaluated on
// FIRST use only — never on page load. Every absence path (no chunk, malformed chunk, partial
// global) resolves to null so the inspector renders exactly as it does today, with no suggestions
// section and no errors.
import type {
  TrailblazeNodeSelector,
  TrailblazeSelectorAnalysis,
  TrailblazeSelectorOption,
} from '../../../../../../../../../../sdks/typescript/src/generated/selectors';
import type { SelectorEngine } from '../../../../../../../../../../trailblaze-selector-engine-js/src/typescript/selector-engine';
import { loadSelectorEngine } from '../../../../../../../../../../trailblaze-selector-engine-js/src/typescript/selector-engine';
import { inflateGzText, jsonToYaml } from './run-report-payload';

// Escape for interpolation into markup this module emits. Local copy (this module is pure and
// dependency-free); same character set as the inspector's escInsp.
function escSel(s: unknown): string {
  return String(s == null ? '' : s).replace(/[<>&"]/g, (c) => ({ '<': '&lt;', '>': '&gt;', '&': '&amp;', '"': '&quot;' }[c]));
}

/**
 * True when a hierarchy is a `TrailblazeNode` tree the selector engine can analyze. The engine's
 * domain is exactly the trees the daemon's generator records against — every TrailblazeNode
 * carries a required `driverDetail`, which the legacy `ViewHierarchyTreeNode` shape (older logs;
 * the excluded TapSelectorV2 domain) never has. Legacy trees get no suggestions section.
 */
function isSelectorAnalyzableTree(hierarchy: unknown): boolean {
  return Boolean(hierarchy && typeof hierarchy === 'object' && !Array.isArray(hierarchy)
    && (hierarchy as { driverDetail?: unknown }).driverDetail
    && typeof (hierarchy as { driverDetail?: unknown }).driverDetail === 'object');
}

// Inspector keys are PRE-ORDER indices (inspectorModel assigns `key = nodes.length` before
// walking `children`, skipping non-object entries without consuming a key); the engine targets
// nodes by their `nodeId`. This walk replicates that exact order so key N here is node N there.
// Memoized per hierarchy object — the viewer re-reads it on every commit.
const keyNodeIdsCache = new WeakMap<object, Array<number>>();
function inspectorKeyNodeIds(hierarchy: object): Array<number> {
  const cached = keyNodeIdsCache.get(hierarchy);
  if (cached) return cached;
  const ids: number[] = [];
  const walk = (raw: any) => {
    if (!raw || typeof raw !== 'object') return;
    // Absent nodeId decodes as the Kotlin default (0) on the engine side too, so mirror it.
    ids.push(typeof raw.nodeId === 'number' && Number.isFinite(raw.nodeId) ? raw.nodeId : 0);
    for (const child of Array.isArray(raw.children) ? raw.children : []) walk(child);
  };
  walk(hierarchy);
  keyNodeIdsCache.set(hierarchy, ids);
  return ids;
}

/** The `nodeId` behind one inspector tree key, or null when the key isn't in the tree. */
function nodeIdForInspectorKey(hierarchy: unknown, key: number): number | null {
  if (!hierarchy || typeof hierarchy !== 'object') return null;
  const ids = inspectorKeyNodeIds(hierarchy as object);
  return Number.isInteger(key) && key >= 0 && key < ids.length ? ids[key] : null;
}

/**
 * The inspector tree key behind one `nodeId` (first pre-order occurrence), or null when the id
 * isn't in the tree — the reverse of [nodeIdForInspectorKey], used to resolve an engine-reported
 * hit node back to its inspector row (label + bounds) for the mismatch visualization.
 */
function inspectorKeyForNodeId(hierarchy: unknown, nodeId: number): number | null {
  if (!hierarchy || typeof hierarchy !== 'object') return null;
  const key = inspectorKeyNodeIds(hierarchy as object).indexOf(nodeId);
  return key >= 0 ? key : null;
}

/**
 * One suggestion's copyable YAML: the `nodeSelector:` block exactly as the recorder writes it in a
 * trail file. jsonToYaml is the same emitter the report's tool-call rendering uses (the TS port of
 * TrailblazeYaml.jsonToYaml), so the pasted block is byte-faithful to a recorded
 * `tapOnElementBySelector` / `assertVisibleBySelector` entry — no invented dialect.
 */
function selectorSuggestionYaml(selector: TrailblazeNodeSelector): string {
  return jsonToYaml({ nodeSelector: selector });
}

/**
 * One card's mismatch-visualization payload: where the selector's tap would land and which node
 * would actually receive it. Aligned by index with the returned `yamls` (null for cards whose
 * resolved tap hits the target, or with no resolved center); the viewer paints it onto the
 * screenshot when the card is engaged.
 */
interface SelectorMismatchViz {
  tapX: number;
  tapY: number;
  /** `nodeId` of the node that would actually receive the tap, when the engine reported one. */
  hitNodeId: number | null;
}

/** Rendering context for [selectorSuggestionsHtml] — all optional, all display-only. */
interface SelectorSuggestionsContext {
  /** Tree-row label of the node the suggestions describe (shown in the section header). */
  subjectLabel?: string | null;
  /** True when the subject is the HOVERED node rather than the committed selection. */
  preview?: boolean;
  /** Resolves an engine-reported hit `nodeId` to its tree-row label, for the mismatch copy. */
  hitLabelFor?: (nodeId: number) => string | null;
}

/**
 * Markup for the ranked suggestions of one node, mirroring the Wasm inspector's Selector
 * Analysis section: content-based options first (the generator's pick marked BEST), then the
 * content-free structural options under their own heading. Each card carries the strategy, a
 * uniqueness badge (UNIQUE green / N MATCHES amber / NO MATCH red), the resolved-tap
 * verification line (naming the intercepting element when the tap would land elsewhere), the
 * trail-file YAML, and a copy button (`data-inspselcopy` = index into the returned `yamls`,
 * which the viewer holds for the clipboard). Mismatch cards additionally carry
 * `data-inspselviz` = the same index into the returned `viz`, the viewer's engagement hook for
 * painting the mismatch onto the screenshot. An absent/failed analysis renders NOTHING —
 * graceful absence is the contract.
 */
function selectorSuggestionsHtml(
  analysis: TrailblazeSelectorAnalysis | null | undefined,
  context: SelectorSuggestionsContext = {},
): { html: string; yamls: string[]; viz: Array<SelectorMismatchViz | null> } {
  const yamls: string[] = [];
  const viz: Array<SelectorMismatchViz | null> = [];
  const options = analysis && !analysis.error && Array.isArray(analysis.options) ? analysis.options : [];
  if (!options.length) return { html: '', yamls, viz };
  const card = (option: TrailblazeSelectorOption) => {
    const yaml = selectorSuggestionYaml(option.selector);
    const idx = yamls.length;
    yamls.push(yaml);
    const badges = [
      option.matchCount === 0 ? '<span class="inspselbadge nomatch">NO MATCH</span>'
        : option.matchCount === 1 ? '<span class="inspselbadge unique">UNIQUE</span>'
          : `<span class="inspselbadge multi">${option.matchCount} MATCHES</span>`,
      option.isBest ? '<span class="inspselbadge bestpick">BEST</span>' : '',
    ].join('');
    // Verification line, same semantics as the Wasm inspector: the resolved center is hit-tested
    // against the tree, so a selector that matches but whose tap would land on an overlapping
    // element is called out even when it is unique. A mismatch names the intercepting element
    // when its identity resolves, and the card becomes engageable (data-inspselviz) so the
    // viewer can paint the mismatch onto the screenshot.
    const mismatch = option.resolvedCenterX != null && option.resolvedCenterY != null && !option.hitsTarget;
    viz.push(mismatch
      ? { tapX: option.resolvedCenterX as number, tapY: option.resolvedCenterY as number, hitNodeId: option.hitNodeId != null ? option.hitNodeId : null }
      : null);
    const hitLabel = mismatch && option.hitNodeId != null && context.hitLabelFor ? context.hitLabelFor(option.hitNodeId) : null;
    // Escaped like every other interpolation here: the `!= null` guard above is a presence check,
    // not a type check, so nothing but escSel keeps a non-numeric center out of the markup.
    const tapAt = `Tap (${escSel(option.resolvedCenterX)}, ${escSel(option.resolvedCenterY)})`;
    const verify = option.resolvedCenterX != null && option.resolvedCenterY != null
      ? `<div class="inspselverify ${option.hitsTarget ? 'ok' : 'bad'}">${option.hitsTarget
        ? `${tapAt} hits this element`
        : (hitLabel
          ? `${tapAt} lands on ${escSel(hitLabel)} — not this element`
          : `${tapAt} would hit a different element`)
      }${mismatch ? '<span class="inspselvizhint">hover to visualize</span>' : ''}</div>`
      : (option.matchCount > 0 ? '<div class="inspselverify">No bounds — tap verification unavailable</div>' : '');
    return `<div class="inspselcard${option.isBest ? ' best' : ''}"${mismatch ? ` data-inspselviz="${idx}"` : ''}>`
      + `<div class="inspselhead"><span class="inspselstrategy">${escSel(String(option.strategy || '').replace(/^Structural: /, ''))}</span>`
      + `<span class="inspselbadges">${badges}</span>`
      + `<button class="inspselcopy" type="button" data-inspselcopy="${idx}" title="Copy the trail-file nodeSelector YAML">Copy</button></div>`
      + verify
      + `<pre class="mono inspselyaml">${escSel(yaml)}</pre>`
      + `</div>`;
  };
  const content = options.filter((o) => !String(o.strategy || '').startsWith('Structural:'));
  const structural = options.filter((o) => String(o.strategy || '').startsWith('Structural:'));
  // Section header names the subject so it's unambiguous which element the cards describe —
  // load-bearing now that suggestions follow hover: the "hover preview" chip separates a
  // transient preview from the committed selection's suggestions.
  const subject = context.subjectLabel != null && context.subjectLabel !== ''
    ? `<span class="inspselsubject mono">${escSel(context.subjectLabel)}</span>` : '';
  const previewChip = context.preview ? '<span class="inspselpreviewchip">hover preview</span>' : '';
  const parts = [`<div class="inspseltitle">Selector suggestions${subject}${previewChip}</div>`];
  content.forEach((o) => parts.push(card(o)));
  if (structural.length) {
    parts.push('<div class="inspselgroup">Structural (content-free)</div>');
    structural.forEach((o) => parts.push(card(o)));
  }
  return { html: parts.join(''), yamls, viz };
}

/**
 * Screenshot-overlay markup for one engaged mismatch: the intended element's bounds, the bounds
 * of the element that would actually receive the tap, the resolved tap point, and a small
 * legend. Percentage-positioned against `dims` (the same device-coordinate anchor the
 * inspector's bounds rects use), so it scales with the image. Pure markup — the viewer owns the
 * layer element it is written into and clears it on disengage. Empty string when there is
 * nothing meaningful to paint (no dims, or neither bounds available).
 */
function mismatchVizHtml({ target, hit, tap, dims }: {
  target: { x1: number; y1: number; x2: number; y2: number } | null;
  hit: { x1: number; y1: number; x2: number; y2: number } | null;
  tap: { x: number; y: number };
  dims: { w: number; h: number } | null;
}): string {
  if (!dims || !(dims.w > 0) || !(dims.h > 0)) return '';
  if (!target && !hit) return '';
  const pct = (v: number, span: number) => `${((v / span) * 100).toFixed(3)}%`;
  const rect = (b: { x1: number; y1: number; x2: number; y2: number }, cls: string) =>
    `<div class="inspselvizrect ${cls}" style="left:${pct(b.x1, dims.w)};top:${pct(b.y1, dims.h)};width:${pct(b.x2 - b.x1, dims.w)};height:${pct(b.y2 - b.y1, dims.h)}"></div>`;
  const parts: string[] = [];
  if (target) parts.push(rect(target, 'intended'));
  if (hit) parts.push(rect(hit, 'actual'));
  parts.push(`<span class="inspselviztap" style="left:${pct(tap.x, dims.w)};top:${pct(tap.y, dims.h)}"></span>`);
  parts.push('<span class="inspselvizlegend">'
    + '<span class="k"><span class="sw intended"></span>this element</span>'
    + '<span class="k"><span class="sw actual"></span>actual tap target</span>'
    + '<span class="k"><span class="sw tappt"></span>tap point</span>'
    + '</span>');
  return parts.join('');
}

/**
 * Evaluate the embedded engine chunk (#tb-selector-engine: `{ js }` inline or `{ gz }` gzip+base64
 * — see packSelectorEngine in run-report-cli.ts) and return the typed engine, or null on ANY
 * absence/failure path. An engine global already present (the Trail Runner web app, tests stubbing
 * the documented contract) short-circuits the chunk entirely. Evaluation is Function-constructor
 * injection into the page — the bundle is a self-contained IIFE that installs
 * `globalThis.TrailblazeSelectorEngine`; `loadSelectorEngine()` then validates all three exports,
 * so a partial or incompatible global still reads as "engine absent" rather than throwing later.
 */
async function loadSelectorEngineFromChunk(chunk: SelectorEnginePayload | null | undefined): Promise<SelectorEngine | null> {
  const existing = loadSelectorEngine();
  if (existing) return existing;
  if (!chunk || typeof chunk !== 'object') return null;
  const code = typeof chunk.js === 'string' && chunk.js
    ? chunk.js
    : (typeof chunk.gz === 'string' && chunk.gz ? await inflateGzText(chunk.gz) : null);
  if (!code) return null;
  try {
    new Function(code)();
  } catch (_) {
    return null;
  }
  return loadSelectorEngine();
}

export { inspectorKeyForNodeId, isSelectorAnalyzableTree, loadSelectorEngine, loadSelectorEngineFromChunk, mismatchVizHtml, nodeIdForInspectorKey, selectorSuggestionsHtml, selectorSuggestionYaml };
export type { SelectorEngine, SelectorMismatchViz };
