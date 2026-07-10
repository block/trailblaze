// @ts-nocheck -- migrated from .jsx; this file has pre-existing type errors from years of
// untyped legacy JS (mostly optional params/props without defaults, inferred by TS as required).
// Babel strips types at load time regardless, so the browser runtime is unaffected.
// Remove this pragma once the file's real errors are fixed; run `bun run typecheck` to see them.

function summarizeAnalyticsProps(props) {
  if (!props || typeof props !== 'object') return '';
  const entity = props.cdf_entity;
  const action = props.cdf_action;
  const skip = new Set(['cdf_entity', 'cdf_action']);
  const rest = Object.keys(props).filter((k) => !skip.has(k));
  const tag = (entity || action) ? [entity, action].filter(Boolean).join(' · ') : '';
  const extra = rest.slice(0, 2).map((k) => `${k}=${truncate(String(props[k]), 24)}`).join('  ');
  return [tag, extra].filter(Boolean).join('  ·  ');
}

function derivePlatformFromTrail(trail) {
  const name = ((trail.path || trail.id || '').toLowerCase().replace(/\.trail\.yaml$/, '').split('/').pop()) || '';
  if (name === 'web' || name.includes('web')) return 'web';
  if (name.startsWith('ios') || name.includes('iphone') || name.includes('ipad')) return 'ios';
  if (name.startsWith('android') || name.includes('phone') || name.includes('tablet')) return 'android';
  return null;
}

