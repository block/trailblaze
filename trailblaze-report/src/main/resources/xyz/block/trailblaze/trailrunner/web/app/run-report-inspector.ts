// Pure model + markup builders for the report's UI Inspector (per-step view-hierarchy tree, node
// details, and the bounds overlay scaled onto the step screenshot). No DOM and no viewer state —
// the viewer (run-report-viewer.ts) owns the overlay lifecycle and event wiring, and the tests
// exercise these builders directly with plain hierarchy JSON.
//
// The hierarchy JSON is whichever view-hierarchy field the session's logs carried
// (viewHierarchyFiltered || trailblazeNodeTree || viewHierarchy — see extractTrace), so two shapes
// arrive here and both are normalized field-by-field rather than branched on:
//  - ViewHierarchyTreeNode (legacy, all drivers): text/accessibilityText/resourceId/className at
//    the top level, bounds as x1..y2 ints (older captures: centerPoint "x,y" + dimensions "WxH").
//  - TrailblazeNode (accessibility drivers): bounds as {left,top,right,bottom}, everything
//    matchable inside a per-driver `driverDetail` object (text/className/contentDescription on
//    Android, label/value on iOS AXe, …).
// kotlinx.serialization omits default-valued fields, so every read tolerates absence.

/** One normalized node of the inspected hierarchy (pre-order key; children by key). */
interface InspectorTreeNode {
  key: number;
  depth: number;
  /** Tree-row display text: "text" | [content description] | #resourceId | <ClassName> | (node). */
  label: string;
  /** Ordered detail rows for the selected-node panel (deduped by display label). */
  fields: Array<{ k: string; v: string }>;
  /** Names of every boolean property that is true on the node (clickable, scrollable, …). */
  flags: string[];
  bounds: { x1: number; y1: number; x2: number; y2: number } | null;
  children: number[];
}

interface InspectorModel {
  nodes: InspectorTreeNode[];
  /** Device-coordinate space of the capture, derived from the tree itself (root bounds, else the
   * max extent of any node) — what the overlay percentages scale against. */
  dims: { w: number; h: number } | null;
  /** True when the walk stopped at the node cap (pathological unfiltered trees). */
  truncated: boolean;
}

// Escape for interpolation into markup this module emits. Local copy (this module is pure and
// dependency-free); same character set as the viewer's esc.
function escInsp(s: unknown): string {
  return String(s == null ? '' : s).replace(/[<>&"]/g, (c) => ({ '<': '&lt;', '>': '&gt;', '&': '&amp;', '"': '&quot;' }[c]));
}

function truncInsp(s: string, n: number): string {
  return s.length > n ? s.slice(0, n - 1) + '…' : s;
}

const INSPECTOR_NODE_CAP = 5000;

// Merge the node's own properties with its driverDetail (TrailblazeNode), detail winning — one
// flat property bag both shapes read from.
function nodeProps(raw: any): Record<string, unknown> {
  const own = raw && typeof raw === 'object' ? raw : {};
  const detail = own.driverDetail && typeof own.driverDetail === 'object' ? own.driverDetail : {};
  return { ...own, ...detail };
}

function nodeBounds(raw: any): { x1: number; y1: number; x2: number; y2: number } | null {
  const num = (v: unknown) => (typeof v === 'number' && Number.isFinite(v) ? v : null);
  const rect = (x1: number | null, y1: number | null, x2: number | null, y2: number | null) =>
    (x1 != null && y1 != null && x2 != null && y2 != null && x2 >= x1 && y2 >= y1) ? { x1, y1, x2, y2 } : null;
  if (!raw || typeof raw !== 'object') return null;
  // TrailblazeNode: bounds: { left, top, right, bottom }.
  const b = raw.bounds;
  if (b && typeof b === 'object') {
    const r = rect(num(b.left), num(b.top), num(b.right), num(b.bottom));
    if (r) return r;
  }
  // ViewHierarchyTreeNode: x1..y2 ints (all-zero means "unset", matching the Kotlin model).
  if ([raw.x1, raw.y1, raw.x2, raw.y2].some((v: unknown) => typeof v === 'number' && v !== 0)) {
    const r = rect(num(raw.x1) || 0, num(raw.y1) || 0, num(raw.x2) || 0, num(raw.y2) || 0);
    if (r) return r;
  }
  // Legacy captures: centerPoint "x,y" + dimensions "WxH".
  if (typeof raw.centerPoint === 'string' && typeof raw.dimensions === 'string') {
    const c = raw.centerPoint.split(',').map(Number);
    const d = raw.dimensions.split('x').map(Number);
    if (c.length === 2 && d.length === 2 && [...c, ...d].every(Number.isFinite)) {
      const left = c[0] - Math.floor(d[0] / 2);
      const top = c[1] - Math.floor(d[1] / 2);
      return rect(left, top, left + d[0], top + d[1]);
    }
  }
  return null;
}

// Detail-row extraction order. Two shapes share display labels (accessibilityText and
// contentDescription are the same concept from different models) — first present wins per label.
const INSPECTOR_FIELD_KEYS: Array<[string, string]> = [
  ['text', 'Text'],
  ['label', 'Label'],
  ['value', 'Value'],
  ['hintText', 'Hint'],
  ['contentDescription', 'Content description'],
  ['accessibilityText', 'Content description'],
  ['ariaName', 'Name'],
  ['resourceId', 'Resource ID'],
  ['viewIdResourceName', 'Resource ID'],
  ['dataTestId', 'Test ID'],
  ['className', 'Class'],
  ['role', 'Role'],
  ['ariaRole', 'Role'],
  ['ref', 'Ref'],
];

function nodeFields(props: Record<string, unknown>): Array<{ k: string; v: string }> {
  const fields: Array<{ k: string; v: string }> = [];
  for (const [key, label] of INSPECTOR_FIELD_KEYS) {
    const value = props[key];
    if (value == null || value === '' || typeof value === 'object') continue;
    if (fields.some((f) => f.k === label)) continue;
    fields.push({ k: label, v: String(value) });
  }
  return fields;
}

function nodeFlags(props: Record<string, unknown>): string[] {
  return Object.keys(props).filter((key) => props[key] === true);
}

// The tree row's display text — same precedence the legacy WASM inspector used: literal text
// first, then the accessibility text, then resource id, then a short class name. The web
// (Playwright) detail keeps that shape via its ARIA fields: ariaName is the accessible name
// (text leg) and ariaRole the element kind (class leg).
function nodeLabel(props: Record<string, unknown>): string {
  const str = (v: unknown) => (v != null && typeof v !== 'object' && String(v).trim() !== '' ? String(v) : null);
  const text = str(props.text) || str(props.label) || str(props.value) || str(props.ariaName);
  if (text) return `"${truncInsp(text, 60)}"`;
  const a11y = str(props.contentDescription) || str(props.accessibilityText);
  if (a11y) return `[${truncInsp(a11y, 60)}]`;
  const id = str(props.resourceId) || str(props.viewIdResourceName);
  if (id) return `#${truncInsp(id, 60)}`;
  const cls = str(props.className) || str(props.role) || str(props.ariaRole);
  if (cls) return `<${truncInsp(cls.split('.').pop() || cls, 40)}>`;
  return '(node)';
}

/** Normalize one hierarchy JSON value into the flat pre-order node model the inspector renders. */
function inspectorModel(hierarchy: unknown): InspectorModel | null {
  if (!hierarchy || typeof hierarchy !== 'object') return null;
  const nodes: InspectorTreeNode[] = [];
  let truncated = false;
  const walk = (raw: any, depth: number): number | null => {
    if (nodes.length >= INSPECTOR_NODE_CAP) { truncated = true; return null; }
    if (!raw || typeof raw !== 'object') return null;
    const props = nodeProps(raw);
    const node: InspectorTreeNode = {
      key: nodes.length,
      depth,
      label: nodeLabel(props),
      fields: nodeFields(props),
      flags: nodeFlags(props),
      bounds: nodeBounds(raw),
      children: [],
    };
    nodes.push(node);
    for (const child of Array.isArray(raw.children) ? raw.children : []) {
      const key = walk(child, depth + 1);
      if (key != null) node.children.push(key);
    }
    return node.key;
  };
  walk(hierarchy, 0);
  if (!nodes.length) return null;
  // Device coordinate space: the root's own extent when it has one, else the widest extent any
  // node reports — bounds are device pixels, so this is what the overlay scales against.
  let w = 0;
  let h = 0;
  const root = nodes[0].bounds;
  if (root && root.x2 > 0 && root.y2 > 0) { w = root.x2; h = root.y2; }
  else {
    for (const n of nodes) { if (n.bounds) { w = Math.max(w, n.bounds.x2); h = Math.max(h, n.bounds.y2); } }
  }
  return { nodes, dims: w > 0 && h > 0 ? { w, h } : null, truncated };
}

/**
 * Smallest-area node containing device point (x, y) — the screenshot click-to-select rule.
 * Ties go to the LAST node in pre-order (`<=`): web DOMs wrap elements in containers with byte-
 * identical bounds (a link inside its list item, a button around its label), and under a
 * first-wins tie the outermost wrapper shadowed the element itself on every hover — the deepest
 * node is the one a browser hit-test would report. The same rule resolves overlapping equal-area
 * SIBLINGS to the later one, matching DOM paint order (later siblings draw on top).
 */
function hitTestNode(model: InspectorModel, x: number, y: number): number | null {
  let best: number | null = null;
  let bestArea = Infinity;
  for (const n of model.nodes) {
    const b = n.bounds;
    if (!b || x < b.x1 || x > b.x2 || y < b.y1 || y > b.y2) continue;
    const area = Math.max(1, (b.x2 - b.x1) * (b.y2 - b.y1));
    if (area <= bestArea) { bestArea = area; best = n.key; }
  }
  return best;
}

/**
 * The collapsible node tree. Nodes with children render as <details open>; rows select by key.
 * Exactly one focusable control per row: the row span (role="button") is the tab stop, and the
 * branch <summary> is taken OUT of the tab order (tabindex="-1") so a branch isn't two stops
 * (toggle + select). Mouse toggle stays on the summary chevron/whitespace; keyboard
 * expand/collapse rides the focused row via ArrowRight/ArrowLeft (wired in the viewer).
 */
function inspectorTreeHtml(model: InspectorModel, selectedKey: number | null): string {
  const row = (n: InspectorTreeNode) =>
    `<span class="inspnoderow${n.key === selectedKey ? ' sel' : ''}" data-inspnode="${n.key}" role="button" tabindex="0"><span class="inspkey mono">${n.key}</span><span class="insplabel mono">${escInsp(n.label)}</span></span>`;
  const render = (key: number): string => {
    const n = model.nodes[key];
    if (!n.children.length) return `<div class="inspleaf">${row(n)}</div>`;
    return `<details class="inspbranch" open><summary data-insptoggle tabindex="-1">${row(n)}</summary><div class="inspkids">${n.children.map(render).join('')}</div></details>`;
  };
  const note = model.truncated ? `<div class="inspnote">Tree truncated at ${INSPECTOR_NODE_CAP} nodes.</div>` : '';
  return render(0) + note;
}

/**
 * One positioned rectangle per node, keyed by `data-insprect` so the viewer can light one up in
 * place (no re-render) as hover moves. At rest every rect is invisible — CSS only paints `.hov` and
 * `.sel` — matching the Compose inspector, which draws nothing until a node is hovered or selected.
 * Painting all of them made the screenshot read as a wireframe before anything was picked.
 *
 * `dimsOverride` is the caller's coordinate anchor when the tree's own extent can't be trusted —
 * the log's viewport for a web capture (page-relative bounds, off-viewport nodes) — and defaults
 * to the model's derived extent.
 */
function inspectorRectsHtml(model: InspectorModel, selectedKey: number | null, dimsOverride: { w: number; h: number } | null = null): string {
  const dims = dimsOverride || model.dims;
  if (!dims) return '';
  const pct = (v: number, span: number) => `${((v / span) * 100).toFixed(3)}%`;
  return model.nodes.map((n) => {
    const b = n.bounds;
    if (!b) return '';
    const sel = n.key === selectedKey;
    return `<div class="insprect${sel ? ' sel' : ''}" data-insprect="${n.key}" style="left:${pct(b.x1, dims.w)};top:${pct(b.y1, dims.h)};width:${pct(b.x2 - b.x1, dims.w)};height:${pct(b.y2 - b.y1, dims.h)}"></div>`;
  }).join('');
}

/**
 * Detail rows for the node under inspection, or a hint when nothing is picked yet. A hovered node
 * takes precedence over the committed selection (the Compose inspector's `hoveredNode ?: selected`
 * rule) and says so, so a preview can't be mistaken for what a click committed.
 */
function inspectorDetailsHtml(model: InspectorModel, selectedKey: number | null, hoveredKey: number | null = null): string {
  const previewing = hoveredKey != null && hoveredKey !== selectedKey;
  const key = hoveredKey != null ? hoveredKey : selectedKey;
  const n = key != null ? model.nodes[key] : null;
  if (!n) return `<div class="inspnote">Hover the screenshot to preview an element, or click it — or a tree node — to see its properties.</div>`;
  const rows = previewing ? [`<div class="r inspreview"><span class="k">Preview</span><span class="v">Click to keep this node selected</span></div>`] : [];
  n.fields.forEach((f) => rows.push(`<div class="r"><span class="k">${escInsp(f.k)}</span><span class="v">${escInsp(f.v)}</span></div>`));
  if (n.bounds) {
    const b = n.bounds;
    rows.push(`<div class="r"><span class="k">Bounds</span><span class="v mono">${b.x1},${b.y1} – ${b.x2},${b.y2}</span></div>`);
    rows.push(`<div class="r"><span class="k">Size</span><span class="v mono">${b.x2 - b.x1}×${b.y2 - b.y1}</span></div>`);
    rows.push(`<div class="r"><span class="k">Center</span><span class="v mono">${Math.floor((b.x1 + b.x2) / 2)},${Math.floor((b.y1 + b.y2) / 2)}</span></div>`);
  }
  if (n.flags.length) rows.push(`<div class="r"><span class="k">State</span><span class="v">${n.flags.map((f) => `<span class="inspflag">${escInsp(f)}</span>`).join('')}</span></div>`);
  return `<div class="rows">${rows.join('')}</div>`;
}

export { inspectorModel, hitTestNode, inspectorTreeHtml, inspectorRectsHtml, inspectorDetailsHtml };
export type { InspectorModel, InspectorTreeNode };