// `prependSteps`: ordered recorded steps to replay before the AI objective, each
// { label, tool, args }. The runner replays steps that have a recording and lets the agent
// drive the ones that don't, so these put the app in a known state first, such as clearing app data
// or launching into a signed-in state.
function buildPromptTrailYaml(title, target, platform, objective, prependSteps) {
  const yamlValue = (v) => {
    if (typeof v === 'string') {
      // Quote when empty too — a bare `key:` parses as null, not "" (e.g. the iOS launch tool's
      // unused `password` is a required String).
      if (v === '' || /[:{}\[\],&*#?|<>=!%@`'"\\]/.test(v) || v.includes('\n')) return JSON.stringify(v);
      return v;
    }
    return String(v);
  };
  const lines = ['- config:', `    title: ${yamlValue(title)}`];
  if (target) lines.push(`    target: ${yamlValue(target)}`);
  if (platform) lines.push(`    platform: ${yamlValue(platform)}`);
  lines.push('- prompts:');
  (prependSteps || []).forEach((s) => {
    const args = Object.entries(s.args || {}).map(([k, v]) => `${k}: ${yamlValue(v)}`).join(', ');
    lines.push(
      `  - step: ${yamlValue(s.label)}`,
      '    recording:',
      '      tools:',
      `      - ${s.tool}: ${args ? `{ ${args} }` : '{}'}`,
    );
  });
  lines.push(`  - step: ${JSON.stringify(String(objective))}`);
  return lines.join('\n');
}

// Serialize a `blaze.yaml` (the portable spec): config (with the original objective preserved in
// metadata) + an ordered list of do/verify steps, NO recordings. Used by Create → Save blaze and
// the Drafts step editor. `steps` is an array of { kind: 'do'|'verify', text }.
// Build a blaze.yaml from the structured fields. `extra` carries optional config (context) and
// metadata (destination — the eventual commit home, kept in metadata so the OSS TrailConfig model
// is untouched, same as objective).
function buildBlazeYaml(title, target, platform, objective, steps, extra) {
  extra = extra || {};
  const yamlValue = (v) => {
    if (typeof v === 'string') {
      if (v === '' || /[:{}\[\],&*#?|<>=!%@`'"\\]/.test(v) || v.includes('\n')) return JSON.stringify(v);
      return v;
    }
    return String(v);
  };
  const lines = ['- config:', `    title: ${yamlValue(title)}`];
  if (target) lines.push(`    target: ${yamlValue(target)}`);
  if (platform) lines.push(`    platform: ${yamlValue(platform)}`);
  if (extra.context) lines.push(`    context: ${yamlValue(extra.context)}`);
  const meta = [];
  if (objective) meta.push(`      objective: ${yamlValue(objective)}`);
  if (extra.destination) meta.push(`      destination: ${yamlValue(extra.destination)}`);
  if (meta.length) { lines.push('    metadata:'); meta.forEach((m) => lines.push(m)); }
  lines.push('- prompts:');
  (steps || []).forEach((s) => {
    const key = (s.kind === 'verify') ? 'verify' : 'step';
    lines.push(`  - ${key}: ${yamlValue(s.text)}`);
  });
  return lines.join('\n');
}

// Re-serialize a draft's blaze.yaml when saving STRUCTURED edits (title/target/platform/destination/
// steps), while PRESERVING any config the structured editor doesn't model (tags, priority, driver,
// skip, description, memory, source, electron, id, plus extra metadata keys) — anything a user typed
// in the raw YAML editor. Falls back to a clean buildBlazeYaml if the existing file can't be parsed.
function mergeBlazeYaml(existingYaml, fields) {
  const f = fields || {};
  if (!window.jsyaml) return buildBlazeYaml(f.title, f.target, f.platform, f.objective, f.steps, { context: f.context, destination: f.destination });
  let config = {};
  // The new format carries a leading `- trailhead:` item (the deterministic step 0); preserve it
  // verbatim across a structured save, exactly like config — the step editor doesn't model it, so
  // rebuilding [{config},{prompts}] alone would silently drop it.
  let trailhead = null;
  try {
    const doc = window.jsyaml.load(existingYaml);
    const items = Array.isArray(doc) ? doc : doc ? [doc] : [];
    for (const it of items) {
      if (it && it.config) config = { ...it.config };
      if (it && it.trailhead != null) trailhead = it.trailhead;
    }
  } catch (e) {
    return buildBlazeYaml(f.title, f.target, f.platform, f.objective, f.steps, { context: f.context, destination: f.destination });
  }
  const set = (k, v) => { if (v != null && v !== '') config[k] = v; else delete config[k]; };
  set('title', f.title);
  set('target', f.target);
  set('platform', f.platform);
  set('context', f.context);
  const meta = { ...(config.metadata || {}) };
  if (f.objective) meta.objective = f.objective; else delete meta.objective;
  if (f.destination) meta.destination = f.destination; else delete meta.destination;
  if (Object.keys(meta).length) config.metadata = meta; else delete config.metadata;
  const prompts = (f.steps || []).filter((s) => s.text.trim()).map((s) => ({ [s.kind === 'verify' ? 'verify' : 'step']: s.text }));
  const out = [{ config }];
  if (trailhead != null) out.push({ trailhead });
  out.push({ prompts });
  try {
    return window.jsyaml.dump(out, { lineWidth: -1, noRefs: true }).trimEnd();
  } catch (e) {
    return buildBlazeYaml(f.title, f.target, f.platform, f.objective, f.steps, { context: f.context, destination: f.destination });
  }
}

// extractTrace / extractLlmLogs and their helpers (logClass, stepText, toolDetail,
// summarizeToolArgs, describeSelector, describeAction, toolChildren, parseLlmResponse, truncate)
// now live in app/run-report-core.js (loaded before this file) as the single source of truth so
// the bun report generator and the in-app timeline share one implementation. They remain global.

// The trailheads available for a target, scoped to a single platform — the deterministic "step 0"
// tools (clear data / launch / sign in into a known account state) defined as `*.trailhead.yaml`
// under the target's trailmap. `trailmaps` is the array from TB.useTrailmaps(); the target id maps
// 1:1 to the trailmap id (e.g. target "sample" -> trailmap "sample"). Returns [{ name, relPath }].
//
// A trailhead's launch/sign-in tool is platform-specific, so an android recording must only offer
// android trailheads, etc. A trailhead names its platform via an `_<platform>_` token in its id
// (sample_android_signedInFresh, demo_ios_launchAppSignedOut); one that names NO known platform
// (sample_launchAppSignedIn) is treated as platform-agnostic and offered everywhere. (A proper
// platform field on the trailhead component is the durable fix; the id-token heuristic matches
// today's naming convention.) With no platform, all of the target's trailheads are returned.
//
// Exposed only as `TB.trailheadsForPlatform` (not a bare global) so it can't collide with
// `screens/record.jsx`'s own file-local `trailheadsForTarget` in the shared app script scope.
function trailheadsForPlatform(trailmaps, target, platform) {
  if (!target) return [];
  const tm = (trailmaps || []).find((t) => t.id === target);
  const all = (tm && tm.trailheads) || [];
  const p = (platform || '').toLowerCase();
  if (!p) return all;
  const KNOWN = ['android', 'ios', 'web'];
  return all.filter((t) => {
    const name = (t.name || '').toLowerCase();
    const named = KNOWN.find((k) => name.includes('_' + k + '_'));
    return !named || named === p;
  });
}

function formatDuration(ms) {
  if (!ms || ms < 0) return '—';
  if (ms < 1000) return `${ms}ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`;
  const m = Math.floor(ms / 60_000);
  const s = Math.round((ms - m * 60_000) / 1000);
  return `${m}m ${s}s`;
}
function formatAgo(epochMs) {
  if (!epochMs) return '';
  const diff = Date.now() - epochMs;
  if (diff < 60_000) return `${Math.floor(diff / 1000)}s ago`;
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)}m ago`;
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)}h ago`;
  return `${Math.floor(diff / 86_400_000)}d ago`;
}

// Tokens a per-DEVICE variant file name is built from (android-phone, android-tablet, ios-iphone,
// ios-ipad, web, …) — as opposed to a TEST name (ios-t-090-create-appt-variant, t-088-adjust-stock).
// A stem made up ENTIRELY of these is a device variant; anything with a test id / free-form word is
// not. Kept in sync with the platforms derivePlatformFromTrail recognizes, plus form-factor words.
const DEVICE_VARIANT_TOKENS = new Set([
  'android', 'ios', 'web', 'mobile', 'desktop',
  'phone', 'tablet', 'iphone', 'ipad',
]);
function isDeviceVariantStem(stem) {
  const toks = String(stem || '').toLowerCase().split(/[-_]/).filter(Boolean);
  return toks.length > 0 && toks.every((t) => DEVICE_VARIANT_TOKENS.has(t));
}

// A folder is a *bundle* (one logical trail whose files are device variants) when it carries a
// plain-language blaze, OR every file is a per-device variant — named purely by platform/device
// (android-phone, ios-iphone, web). Multiple files per platform (phone + tablet, iphone + ipad) are
// fine; they're device recordings of ONE trail. Flat multi-test suites — folders whose files are
// TEST names (ios-t-090-create-appt-variant, t-088-adjust-stock-web) — are NOT bundles; their files
// stay as independent trail rows. The filename shape (device tokens vs a test id) is the signal.
function isVariantBundle(children) {
  if (!children || !children.length) return false;
  if (children.some((c) => (c.kind || 'trail') === 'blaze')) return true;
  const nonBlaze = children.filter((c) => (c.kind || 'trail') !== 'blaze');
  // Unified single-file trails are self-contained — never collapse a folder of them into one row.
  if (nonBlaze.length && nonBlaze.every((c) => c.format === 'unified')) return false;
  // The robust signal: per-platform variants of ONE logical trail (2+ files) all declare the same
  // `config.id` (the shared case id). This collapses variant sets whose filenames aren't plain
  // device tokens (e.g. a hardware-classifier variant like `<classifier>-t2`), which the stem
  // heuristic below misses. A flat suite of independent tests has distinct ids, so it stays as
  // separate rows. A single file is never grouped here — it's a leaf, not a bundle-of-one (the
  // device-stem path below still collapses a lone `android-phone.trail.yaml`, as before).
  const ids = new Set(nonBlaze.map((c) => c.configId).filter(Boolean));
  if (nonBlaze.length > 1 && ids.size === 1 && nonBlaze.every((c) => c.configId)) return true;
  return children.every((c) => {
    if ((c.kind || 'trail') === 'blaze') return true;
    const stem = (c.path || c.id || '').split('/').pop().replace(/\.trail\.yaml$/, '');
    return isDeviceVariantStem(stem);
  });
}

// Classify every trail by its ACTUAL on-disk YAML shape (from the backend's `format` field, decoded
// via TrailDocument) for the format filter + list badges: 'unified' = a single-file `config:`+`trail:`
// mapping (every platform's recording inline); 'bundle' = the legacy v1 list format (`- config:` /
// `- prompts:` items — whether standalone or one of a folder's per-platform variants); 'blaze' = a
// plain-language spec. Classifying per file, not by folder shape, is what makes the badge honest: a
// legacy list file reads 'bundle' even when it sits in a folder our stem heuristic doesn't recognize
// as a variant set. Returns a Map keyed by trail id.
function trailFormatMap(trails) {
  const m = new Map();
  for (const t of (trails || [])) {
    m.set(t.id, t.kind === 'blaze' ? 'blaze' : (t.format === 'unified' ? 'unified' : 'bundle'));
  }
  return m;
}

// Count of selectable trail units, matching what the folder-mode list shows: each variant
// bundle counts once; every file in a non-bundle folder (a flat suite, or root-level files)
// counts on its own.
function countTrailBundles(trails) {
  const byFolder = new Map();
  for (const t of (trails || [])) {
    const f = t.folder != null ? t.folder : ((t.path || '').lastIndexOf('/') < 0 ? '' : (t.path || '').slice(0, (t.path || '').lastIndexOf('/')));
    if (!byFolder.has(f)) byFolder.set(f, []);
    byFolder.get(f).push(t);
  }
  let n = 0;
  // Match buildTrailTree, which only bundle-annotates non-root folders (its `f !== ''` gate):
  // root-level files are never collapsed into a single bundle row, so count them individually
  // here too, otherwise the sidebar badge could disagree with the rendered list for root trails.
  for (const [f, children] of byFolder) n += (f !== '' && isVariantBundle(children)) ? 1 : children.length;
  return n;
}

// Summarize a bundle folder's child trails onto its folder row so the row can stand in for the
// whole logical trail: the platforms it's recorded on, whether it has a plain-language blaze,
// the representative recording to open, and the ids of every variant (for selection).
function annotateBundle(row, children) {
  row.bundle = true;
  row.count = children.length;
  row.platforms = [...new Set(children.map((c) => c.platform || derivePlatformFromTrail(c)).filter(Boolean))].sort();
  row.hasBlaze = children.some((c) => (c.kind || 'trail') === 'blaze');
  row.variantIds = children.map((c) => c.id);
  const rep = children.find((c) => (c.kind || 'trail') !== 'blaze') || children[0];
  row.repId = rep && rep.id;
  row.target = (children.find((c) => c.target) || {}).target || null;
  row.priority = (children.find((c) => c.priority) || {}).priority || null;
}

// Build the sidebar rows for the folder-structure view. The trail BUNDLE is the unit each row
// represents, and intermediate single-child directories are compressed into a path prefix so the
// tree stays shallow and shows more trails:
//   - A bundle (per-platform variants, or a folder with a blaze) is one row. Its variant files
//     follow as `variant:true` trail rows (hidden until the bundle is expanded).
//   - A directory that branches (>= 2 children, or a flat suite of loose trail files) gets a folder
//     header row whose `name` is the full compressed chain (e.g. `regression/suite_a/section_x`),
//     with its children indented one level beneath it.
//   - A directory with a single child is NOT given its own row — its name is folded into the child's
//     `prefix` (shown dim before the name), so a lone trail buried in a chain of dirs reads as one
//     row that still makes its location obvious.
// `acc` stays the real full folder path (collapse state, pins, and the matrix all key off it); only
// the display label (`name` + `prefix`) and indent `depth` are reshaped.
function buildTrailTree(trails, extraFolders) {
  const byFolder = new Map();
  for (const t of trails) {
    const key = t.folder || '';
    if (!byFolder.has(key)) byFolder.set(key, []);
    byFolder.get(key).push(t);
  }
  const folderKeys = new Set(byFolder.keys());
  // Folders with no trail files yet (e.g. just created from the New trail modal).
  (extraFolders || []).forEach((f) => { if (f) folderKeys.add(f); });

  // Nested directory tree. node: { name, acc, dirs: Map<name,node>, files: [trail] }.
  const root = { name: '', acc: '', dirs: new Map(), files: [] };
  const ensureNode = (path) => {
    let node = root, acc = '';
    for (const seg of (path || '').split('/').filter(Boolean)) {
      acc = acc ? `${acc}/${seg}` : seg;
      if (!node.dirs.has(seg)) node.dirs.set(seg, { name: seg, acc, dirs: new Map(), files: [] });
      node = node.dirs.get(seg);
    }
    return node;
  };
  folderKeys.forEach((f) => ensureNode(f));
  for (const [f, files] of byFolder) ensureNode(f).files = files;

  // A directory is a bundle (one logical trail, files are device variants) when it has files, no
  // subdirs, and those files are genuine per-platform variants (or carry a blaze).
  const isBundleNode = (n) => n.acc !== '' && n.dirs.size === 0 && n.files.length > 0 && isVariantBundle(n.files);
  // A directory's child entries: subdirs (each a bundle or a plain dir) then its own loose files.
  const entriesOf = (n) => {
    const out = [];
    [...n.dirs.values()].sort((a, b) => a.name.localeCompare(b.name)).forEach((sub) =>
      out.push({ kind: isBundleNode(sub) ? 'bundle' : 'dir', node: sub }));
    [...n.files].sort((a, b) => (a.path || '').localeCompare(b.path || '')).forEach((tr) =>
      out.push({ kind: 'file', trail: tr }));
    return out;
  };

  const rows = [];
  const trailRow = (tr, depth, acc, variant, prefix) => ({
    t: 'trail', id: tr.id, name: tr.title || tr.id, path: tr.path, rootIdx: tr.rootIdx, depth, acc,
    status: 'unknown', kind: tr.kind || 'trail', format: tr.format || 'v1', platform: tr.platform || derivePlatformFromTrail(tr),
    variant, prefix: prefix || '',
  });
  const renderEntry = (entry, depth, prefixSegs) => {
    const prefix = prefixSegs.join('/');
    if (entry.kind === 'file') { rows.push(trailRow(entry.trail, depth, entry.trail.folder || '', false, prefix)); return; }
    if (entry.kind === 'bundle') {
      const node = entry.node;
      const row = { t: 'folder', bundle: true, acc: node.acc, name: node.name, prefix, depth };
      annotateBundle(row, node.files);
      rows.push(row);
      [...node.files].sort((a, b) => (a.path || '').localeCompare(b.path || ''))
        .forEach((tr) => rows.push(trailRow(tr, depth + 1, node.acc, true, '')));
      return;
    }
    // entry.kind === 'dir': a container/suite directory.
    const ent = entriesOf(entry.node);
    if (ent.length === 1) { renderEntry(ent[0], depth, [...prefixSegs, entry.node.name]); return; } // compress
    rows.push({ t: 'folder', bundle: false, acc: entry.node.acc, name: [...prefixSegs, entry.node.name].join('/'), depth });
    ent.forEach((c) => renderEntry(c, depth + 1, []));
  };

  // Drop the single workspace-root directory ("trails") from the display — it's the same for every
  // row, so it's noise — and render its children as the top level. `acc` keeps the root segment.
  let top = entriesOf(root);
  if (top.length === 1 && top[0].kind === 'dir') top = entriesOf(top[0].node);
  top.forEach((c) => renderEntry(c, 0, []));
  return rows;
}

// A FLAT, sorted list of trail UNITS — the same unit the folder view counts (a bundle counts once;
// a non-bundle file stands alone) — for the Title / Priority sort orders. The folder hierarchy is
// dropped (it would fight the sort), but the unit stays whole: a bundle is one row showing its
// platform variants, a standalone file is one row showing its title + platform — never the raw
// `<device>.trail.yaml` filename flattened out of context (which is meaningless once sorted). Bundle
// variant files follow their bundle as hidden `variant` rows so the accordion still expands them.
function buildTrailBundleRows(trails, sortBy) {
  const PRI = { P0: 0, P1: 1, P2: 2, P3: 3 };
  const pv = (p) => (PRI[p] != null ? PRI[p] : 99);
  const byFolder = new Map();
  for (const t of (trails || [])) {
    const f = t.folder || '';
    if (!byFolder.has(f)) byFolder.set(f, []);
    byFolder.get(f).push(t);
  }
  const trailRow = (tr, depth, acc, variant, flat) => ({
    t: 'trail', id: tr.id, name: tr.title || tr.id, path: tr.path, rootIdx: tr.rootIdx, depth, acc,
    status: 'unknown', kind: tr.kind || 'trail', format: tr.format || 'v1', variant, flat,
    platform: tr.platform || derivePlatformFromTrail(tr), prefix: '',
  });
  const units = [];
  for (const [f, children] of byFolder) {
    if (f !== '' && isVariantBundle(children)) {
      const slug = f.split('/').filter(Boolean).pop() || f;
      // Display + sort by the bundle's authored title (blaze title, else any child's config title),
      // not the raw folder slug — otherwise a Title A–Z sort orders a recorded bundle like
      // `case_1234` by its slug instead of its real title ("Zero state"). Slug is the fallback.
      const blaze = children.find((c) => (c.kind || 'trail') === 'blaze');
      const authored = ((blaze && blaze.title) || (children.find((c) => c.title) || {}).title || '').trim();
      const name = (authored && authored.toLowerCase() !== 'blaze') ? authored : slug;
      const row = { t: 'folder', bundle: true, acc: f, name, prefix: '', depth: 0, flat: true };
      annotateBundle(row, children);
      const variants = [...children].sort((a, b) => (a.path || '').localeCompare(b.path || ''))
        .map((tr) => trailRow(tr, 1, f, true, false));
      units.push({ title: name.toLowerCase(), priority: pv(row.priority), rows: [row, ...variants] });
    } else {
      for (const tr of children) {
        units.push({ title: (tr.title || tr.id || '').toLowerCase(), priority: pv(tr.priority), rows: [trailRow(tr, 0, tr.folder || '', false, true)] });
      }
    }
  }
  if (sortBy === 'priority') units.sort((a, b) => a.priority - b.priority || a.title.localeCompare(b.title));
  else units.sort((a, b) => a.title.localeCompare(b.title));
  return units.flatMap((u) => u.rows);
}

function humanizeTarget(id) {
  if (!id) return '';
  return String(id)
    .replace(/[._-]+/g, ' ')
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .replace(/\s+/g, ' ').trim()
    .replace(/\b\w/g, (c) => c.toUpperCase());
}
function targetLabel(id, displayMap) {
  if (id == null || id === 'all') return 'All Targets';
  return (displayMap && displayMap[id]) || humanizeTarget(id);
}

// The trailmaps whose components (tools, trailheads) are in scope
// when `target` is active on `platform`: the target's own trailmap PLUS the generalized ones —
// the framework core (trailblaze) and the platform's shared trailmaps (android/ios/web/mobile/
// compose). This mirrors the agent's session-start tool composition and the trail/draft editor
// palette (editorToolsFor), so the Tools and Trailmaps views show the same set a real run would:
// platform-wide tools (android_*, mobile_*, trailblaze_*) stay visible for an app target instead
// of vanishing because they don't live in the target's own trailmap. A null/unknown platform is
// permissive (every general trailmap), so nothing is hidden before a device platform is known.
const GENERAL_TRAILMAPS_BY_PLATFORM = {
  android: ['trailblaze', 'mobile', 'android', 'compose'],
  ios: ['trailblaze', 'mobile'],
  web: ['trailblaze', 'web'],
};
function scopeTrailmaps(target, platform) {
  const plat = (platform || '').toLowerCase();
  const general = GENERAL_TRAILMAPS_BY_PLATFORM[plat] || ['trailblaze', 'mobile', 'android', 'ios', 'web', 'compose'];
  return new Set([target, ...general].filter(Boolean));
}

// Flatten a nested object into ordered { path, value } rows: nested objects become dotted paths
// (metadata.objective), arrays are formatted by `joinArray` (default: comma-joined), scalars
// stringify; `skip` (a Set) drops keys at any level. One predictable rule for every key — used for
// the verbatim config block and for recorded selector params (JSON-encoded arrays, `reason` skipped).
// Lives here (loaded before every screen) so drafts.jsx and trail-detail.jsx share one definition
// rather than leaning on cross-file <script> ordering.
function flattenObject(obj, { joinArray = (a) => a.join(', '), skip, prefix } = {}) {
  const out = [];
  for (const k of Object.keys(obj || {})) {
    if (skip && skip.has(k)) continue;
    const path = prefix ? prefix + '.' + k : k;
    const v = obj[k];
    if (v && typeof v === 'object' && !Array.isArray(v)) out.push(...flattenObject(v, { joinArray, skip, prefix: path }));
    else out.push({ path, value: Array.isArray(v) ? joinArray(v) : String(v) });
  }
  return out;
}

Object.assign(window, {
  flattenObject,
  summarizeAnalyticsProps, derivePlatformFromTrail, buildPromptTrailYaml,
  formatDuration, formatAgo, buildTrailTree, buildTrailBundleRows, countTrailBundles, trailFormatMap, humanizeTarget, targetLabel, scopeTrailmaps,
});

window.TB = {
  useStatus, useTrails, useTools, useToolCatalog, invalidateToolCatalog, useTrailmaps, useToolSource, useScriptedToolParams, useSessions, useSessionDetail, useTrailDetail, useRunTools, useDevices, useTrailRoots, useFavorites, useSettings, useIntegrations, useSessionAnalytics, useSessionEvents, useSessionYaml, useSessionFiles, useToolUsages, useToolUsageCounts, useToolToolUsages, useToolToolUsageCounts,
  addTrailRoot, removeTrailRoot, pickDirectoryViaShell, updateSetting, switchWorkspace, WORKSPACE_BLURB, WORKSPACE_EMPTY_NOTICE, workspaceRestartNotice, setTargetsRestartNeeded, getTargetsRestartNeeded, deleteSession, clearSessions, cancelSession, revealSession, revealLogsRoot, revealToolSource, openTrailInEditor, revealTrail, exportSessionUrl, sessionArchiveUrl, importSessionArchive, runIntegrationAction, fetchComponentSource, createTrailmapComponent, saveTargetConfig,
  resolveRunDevice, connectDevice, fetchTrailYaml, dispatchRun, retrySession,
  getTargetApps, setTargetApp, useDeviceApps, buildPromptTrailYaml, updateTrail, createTrail, fetchEditedTrails, runToolQuick, updateToolSource, fetchDeviceApps, fetchInstalledApps, fetchInstalledAppBadge, installedAppIconUrl, validateTrail, rebuildDaemon, openSessionFile, revealTrailsRoot,
  buildTrailTree, buildTrailBundleRows, countTrailBundles, trailFormatMap, fileUrl, summarizeAnalyticsProps, humanizeTarget, targetLabel, scopeTrailmaps, useTargetAppMap, trailheadsForPlatform,
  recordPendingRun, getPendingRun, clearPendingRun, failPendingRun,
  useGlobalTarget, getGlobalTarget, setGlobalTarget,
  buildBlazeYaml, mergeBlazeYaml, proposeSteps, createDraft, fetchDraftDetail, fetchDraftFile, updateDraftBlaze, updateDraftFile, deleteDraftFile, saveDraftTo, deleteDraft, revealDraft, recordDraft,
  fetchTrailFolderFile, saveTrailFolderFile, deleteTrailFolderFile, recordTrailFolder, migrateTrailFolder,
  useDrafts, useDraftDetail,
  recordConnect, recordScreen, recordGesture, recordTree, recordDisconnect, recordToolParams, scriptedToolParams,
};
