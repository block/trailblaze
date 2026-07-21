// @ts-nocheck -- migrated from .jsx; this file has pre-existing type errors from years of
// untyped legacy JS (mostly optional params/props without defaults, inferred by TS as required).
// Babel strips types at load time regardless, so the browser runtime is unaffected.
// Remove this pragma once the file's real errors are fixed; run `bun run typecheck` to see them.

// Blaze → Record. Drive a live device: each interaction is captured as a deterministic tool step,
// editable inline, then saved into a trail folder (blaze.yaml + <platform>.trail.yaml).
// Live view + dispatch reuse the daemon's recording endpoints (RecordRoutes.kt).

// Quote a string as a YAML scalar when it carries metacharacters (matches buildPromptTrailYaml).
function recordYamlValue(v) {
  const s = String(v == null ? '' : v);
  if (s === '' || /[:{}\[\],&*#?|<>=!%@`'"\\]/.test(s) || s.includes('\n')) return JSON.stringify(s);
  return s;
}

// Assemble the editable step cards into one runnable trail YAML.
function buildRecordedTrailYaml(title, target, platform, steps) {
  const lines = ['- config:', `    title: ${recordYamlValue(title)}`];
  if (target) lines.push(`    target: ${recordYamlValue(target)}`);
  if (platform) lines.push(`    platform: ${recordYamlValue(platform)}`);
  for (const s of steps) {
    const text = (s.text || '').trim();
    const toolItems = s.yaml ? parseRecordStepTools(s.yaml) : [];
    if (text) {
      // NL prompt step; if it also has tools, carry them under `recording:` (the trail-detail shape).
      lines.push('- prompts:', `  - ${s.verify ? 'verify' : 'step'}: ${recordYamlValue(text)}`);
      if (toolItems.length && window.jsyaml) {
        lines.push('    recording:', '      tools:');
        toolItems.forEach((t) => {
          window.jsyaml.dump([{ [t.name]: t.args == null ? {} : t.args }], { lineWidth: -1 }).replace(/\n+$/, '').split('\n').forEach((l) => lines.push('      ' + l));
        });
      }
    } else if (toolItems.length) {
      const y = (s.yaml || '').trimEnd();
      if (y) lines.push(y);
    }
  }
  return lines.join('\n');
}

// Editable `- tools:` YAML for a catalog tool, params emitted as fill-in placeholders.
function buildToolStepYaml(t) {
  const params = t.parameters || [];
  if (!params.length) return `- tools:\n  - ${t.id}: {}`;
  const lines = ['- tools:', `  - ${t.id}:`];
  params.forEach((p) => lines.push(`      ${p.name}: <${p.type}${p.required ? '' : ', optional'}>`));
  return lines.join('\n');
}

// Append a catalog tool to a step's existing `- tools:` list (a step can drive several tools in order).
function appendToolToStepYaml(stepYaml, tool) {
  const params = tool.parameters || [];
  const args = {};
  params.forEach((p) => { args[p.name] = '<' + p.type + (p.required ? '' : ', optional') + '>'; });
  const existing = parseRecordStepTools(stepYaml || '').map((e) => ({ [e.name]: e.args == null ? {} : e.args }));
  const all = [...existing, { [tool.id]: params.length ? args : {} }];
  if (window.jsyaml) return window.jsyaml.dump([{ tools: all }], { lineWidth: -1 }).replace(/\n+$/, '');
  const head = (stepYaml || '- tools:').trimEnd();
  if (!params.length) return head + '\n  - ' + tool.id + ': {}';
  return head + '\n  - ' + tool.id + ':\n' + params.map((p) => '      ' + p.name + ': <' + p.type + (p.required ? '' : ', optional') + '>').join('\n');
}

// Wrap a step's tool(s) in the prompts/recording/tools envelope ToolRunRequest needs (a bare
// `- tools:` item is rejected). Returns null if the step has no parseable tool.
function buildRunnableToolYaml(label, stepYaml) {
  if (!window.jsyaml) return null;
  let tools = null;
  try {
    const doc = window.jsyaml.load(stepYaml);
    const items = Array.isArray(doc) ? doc : [doc];
    for (const it of items) if (it && it.tools) tools = it.tools;
  } catch (e) { return null; }
  if (!tools || !tools.length) return null;
  const lines = ['- config:', `    title: ${JSON.stringify('Run: ' + label)}`, '- prompts:', `  - step: ${JSON.stringify('Run: ' + label)}`, '    recording:', '      tools:'];
  window.jsyaml.dump(tools, { lineWidth: -1 }).replace(/\n+$/, '').split('\n').forEach((l) => lines.push(l ? '      ' + l : l));
  return lines.join('\n');
}

// Parse a step's stored YAML (`- tools:`) into [{name, args}] for the card display.
function parseRecordStepTools(yaml) {
  if (!window.jsyaml) return [];
  try {
    const doc = window.jsyaml.load(yaml);
    const items = Array.isArray(doc) ? doc : [doc];
    const out = [];
    for (const it of items) {
      if (it && Array.isArray(it.tools)) {
        for (const t of it.tools) {
          if (t && typeof t === 'object') { const name = Object.keys(t)[0]; out.push({ name, args: t[name] }); }
          else if (typeof t === 'string') out.push({ name: t, args: null });
        }
      }
    }
    return out;
  } catch (e) { return []; }
}

// Translate a selector strategy into plain language for a non-technical author: friendly name,
// one-line "why", and a stability tier so they can pick the most durable match.
function selectorMeta(opt) {
  const raw = (opt && opt.label) || '';
  const s = raw.toLowerCase();
  const isCoord = opt && opt.isSelector === false;
  const GREEN = 'var(--tb-primary-green)', AMBER = 'var(--tb-amber)', RED = 'var(--tb-fail)';
  const mk = (tier, tierLabel, tierColor, friendly, why) => ({ tier, tierLabel, tierColor, friendly, why, raw });
  if (isCoord || s.includes('coordinate')) return mk('fragile', 'Fragile', RED, 'Screen coordinates', 'Taps an exact spot. Breaks on a different screen size, so use it only as a last resort.');
  if (s.includes('index')) return mk('fragile', 'Fragile', RED, 'Its position number', 'Finds the Nth element on screen. Breaks if anything is added or reordered.');
  if (s.includes('resource id') || s.includes('resourceid') || s.includes('test tag') || s.includes('testtag') || s.includes('unique id') || s.includes('uniqueid') || s.includes('data-testid') || s.includes('css'))
    return mk('solid', 'Rock solid', GREEN, 'Its accessibility ID', 'Matches the developer-set identifier (the accessibility identifier on iOS, the resource-id on Android). The most reliable option. It survives wording and layout changes.');
  if (s.includes('text') || s.includes('accessibility') || s.includes('content desc') || s.includes('label') || s.includes('hint') || s.includes('aria name') || (s.includes('name') && !s.includes('class')))
    return mk('stable', 'Stable', GREEN, 'Its label', 'Matches the element’s visible text. Reliable unless the wording changes.');
  if (s.includes('child of') || s.includes('contains child') || s.includes('collection') || s.includes('pane'))
    return mk('ok', 'Okay', AMBER, 'Its place in the layout', 'Matches by where it sits in the screen structure. Can break if the layout is rebuilt.');
  if (s.includes('above') || s.includes('below') || s.includes('left of') || s.includes('right of') || s.includes('neighbor') || s.includes('spatial'))
    return mk('ok', 'Okay', AMBER, 'Near another element', 'Matches by its position next to a labeled neighbor. Fine as a backup.');
  if (s.includes('class') || s.includes('role') || s.includes('type') || s.includes('state') || s.includes('checked') || s.includes('selected') || s.includes('focused') || s.includes('password'))
    return mk('ok', 'Okay', AMBER, 'Its type', 'Matches by what kind of control it is. Best when there is only one of its kind on screen.');
  return mk('ok', 'Okay', AMBER, raw || 'Match', 'One way Trailblaze can find this element.');
}

function tierTone(tier) { return tier === 'fragile' ? 'red' : (tier === 'solid' || tier === 'stable' ? 'green' : 'amber'); }

function platformBadge(id) {
  const s = (id || '').toLowerCase();
  if (s.includes('_ios_') || s.endsWith('_ios') || s.startsWith('ios_')) return 'iOS';
  if (s.includes('_android_') || s.endsWith('_android') || s.startsWith('android_')) return 'Android';
  if (s.includes('_web_') || s.endsWith('_web') || s.startsWith('web_')) return 'Web';
  return null;
}

function elementTitle(el, fallback) {
  if (!el) return fallback || 'this spot';
  const label = (el.label || '').trim();
  const type = (el.type || '').trim();
  if (label && type) return '“' + label + '” · ' + type;
  if (label) return '“' + label + '”';
  if (type) return type;
  return fallback || 'this spot';
}

// The catalog tools relevant to a proposed gesture: matched by name family (tap → tap* tools, etc.),
// scoped to the active target + platform, with cross-platform tools dropped (no android_ on iOS).
function relevantTools(catalog, gesture, scopeSet, platform) {
  const t = (gesture && gesture.type) || 'tap';
  const fam = (gesture && gesture.action === 'assertVisible') ? ['assert', 'verify']
    : t === 'swipe' ? ['swipe', 'scroll', 'fling']
      : t === 'inputText' ? ['input', 'type', 'text']
        : t === 'pressKey' ? ['press', 'key']
          : ['tap']; // tap / longPress
  const plat = (platform || '').toLowerCase();
  const otherPlats = ['ios', 'android', 'web', 'desktop'].filter((p) => p !== plat);
  return (catalog || []).filter((tool) => {
    const id = (tool.id || '').toLowerCase();
    if (!fam.some((f) => id.includes(f))) return false;
    if (scopeSet && tool.trailmap && !scopeSet.has(tool.trailmap)) return false;
    // Drop tools whose name targets a different platform (e.g. sample_web_* / android_* on iOS).
    if (otherPlats.some((p) => id.includes('_' + p + '_') || id.startsWith(p + '_'))) return false;
    return true;
  });
}

// Build a `- tools:` YAML for a catalog tool, pre-filling args derivable from the resolved
// element / gesture / selector and leaving the rest as placeholders.
function buildProposalToolYaml(tool, ctx) {
  const params = tool.parameters || [];
  const valueFor = (p) => {
    const n = p.name;
    if ((n === 'nodeSelector' || n === 'selector') && ctx.selectorObj) return ctx.selectorObj;
    if ((n === 'x' || n === 'startX') && ctx.gesture && ctx.gesture.x != null) return ctx.gesture.x;
    if ((n === 'y' || n === 'startY') && ctx.gesture && ctx.gesture.y != null) return ctx.gesture.y;
    if (n === 'text' && ctx.element && ctx.element.label) return ctx.element.label;
    if ((n === 'accessibilityText' || n === 'contentDescription') && ctx.element && ctx.element.label) return ctx.element.label;
    if (n === 'longPress') return false;
    if (n === 'reason' && ctx.element && ctx.element.label) return ctx.element.label + ' is visible';
    return '<' + p.type + (p.required ? '' : ', optional') + '>';
  };
  const args = {};
  params.forEach((p) => { args[p.name] = valueFor(p); });
  if (window.jsyaml) {
    return window.jsyaml.dump([{ tools: [{ [tool.id]: params.length ? args : {} }] }], { lineWidth: -1 }).replace(/\n+$/, '');
  }
  if (!params.length) return `- tools:\n  - ${tool.id}: {}`;
  return ['- tools:', `  - ${tool.id}:`].concat(params.map((p) => `      ${p.name}: ${JSON.stringify(valueFor(p))}`)).join('\n');
}

// Pull the nodeSelector out of the best resolved option, so a newly-picked selector-based tool
// inherits the same match instead of a placeholder.
function bestSelectorObj(options) {
  if (!window.jsyaml) return null;
  const best = (options || []).find((o) => o.isSelector);
  if (!best) return null;
  try {
    const doc = window.jsyaml.load(best.yaml);
    for (const it of (Array.isArray(doc) ? doc : [doc])) {
      if (it && Array.isArray(it.tools)) for (const tl of it.tools) {
        const a = tl[Object.keys(tl)[0]];
        if (a && a.nodeSelector) return a.nodeSelector;
      }
    }
  } catch (e) { /* ignore */ }
  return null;
}

// A seeded first-draft description for a proposed step, so "describe it" starts from a sentence.
function seedPrompt(action, element, gesture) {
  const label = element && (element.label || '').trim();
  const type = (element && (element.type || '').trim()) || 'element';
  const thing = label ? '“' + label + '”' : 'the ' + type;
  const t = gesture && gesture.type;
  if (action === 'assertVisible') return (label ? '“' + label + '”' : 'The ' + type) + ' is visible';
  if (t === 'swipe') return 'Swipe the screen';
  if (t === 'longPress') return 'Long-press ' + thing;
  if (t === 'inputText') return 'Type ' + (gesture && gesture.text ? '“' + gesture.text + '”' : 'text');
  if (t === 'pressKey') return 'Press ' + ((gesture && gesture.key) || 'key');
  return 'Tap ' + thing;
}

// ─── Saving into the trail library (shared by this screen and the Create screen's composer) ───

// The gate both save flows run before opening the dialog: something recorded, a trailhead picked
// when the workspace offers any (the trail needs a declared starting point), and the trailhead's
// required parameters filled. Returns the blocking message, or null when the save may proceed.
function trailSaveBlocker({ hasSteps, availTrailheads, selectedTh, thMissingRequired }) {
  if (!hasSteps) return 'Record at least one step before saving.';
  if (availTrailheads.length && !selectedTh) return 'Pick a trailhead first - it is required so the trail has a known starting point.';
  if (selectedTh && thMissingRequired.length) return 'Fill the trailhead required ' + (thMissingRequired.length === 1 ? 'parameter' : 'parameters') + ' (' + thMissingRequired.map((p) => p.name).join(', ') + ') first.';
  return null;
}

// The baked-in step 0: the chosen trailhead as a deterministic tools step, so the saved trail is
// reproducible from its known starting state. Null when no trailhead is chosen.
function trailheadLeadStep(selectedTh, thDetail, args) {
  if (!selectedTh || !window.jsyaml) return null;
  return {
    text: 'Start at ' + ((thDetail && thDetail.to) || selectedTh.name),
    yaml: window.jsyaml.dump([{ tools: trailheadRunTools(selectedTh.name, thDetail && thDetail.tools, args) }], { lineWidth: -1 }).replace(/\n+$/, ''),
    verify: false,
  };
}

// Write a new trail bundle into the library at `dest`: a blaze.yaml built from the steps' prompts
// plus the recorded <platform>.trail.yaml. `steps` is the full recorded list (lead step included),
// each { text, yaml, verify }. Returns { ok, id } or { ok: false, error }.
async function saveTrailToLibrary({ dest, target, platform, objective, steps }) {
  const nm = dest.split('/').filter(Boolean).pop() || (target || 'recording');
  const promptSteps = steps.filter((s) => (s.text || '').trim()).map((s) => ({ kind: s.verify ? 'verify' : 'step', text: s.text.trim() }));
  const blazeYaml = TB.buildBlazeYaml(nm, target, platform, objective, promptSteps);
  const created = await TB.createBundle(dest, blazeYaml);
  if (!created.success) return { ok: false, error: created.error || 'Could not create the trail.' };
  const variant = `${platform || 'recording'}.trail.yaml`;
  const trailYaml = buildRecordedTrailYaml(nm, target, platform, steps);
  const wrote = await TB.saveTrailFolderFile(created.id, variant, trailYaml);
  if (!wrote.success) return { ok: false, error: wrote.error || `Saved the trail but could not write ${variant}.` };
  return { ok: true, id: created.id };
}

// Save dialog shared by the Studio save flows: asks where in the trail library the new trail
// folder should live, then hands the destination path back to the caller. The path is relative to
// the workspace's primary trails root; folders are created as needed and the saved trail shows in
// the Trails list immediately.
function SaveTrailDialog({ target, busy, error, onCancel, onSave }) {
  // Default destination: a target-scoped, timestamped folder the user is free to overwrite.
  const [dest, setDest] = React.useState(() => (target ? target + '/' : '') + 'recorded-' + new Date().toTimeString().slice(0, 5).replace(':', ''));
  const ok = dest.trim() !== '' && !busy;
  return (
    <div className="tb-overlay" onClick={() => { if (!busy) onCancel(); }} style={{ alignItems: 'center', padding: 24 }}>
      <div className="tb-card" onClick={(e) => e.stopPropagation()} style={{ width: 'min(560px, 94vw)', padding: 24 }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 4 }}>
          <h2 className="tb-h2" style={{ fontSize: 17, margin: 0 }}>Save trail</h2>
          <Btn sm ico="x" onClick={onCancel}>Close</Btn>
        </div>
        <div className="tb-sub" style={{ fontSize: 12.5, marginBottom: 16 }}>Creates a trail folder (<span className="tb-mono">blaze.yaml</span> + recordings) at this path in your trail library - folders are created as needed.</div>
        <div className="tb-eyebrow" style={{ marginBottom: 6 }}>Folder path</div>
        <input
          autoFocus value={dest} spellCheck={false} placeholder="sample/login"
          className="tb-mono"
          onChange={(e) => setDest(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter' && ok) onSave(dest.trim()); if (e.key === 'Escape' && !busy) onCancel(); }}
          style={{ width: '100%', boxSizing: 'border-box', background: 'var(--bg-standard)', border: '1px solid var(--tb-hairline)', borderRadius: 8, padding: '8px 11px', color: 'var(--text-standard)', fontSize: 13, outline: 'none' }}
        />
        <div className="tb-sub" style={{ fontSize: 11, marginTop: 4 }}>Relative to the workspace · shows in the Trails list once saved</div>
        {error && <div role="alert" style={{ marginTop: 10, fontSize: 12, color: 'var(--tb-danger-text)' }}>{error}</div>}
        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8, marginTop: 18 }}>
          <Btn onClick={onCancel} disabled={busy}>Cancel</Btn>
          <Btn kind="primary" ico={busy ? 'loader-2' : 'save'} spin={busy} onClick={() => { if (ok) onSave(dest.trim()); }} disabled={!ok}>
            {busy ? 'Saving…' : 'Save trail'}
          </Btn>
        </div>
      </div>
    </div>
  );
}

// ─── Trailhead (the known state a trail starts from: step 0, clear → launch → sign in) ───

// The trailheads available for `target` on `platform`. Scoped by the platform token in the id
// (sample_android_signedIn -> android); ids with no token show for every platform.
// File-local on purpose: the app's JSX files share one global script scope, so this helper is kept
// self-contained rather than delegating to TB.trailheadsForPlatform (data-extract.jsx exposes that
// variant only as `TB.trailheadsForPlatform`, with no bare global, to avoid a same-name clash here).
function trailheadsForTarget(trailmaps, target, platform) {
  if (!target) return [];
  const tm = (trailmaps || []).find((t) => t.id === target);
  const all = (tm && tm.trailheads) || [];
  const plat = (platform || '').toLowerCase();
  const TOKENS = ['android', 'ios', 'web'];
  return all.filter((th) => {
    const id = (th.name || '').toLowerCase();
    const tagged = TOKENS.find((p) => id.includes('_' + p + '_') || id.endsWith('_' + p) || id.startsWith(p + '_'));
    return !tagged || !plat || tagged === plat;
  });
}

// Parse a *.trailhead.yaml body into { description, to, tools, className }. For a scripted (.ts)
// trailhead (`scripted` true), the same shape is extracted from its inline `trailhead: { to }`
// block via a lightweight regex instead — mirrors TrailmapCatalogBuilder's TRAILHEAD_INLINE scan on
// the Kotlin side. It never has a `tools:`/`class:` of its own: the tool itself IS the trailhead, so
// `tools` stays [] and `trailheadRunTools` below runs it by name (see the scripted-param fetch this
// enables in RecordScreen's trailhead-detail effect).
function parseTrailhead(text, scripted) {
  if (!text) return null;
  if (scripted) {
    const m = /trailhead\s*:\s*\{[^}]*\bto\s*:\s*["']([^"']+)["']/.exec(text);
    return { description: '', to: m ? m[1] : null, tools: [], className: null };
  }
  if (!window.jsyaml) return null;
  try {
    const doc = window.jsyaml.load(text);
    if (!doc || typeof doc !== 'object') return null;
    return {
      description: (doc.description || '').trim(),
      to: (doc.trailhead && doc.trailhead.to) || null,
      tools: Array.isArray(doc.tools) ? doc.tools : [],
      // CLASS-mode trailheads run by id and may need params; the schema is fetched by this class FQN.
      className: doc.class || null,
    };
  } catch (e) { return null; }
}

// Visibility rule for flattened discriminated-union params (mirrors the desktop picker and the
// MCP lowering): a param carrying visibleWhen applies only while the named discriminator param
// holds one of the gating values. Unset discriminator = hidden, not shown-by-default.
function paramVisible(p, args) {
  if (!p.visibleWhen) return true;
  const v = args[p.visibleWhen.parameterName];
  return !!v && (p.visibleWhen.values || []).includes(v);
}

// Placeholders must never clip mid-sentence: use the description's first backticked example when
// it has one (`/dl/view/activity`), else a short first sentence, else the bare type.
function paramPlaceholder(p) {
  const d = String(p.description || '');
  const tick = d.match(/`([^`]+)`/);
  if (tick) return tick[1];
  const first = (d.split('.')[0] || '').trim();
  return first && first.length <= 42 ? first : p.type;
}

// Descriptions render as hint lines, not code: drop the markdown backticks.
function cleanParamDesc(d) {
  return String(d || '').replace(/`/g, '');
}

// The sign-in identity param the Account picker drives. Only a free-text field qualifies - a param
// with validValues is a variant selector (e.g. the account-profile ladder), never the account
// itself. Dotted names test their leaf so `account.personaKey` doesn't read as "account".
function trailheadAccountParam(params) {
  return (params || []).find((p) => {
    if (p.validValues && p.validValues.length) return false;
    const leaf = p.name.split('.').pop();
    return /email|account/i.test(leaf);
  }) || null;
}

// The tool list that drives the device into a trailhead: a TOOLS-mode trailhead's inline list, or a
// CLASS-mode trailhead invoked by its id (`<id>: {}`) with any filled params. Dotted param names
// (account.email) nest under their parent key - the flattened schema is a UI shape, not the tool's
// input shape.
function trailheadRunTools(name, tools, args) {
  if (tools && tools.length) return tools;
  const filled = {};
  Object.entries(args || {}).forEach(([k, v]) => {
    const dot = k.indexOf('.');
    if (dot < 0) { filled[k] = v; return; }
    const parent = k.slice(0, dot);
    if (filled[parent] == null || typeof filled[parent] !== 'object') filled[parent] = {};
    filled[parent][k.slice(dot + 1)] = v;
  });
  return [{ [name]: filled }];
}

// Wrap a trailhead's tools in the runnable trail shape so "Go to trailhead" replays it on the device.
function buildTrailheadRunYaml(name, tools, args) {
  if (!window.jsyaml) return null;
  const lines = ['- config:', `    title: ${JSON.stringify('Trailhead: ' + name)}`, '- prompts:', `  - step: ${JSON.stringify('Enter trailhead: ' + name)}`, '    recording:', '      tools:'];
  window.jsyaml.dump(trailheadRunTools(name, tools, args), { lineWidth: -1 }).replace(/\n+$/, '').split('\n').forEach((l) => lines.push(l ? '      ' + l : l));
  return lines.join('\n');
}

// The required "Trailhead" step 0 at the top of the recorded-steps list: a dropdown of where it lands
// you, plus an Account picker for its sign-in account. Required when the target has trailheads.
function TrailheadStep({ target, platform, trailheads, selected, onSelect, detail, loading, metaByName = {}, params = [], args = {}, onArg, missingRequired = [], accountParam = null, accountValue = '', onAccount, canRun, onGoToTrailhead, run, go, expanded = true, onExpand }) {
  const platLabel = platform === 'ios' ? 'iOS' : platform === 'android' ? 'Android' : platform === 'web' ? 'Web' : (platform || '');
  const empty = !trailheads.length;
  const blockedByParams = missingRequired.length > 0;
  const meta = selected ? (metaByName[selected.name] || {}) : null;
  const dropTitle = selected
    ? (selected.name + (meta && meta.to ? '  →  ' + meta.to : '') + (meta && meta.description ? '\n\n' + meta.description : '') + (detail && detail.relPath ? '\n\n' + detail.relPath : ''))
    : 'Pick the known state this trail starts from';
  const ddOptions = [{ value: '', label: 'Pick a trailhead…', ico: 'flag', icoColor: 'var(--text-subtle)' },
    ...trailheads.map((t) => {
      const m = metaByName[t.name] || {};
      const pb = platformBadge(t.name);
      return {
        value: t.name, ico: 'flag', icoColor: 'var(--tb-ai)',
        label: m.to || t.name, short: m.to || t.name,
        badges: pb ? [{ text: pb }] : [],
        meta: (m.to && m.to !== t.name) ? <span className="tb-mono">{t.name}</span> : null,
        desc: m.description || '',
      };
    })];
  // The account/email param is driven by the Account picker, not this form; params gated on an
  // unselected variant (visibleWhen) stay out entirely.
  const formParams = params.filter((p) => paramVisible(p, args) && (!accountParam || p.name !== accountParam.name));
  const accountSummary = accountParam ? (accountValue || 'no account') : 'Signed out';
  const summaryLabel = empty ? `No ${platLabel || ''} trailheads`.replace('  ', ' ') : selected ? ((meta && meta.to) || selected.name) : 'Pick a trailhead…';
  return (
    <div className="tb-card" onClick={() => { if (!expanded && onExpand) onExpand(); }}
      style={{ overflow: 'hidden', flexShrink: 0, borderColor: 'rgba(123,97,255,.5)', cursor: expanded ? 'default' : 'pointer' }}>
      {/* Header — flag badge + title; collapsed shows the settled value on the right. */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: expanded ? '11px 12px 0' : '11px 12px' }}>
        <span style={{ width: 20, height: 20, borderRadius: 6, background: 'rgba(123,97,255,.16)', display: 'grid', placeItems: 'center', flex: '0 0 auto' }}>
          <Ico n="flag" s={12} c="var(--tb-ai)" />
        </span>
        <span style={{ fontSize: 12.5, fontWeight: 700, color: 'var(--text-standard)' }}>Trailhead</span>
        <span title="Required — the known state your trail starts from" style={{ color: 'var(--tb-danger-text)', fontWeight: 700, lineHeight: 1 }}>*</span>
        {expanded ? (
          <span className="tb-sub" style={{ fontSize: 11 }}>starting state</span>
        ) : (
          <>
            <span style={{ flex: 1 }} />
            <span style={{ fontSize: 12, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', color: selected ? 'var(--text-standard)' : 'var(--text-subtle)' }}>
              {summaryLabel}{!empty && selected ? <span className="tb-sub" style={{ fontSize: 11.5 }}> · {accountSummary}</span> : null}
            </span>
          </>
        )}
      </div>
      {/* Collapsible editing surface — framed fields + params + footer. */}
      <div style={{ maxHeight: expanded ? 520 : 0, opacity: expanded ? 1 : 0, overflow: 'hidden', transition: 'max-height .28s var(--ease-out-soft), opacity .2s var(--ease-out-soft)' }}>
      {/* Destination dropdown + Account picker, split by a divider. */}
      {!empty && (
        <div style={{ margin: '9px 12px 12px', display: 'flex', border: '1px solid var(--tb-hairline)', borderRadius: 10, overflow: 'hidden' }}>
          <div style={{ flex: 1, minWidth: 0, padding: '9px 11px', display: 'flex', flexDirection: 'column', gap: 5 }}>
            <span className="tb-eyebrow" style={{ fontSize: 9 }}>Starts at</span>
            <Select full value={selected ? selected.name : ''} onChange={(e) => onSelect(e.target.value)} title={dropTitle} options={ddOptions} />
          </div>
          <div style={{ width: 1, background: 'var(--tb-hairline)', flex: '0 0 auto' }} />
          <div style={{ flex: 1, minWidth: 0, padding: '9px 11px', display: 'flex', flexDirection: 'column', gap: 5 }}>
            <span className="tb-eyebrow" style={{ fontSize: 9 }}>Account</span>
            {accountParam ? (
              /* Free-text account — the desktop app doesn't ship preset accounts. */
              <input value={accountValue} onChange={(e) => onAccount && onAccount(e.target.value)}
                placeholder="account email…" spellCheck={false} title="Sign-in account for this trailhead"
                style={{ width: '100%', boxSizing: 'border-box', height: 30, background: 'var(--bg-elevated)', border: '1px solid var(--tb-hairline-strong)', borderRadius: 8, outline: 'none', color: 'var(--text-standard)', fontSize: 12, padding: '0 11px' }} />
            ) : (
              <span className="tb-sub" title="This trailhead needs no sign-in" style={{ fontSize: 12, display: 'inline-flex', alignItems: 'center', gap: 5, padding: '6px 0' }}><Ico n="user-x" s={13} /> Signed out</span>
            )}
          </div>
        </div>
      )}
      {/* Any non-account params still get an editable row (required ones gate Go + save). */}
      {!empty && selected && formParams.length > 0 && (
        <div style={{ borderTop: '1px solid var(--tb-hairline)', background: 'var(--bg-standard)', padding: '10px 12px', display: 'flex', flexDirection: 'column', gap: 8 }}>
          {formParams.map((p) => {
            // An enum param (validValues) is a fixed choice - a Select, never free text. The
            // selected variant's description rides as the option hint.
            const isSelect = !!(p.validValues && p.validValues.length);
            return (
              <div key={p.name} style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                <label style={{ width: 130, flex: '0 0 auto', fontSize: 12, fontWeight: 600, color: 'var(--text-standard)' }} title={cleanParamDesc(p.description)}>
                  <span className="tb-mono">{p.name}</span>{p.required && <span style={{ color: 'var(--tb-danger-text)', fontWeight: 700 }}> *</span>}
                </label>
                {isSelect ? (
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <Select full value={args[p.name] || ''} onChange={(e) => onArg && onArg(p.name, e.target.value)}
                      options={p.validValues.map((v, i) => ({ value: v, label: v, desc: cleanParamDesc((p.validValueDescriptions || [])[i] || '') || undefined }))} />
                  </div>
                ) : (
                  <input value={args[p.name] || ''} onChange={(e) => onArg && onArg(p.name, e.target.value)}
                    placeholder={paramPlaceholder(p)} spellCheck={false} title={cleanParamDesc(p.description)}
                    style={{ flex: 1, minWidth: 0, background: '#0a0a0a', border: '1px solid ' + (p.required && !String(args[p.name] || '').trim() ? 'var(--tb-danger-text)' : 'var(--tb-hairline)'), borderRadius: 8, outline: 'none', color: 'var(--text-standard)', padding: '7px 10px', font: 'inherit', fontSize: 13 }} />
                )}
              </div>
            );
          })}
        </div>
      )}
      {/* Body — source link + go button. */}
      <div style={{ borderTop: '1px solid var(--tb-hairline)', background: 'var(--bg-standard)', padding: '10px 12px', display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
        {empty ? (
          <span className="tb-sub" style={{ fontSize: 12, display: 'inline-flex', alignItems: 'center', gap: 7 }}>
            <Ico n="triangle-alert" s={14} c="var(--tb-amber)" />
            No {platLabel} trailheads for <span className="tb-mono">{target}</span> yet — record from the current screen, or <a onClick={() => go('trailheads')} style={{ color: 'var(--tb-primary-green)', cursor: 'pointer' }}>create one</a>.
          </span>
        ) : !selected ? (
          <span className="tb-sub" style={{ fontSize: 12 }}>Pick where your recording should start, then drive the device into it.</span>
        ) : (
          <>
            {/* Run result once you've gone to the trailhead, otherwise the source link. */}
            {run && !run.running ? (
              <span style={{ flex: 1, minWidth: 0, fontSize: 11.5, display: 'inline-flex', alignItems: 'center', gap: 6, color: run.ok ? 'var(--tb-pass)' : 'var(--tb-danger-text)' }} title={run.text}>
                <Ico n={run.ok ? 'circle-check' : 'circle-x'} s={13} style={{ flex: '0 0 auto' }} />
                <span style={{ minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{run.text}</span>
              </span>
            ) : (
              <>
                {detail && detail.relPath && (
                  <button onClick={() => TB.revealToolSource({ path: detail.relPath })} title={detail.relPath}
                    style={{ display: 'inline-flex', alignItems: 'center', gap: 5, background: 'none', border: 'none', cursor: 'pointer', color: 'var(--text-subtle)', fontSize: 11, fontFamily: 'var(--font-mono)', flex: '0 0 auto' }}>
                    <Ico n="file-code-2" s={12} /> source
                  </button>
                )}
                <span style={{ flex: 1 }} />
              </>
            )}
            <Btn sm kind="primary" ico={run && run.running ? 'loader-2' : 'flag'} spin={run && run.running}
              onClick={onGoToTrailhead} disabled={!selected || !canRun || blockedByParams || (run && run.running)}
              title={!selected ? 'Pick a trailhead first' : !canRun ? 'Connect a device first' : blockedByParams ? 'Fill ' + missingRequired.map((p) => p.name).join(', ') + ' first' : 'Drive this device into the trailhead'}>
              {run && run.running ? 'Going…' : 'Go to trailhead'}
            </Btn>
          </>
        )}
      </div>
      </div>
    </div>
  );
}

// The Interact screen: connect a device, mirror it, and turn gestures into editable steps. Reads
// top-to-bottom as state → connect/mirror → propose/confirm → tree inspector → step editing →
// trailhead/save → render; the `// ───` dividers below mark those sections.
function RecordScreen({ go, active, yamlSeed }) {
  useLucide();
  const [gt] = TB.useGlobalTarget();
  const devices = TB.useDevices();
  const tools = TB.useTools();
  // Catalog tool ids — lets a recorded tool name render as a link into the Tools screen.
  const toolMap = React.useMemo(() => new Set((tools.data || []).map((t) => t.id)), [tools.data]);
  const deviceList = (devices.data || []).filter((d) => d.connected);

  // Companion guided recording: `go('interact', { companion })` from the companion screen arms
  // this surface for an agent-directed demonstration - the save destination is FIXED to the
  // companion session's declared folder (the one sanctioned UI write in companion mode) and the
  // device connect / save are reported back to the attached agent as user actions. LATCHED, not
  // read live: the shell's payload is scoped to the visible route, so it goes null on any
  // navigation (including the agent's own live navigate directive) - and losing it mid-recording
  // would silently reopen the unsanctioned library-save path. Released on save success, or on a
  // terminal save failure (session gone) so the demonstrated steps aren't stranded.
  const [companion, setCompanion] = React.useState((yamlSeed && yamlSeed.companion) || null);
  // Guards re-delivery of ANY previously adopted payload (the history stack stores payload
  // objects and replays them on back/forward - an old cycle's payload must never re-arm a stale
  // destination or wipe current steps) and re-entry into the SAME cycle (re-clicking Start
  // recording makes a fresh object; so does re-arming after Cancel, which nulls the latch - hence
  // same-cycle is judged against the last ADOPTED payload, not the live latch). Only a genuinely
  // new cycle resets.
  const adoptedCompanionRef = React.useRef(null); // the last adopted payload, for same-cycle detection
  const adoptedSeenRef = React.useRef(null); // every payload ever adopted (WeakSet)
  if (!adoptedSeenRef.current) adoptedSeenRef.current = new WeakSet();
  // The live latch for async resolvers: a connect can be 120s in flight, and reporting to the
  // click-time closure would announce a device for a cycle that was cancelled meanwhile. Kept in
  // lockstep IN THE SAME TICK as the state (not via an effect): a resolver landing between
  // setCompanion and React's commit must not read a one-render-stale latch.
  const companionRef = React.useRef(companion);
  const latchCompanion = (v) => { companionRef.current = v; setCompanion(v); };
  React.useEffect(() => {
    const c = (yamlSeed && yamlSeed.companion) || null;
    if (!c || !c.runId || adoptedSeenRef.current.has(c)) return;
    adoptedSeenRef.current.add(c);
    const prev = adoptedCompanionRef.current;
    const sameCycle = prev && prev.runId === c.runId && (prev.variant || null) === (c.variant || null);
    // Steps recorded OUTSIDE any companion cycle are unsaved library work sitting on this
    // mounted-but-hidden screen; destroying them for the agent's cycle needs the human's say-so.
    // Declining leaves the latch unarmed - the agent's ask stays standing on the companion view.
    if (!sameCycle && !companionRef.current && steps.length > 0 &&
        !window.confirm(`Discard ${steps.length} unsaved recorded step${steps.length === 1 ? '' : 's'} and start the agent-directed recording?`)) {
      return;
    }
    // Adopting over a DIFFERENT session's live cycle abandons that cycle: tell its agent, or it
    // tails for a recording-saved that can never come (its destination was just replaced).
    const old = companionRef.current;
    if (old && old.runId !== c.runId) {
      TB.companionUserAction(old.runId, 'user-action', { actionId: 'recording-cancelled' });
    }
    adoptedCompanionRef.current = c;
    latchCompanion(c);
    if (!sameCycle) {
      // A new companion cycle starts from a clean slate: steps demonstrated for a previous
      // variant/session must not leak into this save (screens stay mounted when hidden), and the
      // device dedupe rescopes so this cycle's connect re-announces to the agent.
      deviceReportedRef.current = null;
      setSteps([]); setPending(null); setPendingPrompt(''); setTestStatus({}); setSaveErr(null); setSaveOpen(false);
    }
    // A device connected earlier is this cycle's answer too - conn survives screen hides, so
    // connect() (the only other announcer) may never run again. Same-cycle re-entry announces
    // too: Cancel cleared the dedupe, so re-arming the same variant answers a re-posted
    // select-device ask instead of leaving it standing against a connected device.
    if (conn && connDeviceRef.current) reportCompanionDevice(c, connDeviceRef.current);
  }, [yamlSeed]);

  // The raw connected devices carry the structured trailblazeDeviceId the record API needs;
  // useDevices() normalizes that away, so keep an id → trailblazeDeviceId map.
  const [rawById, setRawById] = React.useState({});
  React.useEffect(() => {
    let alive = true;
    window.TbRpc.getConnectedDevices().then((raw) => {
      if (!alive) return;
      const map = {};
      (raw?.devices || []).forEach((d) => {
        const id = d.instanceId || d.trailblazeDeviceId?.instanceId;
        if (id) map[id] = d.trailblazeDeviceId || { instanceId: id, trailblazeDevicePlatform: (d.platform || '').toUpperCase() };
      });
      setRawById(map);
    });
    return () => { alive = false; };
  }, [devices.data]);

  const target = (gt && gt.target) || null;
  const preferredId = (gt && (gt.deviceIds || [])[0]) || (deviceList[0] && deviceList[0].id) || null;
  const [selectedId, setSelectedId] = React.useState(preferredId);
  React.useEffect(() => {
    if ((!selectedId || !deviceList.find((d) => d.id === selectedId)) && deviceList.length) {
      setSelectedId(preferredId || deviceList[0].id);
    }
  }, [devices.data]);
  const device = deviceList.find((d) => d.id === selectedId) || null;
  const platform = device ? device.platform : null;
  // The structured trailblazeDeviceId for any device id.
  const tbIdFor = (sid) => {
    if (!sid) return null;
    if (rawById[sid]) return rawById[sid];
    const d = deviceList.find((x) => x.id === sid);
    return d ? { instanceId: d.id, trailblazeDevicePlatform: (d.platform || '').toUpperCase() } : null;
  };
  const tbId = () => tbIdFor(selectedId);

  // ─── Trailhead (required: the known state this trail starts from) ───
  const trailmaps = TB.useTrailmaps();
  const availTrailheads = React.useMemo(
    () => trailheadsForTarget(trailmaps.data, target, platform),
    [trailmaps.data, target, platform],
  );
  // Remember the pick per target+platform under one fixed sticky key (a dynamic useStickyState key
  // would mis-load on scope change) — so we key into a map instead.
  const [thByScope, setThByScope] = useStickyState('tb-interact-trailhead', {});
  const thScopeKey = (target || '') + '|' + (platform || '');
  const storedThId = thByScope[thScopeKey] || '';
  // Default to the sole option; an explicit choice (or a multi-option scope) overrides it.
  const selectedTh = availTrailheads.find((t) => t.name === storedThId)
    || (availTrailheads.length === 1 ? availTrailheads[0] : null);
  const setTrailheadId = (id) => setThByScope((m) => ({ ...m, [thScopeKey]: id }));
  const [thDetail, setThDetail] = React.useState(null); // { description, to, tools, className, name, relPath }
  const [thLoading, setThLoading] = React.useState(false);
  const [thRun, setThRun] = React.useState(null); // { running } | { ok, text }
  const [thParams, setThParams] = React.useState([]); // param schema for a CLASS-mode trailhead
  const [thArgs, setThArgs] = React.useState({}); // { paramName: value } the author fills in
  // Destination (`to`) + description for every available trailhead, for the picker rows + hover.
  const [thMetaByName, setThMetaByName] = React.useState({});
  const availKey = availTrailheads.map((t) => t.relPath).join('|');
  React.useEffect(() => {
    let alive = true;
    Promise.all(availTrailheads.map(async (t) => {
      const p = parseTrailhead(await TB.fetchComponentSource(t.relPath), t.relPath.endsWith('.ts'));
      return [t.name, { to: (p && p.to) || null, description: (p && p.description) || '' }];
    })).then((pairs) => { if (alive) setThMetaByName(Object.fromEntries(pairs)); });
    return () => { alive = false; };
  }, [availKey]);
  const selectedThPath = selectedTh ? selectedTh.relPath : null;
  React.useEffect(() => {
    if (!selectedThPath) { setThDetail(null); setThParams([]); setThArgs({}); return; }
    let alive = true;
    setThLoading(true); setThRun(null); setThParams([]); setThArgs({});
    const scripted = selectedThPath.endsWith('.ts');
    TB.fetchComponentSource(selectedThPath).then(async (src) => {
      if (!alive) return;
      const parsed = parseTrailhead(src, scripted) || { description: '', to: null, tools: [], className: null };
      setThDetail({ ...parsed, name: selectedTh.name, relPath: selectedThPath });
      setThLoading(false);
      // Required discriminators (enum params) start on their first variant so their companion
      // fields show and the form never gates on an invisible choice.
      const applyParams = (params) => {
        if (!alive) return;
        setThParams(params || []);
        const seed = {};
        (params || []).forEach((p) => { if (p.required && p.validValues && p.validValues.length) seed[p.name] = p.validValues[0]; });
        if (Object.keys(seed).length) setThArgs((a) => ({ ...seed, ...a }));
      };
      // CLASS-mode trailhead with no inline tools → fetch its param schema so required args can be filled.
      if (parsed.className && !(parsed.tools && parsed.tools.length)) {
        applyParams(await TB.recordToolParams(parsed.className));
      } else if (scripted && target && TB.scriptedToolParams) {
        // Scripted (.ts) trailhead — the tool itself IS the trailhead, so its own `<I>` fields are
        // the required args. Same on-demand fetch ProposalToolRow already does for a scripted tool
        // dropped into a recording step; without this, required args (e.g. email/password) would
        // silently stay unfilled and the baked run would call the tool with `{}`.
        applyParams(await TB.scriptedToolParams(target, selectedTh.name));
      }
    }).catch(() => { if (alive) { setThDetail(null); setThLoading(false); } });
    return () => { alive = false; };
  }, [selectedThPath]);

  // The "account" param (a sign-in email) is driven by the Account picker beside the trailhead.
  const accountParam = trailheadAccountParam(thParams);
  const accountValue = accountParam ? (thArgs[accountParam.name] || '') : '';
  const setAccount = (email) => { if (accountParam) setThArgs((a) => ({ ...a, [accountParam.name]: email })); };
  // Visible required params still missing a value - blocks "Go to trailhead" and save until
  // filled. Params hidden behind an unselected variant (visibleWhen) never gate.
  const thMissingRequired = thParams.filter((p) => paramVisible(p, thArgs) && p.required && !String(thArgs[p.name] || '').trim());
  // The filled args (visible, trimmed, non-empty) to pass when running/baking the trailhead.
  const thFilledArgs = () => {
    const out = {};
    thParams.forEach((p) => { if (!paramVisible(p, thArgs)) return; const v = String(thArgs[p.name] || '').trim(); if (v) out[p.name] = v; });
    return out;
  };

  // Drive the connected device into the selected trailhead by replaying its tools (via runToolQuick).
  async function goToTrailhead() {
    if (!selectedTh || !conn || (thRun && thRun.running)) return;
    if (thMissingRequired.length) { setThRun({ ok: false, text: 'Fill ' + thMissingRequired.map((p) => p.name).join(', ') + ' first.' }); return; }
    const yaml = buildTrailheadRunYaml(selectedTh.name, thDetail && thDetail.tools, thFilledArgs());
    if (!yaml) { setThRun({ ok: false, text: 'Could not build the trailhead run.' }); return; }
    setThRun({ running: true });
    const r = await TB.runToolQuick(yaml, tbId());
    setThRun({ ok: r.success === true, text: r.success === true ? (r.result || 'Arrived at the trailhead') : (r.error || 'Could not reach the trailhead') });
    refreshTreeIfShown();
  }

  const [conn, setConn] = React.useState(null); // { deviceWidth, deviceHeight } when connected
  const [connecting, setConnecting] = React.useState(false);
  const [connErr, setConnErr] = React.useState(null);
  const [frame, setFrame] = React.useState(false); // true once the live stream has rendered ≥1 frame
  const [busy, setBusy] = React.useState(false); // a gesture is dispatching
  const [steps, setSteps] = React.useState([]);
  const [saving, setSaving] = React.useState(false);
  const [saveErr, setSaveErr] = React.useState(null);
  const [saveOpen, setSaveOpen] = React.useState(false);
  // The trail's objective in plain language; feeds blaze.yaml's objective so the agent knows the
  // intent (recover from unexpected screens, re-derive steps on other platforms).
  const [context, setContext] = React.useState('Validates that a user can ');
  // Broadcast the in-progress session to the shell: the nav rail's "Create" group lists
  // it (device connected or steps captured = a session worth coming back to). All session state is
  // in-component, so this window event is the rail's only view into it; the unmount cleanup clears
  // the rail row when the screen is remounted for a new session.
  const interactSessionActive = !!conn || steps.length > 0;
  React.useEffect(() => {
    const label = (context || '').trim();
    const detail = interactSessionActive
      ? { active: true, label: (label && label !== 'Validates that a user can' ? label : 'Recording session'), steps: steps.length }
      : { active: false };
    window.dispatchEvent(new CustomEvent('tb:interact-session', { detail }));
  }, [interactSessionActive, steps.length, context]);
  React.useEffect(() => () => { window.dispatchEvent(new CustomEvent('tb:interact-session', { detail: { active: false } })); }, []);
  // Guided focus walks the author through one stage at a time (Context → Trailhead → Steps).
  // `contextFocused` keeps Context active while they're still typing it (so the highlight doesn't
  // jump away on the first keystroke).
  const [contextFocused, setContextFocused] = React.useState(false);
  // A done stage the user tapped to re-open for editing; cleared as the flow advances.
  const [editing, setEditing] = React.useState(null);
  // A mirror gesture proposed but not yet confirmed; only dispatched + recorded once confirmed.
  const [pending, setPending] = React.useState(null);
  const [pendingPrompt, setPendingPrompt] = React.useState('');
  // Arms the next device tap to APPEND that tool to the open step (null | 'tap' | 'assertVisible'),
  // instead of the default re-target behavior.
  const [appendArm, setAppendArm] = React.useState(null);
  // Intent for the next mirror tap: 'interact' drives the app; 'check' records an assertion.
  const [mode, setMode] = React.useState('interact');
  // Right panel: the recorded trail ('steps'), the on-screen element inspector ('elements'),
  // or the ad hoc YAML runner ('runyaml' — the old standalone Run YAML screen, folded in).
  const [rightTab, setRightTab] = React.useState('steps');
  // Ad hoc YAML runner state: paste trail YAML, dispatch it as a full run on this device.
  const [adhocYaml, setAdhocYaml] = React.useState('');
  const [adhocName, setAdhocName] = React.useState('');
  const [adhocRunning, setAdhocRunning] = React.useState(false);
  const [adhocErr, setAdhocErr] = React.useState(null);
  // A `go('interact', { openYaml, yaml, name })` payload (the command palette's "Run ad hoc
  // YAML…" action) opens the Run YAML tab, prefilled when it carries yaml. Keyed on the payload
  // object itself — each navigation delivers a fresh one, so re-invoking it re-opens the tab.
  React.useEffect(() => {
    if (!yamlSeed || (!yamlSeed.openYaml && !yamlSeed.yaml)) return;
    setRightTab('runyaml');
    if (yamlSeed.yaml) { setAdhocYaml(yamlSeed.yaml); setAdhocName(yamlSeed.name || ''); setAdhocErr(null); }
  }, [yamlSeed]);
  const [els, setEls] = React.useState([]);
  const [elsLoading, setElsLoading] = React.useState(false);
  const [elsErr, setElsErr] = React.useState(null);
  const [elQuery, setElQuery] = React.useState('');
  // The element bounds currently highlighted on the mirror (from hovering the Elements list).
  const [hoverEl, setHoverEl] = React.useState(null);
  // View-tree overlay: labeled boxes on the mirror, toggled from the device frame (independent of tab).
  const [showTree, setShowTree] = React.useState(false);
  // Steps view: the editable cards, or the read-only assembled trail YAML (top toggle).
  const [showYaml, setShowYaml] = React.useState(false);
  // "Test trail" — replays every step on the device. testStatus maps a step id to
  // 'running' | 'pass' | 'fail' so the matching card lights up inline.
  const [testing, setTesting] = React.useState(false);
  const [testStatus, setTestStatus] = React.useState({});
  const testAbortRef = React.useRef(false);

  const stepId = React.useRef(1);
  const imgWrapRef = React.useRef(null);
  const downRef = React.useRef(null);
  const stepsScrollRef = React.useRef(null); // the Steps list scroller, for auto-scrolling to a new step
  // Guided-focus refs; prevStageRef debounces the focus/scroll effect to real stage transitions.
  const contextRef = React.useRef(null);
  const contextCardRef = React.useRef(null);
  const trailheadWrapRef = React.useRef(null);
  const stepsWrapRef = React.useRef(null);
  const prevStageRef = React.useRef(null);
  const connectAbortRef = React.useRef(null); // AbortController for the in-flight connect
  const connDeviceRef = React.useRef(null); // the tbId we're connected to, for cleanup
  const deviceReportedRef = React.useRef(null); // "<runId>|<instanceId>" last announced to a companion agent
  const mirrorImgRef = React.useRef(null); // JPEG/poll fallback surface
  const mirrorCanvasRef = React.useRef(null); // H.264 (WebCodecs) surface
  const firstFrameSeen = React.useRef(false); // gate the one-time first-frame setState
  const mirrorOpChainRef = React.useRef(Promise.resolve());
  const queueMirrorOp = (fn) => {
    const run = mirrorOpChainRef.current.then(fn, fn);
    mirrorOpChainRef.current = run.then(() => {}, () => {});
    return run;
  };
  // Measured height of the screen root, so the device sizes to fit the viewport (no scrollbar).
  const rootRef = React.useRef(null);
  const [rootH, setRootH] = React.useState(0);
  React.useEffect(() => {
    const el = rootRef.current;
    if (!el || typeof ResizeObserver === 'undefined') return;
    const ro = new ResizeObserver((entries) => {
      const h = entries[0] && entries[0].contentRect.height;
      if (h) setRootH((prev) => (Math.abs(prev - h) > 1 ? h : prev));
    });
    ro.observe(el);
    setRootH(el.clientHeight);
    return () => ro.disconnect();
  }, []);
  // Resizable split between the device and steps columns. `dragging` suppresses the frame's resize
  // transition so the drag tracks the cursor 1:1 instead of rubber-banding.
  const [leftW, startDragRaw] = useResizableWidth('tb-interact-leftw', 460, 320, 860);
  const [dragging, setDragging] = React.useState(false);
  const startDrag = (e) => {
    setDragging(true);
    const up = () => { setDragging(false); window.removeEventListener('mouseup', up); };
    window.addEventListener('mouseup', up);
    startDragRaw(e);
  };

  const appendStep = (s) => setSteps((prev) => [...prev, { id: stepId.current++, ...s }]);

  // Reset the mirror surfaces + first-frame gate (on disconnect / device switch). The live-stream
  // handle owns object-URL lifecycle and hides the surfaces itself; this just clears our React gate.
  const clearFrame = () => {
    firstFrameSeen.current = false;
    setFrame(false);
    if (mirrorImgRef.current) mirrorImgRef.current.removeAttribute('src');
  };

  // Keep the newest item in view: when a step is added or a proposal appears at the bottom, scroll there.
  React.useEffect(() => {
    if (rightTab !== 'steps') return;
    const el = stepsScrollRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [steps.length, pending, rightTab]);

  async function connect(explicitId) {
    // explicitId is only honored when it's a real device id — guards against being called as an
    // onClick handler (where the first arg is a DOM event, not a device id).
    const id = (explicitId && explicitId.instanceId) ? explicitId : tbId();
    if (!id || connecting) return;
    const controller = new AbortController();
    connectAbortRef.current = controller;
    // The first connect can install the on-device runner (slow); auto-abort after 120s.
    const timer = setTimeout(() => controller.abort(), 120000);
    setConnecting(true); setConnErr(null);
    const r = await TB.recordConnect(id, controller.signal);
    clearTimeout(timer);
    // A newer connect/cancel superseded this attempt — drop the stale result.
    if (connectAbortRef.current !== controller) return;
    connectAbortRef.current = null;
    setConnecting(false);
    if (r.aborted) { if (controller.signal.reason !== 'user') setConnErr('Connecting timed out — the on-device runner may be slow to install. Try again.'); return; }
    if (!r.ok) { setConnErr(r.error || 'Could not connect to the device.'); return; }
    connDeviceRef.current = id;
    setConn({ deviceWidth: r.deviceWidth, deviceHeight: r.deviceHeight });
    // The agent asked for this demonstration: tell it the device is up. Read the latch through
    // the ref, not the click-time closure - a connect can be 120s in flight, and the cycle that
    // was current at click time may have been cancelled or replaced by now.
    reportCompanionDevice(companionRef.current, id);
  }

  // Tell the attached agent a device is up, so its select-device ask resolves. Fire-and-forget -
  // a failed report must not block the human's recording. Deduped against the LAST announced
  // session+device (a reconnect hiccup doesn't re-announce; switching A→B→A announces A again,
  // which is harmless - the daemon-side retract is idempotent).
  function reportCompanionDevice(comp, id) {
    if (!comp || !id || !id.instanceId) return;
    const key = comp.runId + '|' + id.instanceId;
    if (deviceReportedRef.current === key) return;
    deviceReportedRef.current = key;
    TB.companionUserAction(comp.runId, 'device-connected', {
      device: id.instanceId, platform: (id.trailblazeDevicePlatform || '').toLowerCase(),
    }).then((r) => {
      // A failed announce must not burn the dedupe: free the key so a later connect or adopt can
      // retry - unless a newer announce owns it by now.
      if (!r.ok && deviceReportedRef.current === key) deviceReportedRef.current = null;
    });
  }

  // Cancel an in-flight connect. The daemon may finish the install in the background; a later
  // connect reuses it instantly.
  function cancelConnect() {
    const c = connectAbortRef.current;
    connectAbortRef.current = null;
    setConnecting(false); setConnErr(null);
    if (c) c.abort('user');
  }

  async function disconnect() {
    const id = connDeviceRef.current;
    connDeviceRef.current = null;
    setConn(null); clearFrame(); setAppendArm(null);
    if (id) await queueMirrorOp(() => TB.recordDisconnect(id));
  }

  // Switch the connected device from the in-frame dropdown: disconnect, select, reconnect.
  async function switchDevice(v) {
    if (!v || v === selectedId || connecting) return;
    if (conn) await disconnect();
    setSelectedId(v);
    setSteps((s) => s); // keep recorded steps across a device switch
    setEls([]); setElsErr(null); setPending(null);
    await connect(tbIdFor(v));
  }

  // Live device mirror, only while connected AND this screen is the visible tab (screens stay mounted
  // when hidden). Uses the shared /devices/api/stream transport: H.264 via WebCodecs on Android/iOS,
  // CDP JPEG on Web, degrading through the SubscribeFrames JPEG stream to a snapshot poll. The device
  // is already connected (recordConnect published it into the shared session registry), so the
  // transport streams without re-connecting and never disconnects it — this screen owns that.
  React.useEffect(() => {
    if (!conn || !active) return;
    const id = connDeviceRef.current;
    if (!id || !window.TbLiveDeviceStream) return;
    firstFrameSeen.current = false;
    setFrame(false);
    const handle = window.TbLiveDeviceStream.openLiveDeviceStream({
      deviceId: id,
      deviceWidth: conn.deviceWidth,
      deviceHeight: conn.deviceHeight,
      img: mirrorImgRef.current,
      canvas: mirrorCanvasRef.current,
      onFrame: () => { if (!firstFrameSeen.current) { firstFrameSeen.current = true; setFrame(true); } },
      pollFrame: async (deviceId) => {
        const result = await TB.recordScreen(deviceId);
        if (!result.ok) throw new Error(result.error || 'Could not capture the device screen.');
        return result;
      },
      onNotConnected: async () => {
        if (connDeviceRef.current !== id) return;
        const result = await queueMirrorOp(() => TB.recordConnect(id));
        if (!result.ok) throw new Error(result.error || 'Could not reconnect to the device.');
      },
    });
    return () => handle.close();
  }, [conn, active]);

  // Release the live connection (and abort any in-flight connect) when the screen actually unmounts.
  React.useEffect(() => () => {
    if (connectAbortRef.current) connectAbortRef.current.abort('unmount');
    const id = connDeviceRef.current;
    if (id) queueMirrorOp(() => TB.recordDisconnect(id));
  }, []);

  // Map a pointer event to device coordinates (the frame is drawn at the device aspect, 1:1).
  const toDevice = (clientX, clientY) => {
    const el = imgWrapRef.current;
    if (!el || !conn) return null;
    const rect = el.getBoundingClientRect();
    if (rect.width <= 0 || rect.height <= 0) return null;
    const x = Math.round(((clientX - rect.left) / rect.width) * conn.deviceWidth);
    const y = Math.round(((clientY - rect.top) / rect.height) * conn.deviceHeight);
    return { x: Math.max(0, Math.min(conn.deviceWidth - 1, x)), y: Math.max(0, Math.min(conn.deviceHeight - 1, y)) };
  };

  // Direct action: resolve + dispatch + record in one go. Used by the key/nav buttons (explicit
  // actions the author already committed to).
  async function dispatch(gesture) {
    const id = connDeviceRef.current;
    if (!id || busy) return;
    setBusy(true);
    const r = await TB.recordGesture(id, gesture);
    setBusy(false);
    if (!r.ok) { setConnErr(r.error || 'The gesture could not be recorded.'); return; }
    setConnErr(null);
    appendStep({ verify: false, text: '', label: r.label || r.toolName || 'Step', yaml: r.yaml || '' });
    refreshTreeIfShown();
  }

  // Mirror gestures PROPOSE the next step: resolve the tool WITHOUT touching the device, then hold it
  // for the author to confirm with a human prompt. Confirming re-sends the gesture for real.
  async function propose(gesture) {
    const id = connDeviceRef.current;
    if (!id || busy) return;
    // When armed, the next tap APPENDS that tool to the open step (not re-target).
    if (pending && appendArm && gesture.type === 'tap') {
      const armAction = appendArm;
      setAppendArm(null);
      setBusy(true);
      const r = await TB.recordGesture(id, { ...gesture, action: armAction, resolveOnly: true });
      setBusy(false);
      if (!r.ok) { setConnErr(r.error || 'Could not resolve that element.'); return; }
      setConnErr(null);
      const best = (r.options || []).find((o) => o.isSelector);
      const nt = parseRecordStepTools(best ? best.yaml : (r.yaml || ''))[0];
      if (nt && window.jsyaml) {
        const arr = [...parseRecordStepTools(pending.yaml), nt].map((t) => ({ [t.name]: t.args == null ? {} : t.args }));
        const yaml = window.jsyaml.dump([{ tools: arr }], { lineWidth: -1 }).replace(/\n+$/, '');
        setPending((p) => (p ? { ...p, yaml } : p));
      }
      return;
    }
    if (appendArm) setAppendArm(null); // a non-tap gesture cancels the one-shot arm
    // If a proposal is already open, this tap RE-TARGETS it: re-resolve at the new spot.
    const prior = pending;
    const priorSeed = prior ? seedPrompt(prior.action, prior.element, prior.gesture) : null;
    setBusy(true);
    const r = await TB.recordGesture(id, { ...gesture, resolveOnly: true });
    setBusy(false);
    if (!r.ok) { setConnErr(r.error || 'Could not resolve that interaction.'); return; }
    setConnErr(null);
    const action = gesture.action || 'tap';
    // (Re)seed the description only when fresh or still showing the previous element's seed — never
    // clobber a prompt the author has hand-written.
    const newSeed = seedPrompt(action, r.element, gesture);
    setPendingPrompt((cur) => (!prior || !cur.trim() || cur === priorSeed ? newSeed : cur));
    setHoverEl(null);
    setRightTab('steps');
    // Prefer a stable selector by default, falling back to whatever the resolver returned (coordinate).
    const opts = r.options || [];
    const best = opts.find((o) => o.isSelector);
    const tool0Yaml = best ? best.yaml : (r.yaml || '');
    // Re-targeting a proposal that already chained extra tools: swap ONLY tool 0 (the gesture tool)
    // and keep the rest of the series, so retargeting never drops the author's added tools.
    let yaml = tool0Yaml;
    if (prior && window.jsyaml) {
      const priorTools = parseRecordStepTools(prior.yaml);
      const nt = parseRecordStepTools(tool0Yaml)[0];
      if (nt && priorTools.length > 1) {
        const arr = [nt, ...priorTools.slice(1)].map((t) => ({ [t.name]: t.args == null ? {} : t.args }));
        yaml = window.jsyaml.dump([{ tools: arr }], { lineWidth: -1 }).replace(/\n+$/, '');
      }
    }
    setPending({
      gesture,
      action,
      toolName: best ? best.toolName : r.toolName,
      yaml,
      label: r.label || r.toolName || 'Step',
      options: opts,
      element: r.element || null,
    });
  }
  const pickOption = (opt) => setPending((p) => (p ? { ...p, yaml: opt.yaml, toolName: opt.toolName } : p));
  const pickYaml = (yaml) => setPending((p) => (p ? { ...p, yaml } : p));
  async function confirmPending() {
    if (!pending || busy) return;
    setAppendArm(null);
    const id = connDeviceRef.current;
    if (!id) return;
    setBusy(true);
    // A step is a SERIES of tools — run them all in order via the per-step ▶ run path.
    const runnable = buildRunnableToolYaml(pending.label || 'step', pending.yaml);
    const r = runnable
      ? await TB.runToolQuick(runnable, id)
      : { success: false, error: 'This step has no runnable tools.' };
    setBusy(false);
    if (r.success !== true) { setConnErr(r.error || 'Could not run this step.'); return; }
    setConnErr(null);
    appendStep({ verify: pending.action === 'assertVisible', text: pendingPrompt.trim(), label: pending.label, yaml: pending.yaml });
    // An interaction changes the screen, so the cached element list is now stale; an assertion isn't.
    if (pending.action !== 'assertVisible') { setEls([]); setElsErr(null); setPending(null); setPendingPrompt(''); refreshTreeIfShown(); return; }
    setPending(null); setPendingPrompt('');
  }
  const cancelPending = () => { setPending(null); setPendingPrompt(''); setAppendArm(null); };

  // ─── Accessibility-tree inspector: list the elements on screen, pick one to act on ───
  async function loadTree() {
    const id = connDeviceRef.current;
    if (!id) return;
    setElsLoading(true); setElsErr(null);
    const r = await TB.recordTree(id);
    setElsLoading(false);
    if (!r.ok) { setElsErr(r.error || 'Could not read the screen.'); setEls([]); return; }
    setEls(r.elements || []);
  }
  // Load the element list when the author opens the Elements tab OR turns on the view-tree overlay.
  React.useEffect(() => {
    if ((rightTab === 'elements' || showTree) && conn && !elsLoading && els.length === 0 && !elsErr) loadTree();
  }, [rightTab, showTree, conn]);
  // Re-read the tree after anything that changes the device screen, when the overlay/list is showing.
  const refreshTreeIfShown = () => { if (connDeviceRef.current && (showTree || rightTab === 'elements')) loadTree(); };
  // Clicking an element in the inspector proposes the same step a mirror tap would (at its center).
  const proposeElement = (el) => {
    if (!conn || busy) return;
    propose({ type: 'tap', x: el.centerX, y: el.centerY, action: mode === 'check' ? 'assertVisible' : 'tap' });
  };

  const onPointerDown = (e) => {
    if (!conn || busy) return;
    const p = toDevice(e.clientX, e.clientY);
    if (!p) return;
    try { e.target.setPointerCapture(e.pointerId); } catch (_) {}
    downRef.current = { ...p, clientX: e.clientX, clientY: e.clientY, t: Date.now() };
  };
  const onPointerUp = (e) => {
    const down = downRef.current; downRef.current = null;
    if (!conn || busy || !down) return;
    const up = toDevice(e.clientX, e.clientY);
    if (!up) return;
    const dist = Math.hypot(up.x - down.x, up.y - down.y);
    const dt = Date.now() - down.t;
    const swipeThreshold = conn.deviceWidth * 0.06;
    if (dist > swipeThreshold) {
      propose({ type: 'swipe', startX: down.x, startY: down.y, endX: up.x, endY: up.y, durationMs: Math.max(120, Math.min(1200, dt)) });
    } else if (dt > 550) {
      propose({ type: 'longPress', x: down.x, y: down.y });
    } else {
      // A tap honors the current mode: Interact drives the app, Check records an assertion.
      propose({ type: 'tap', x: down.x, y: down.y, action: mode === 'check' ? 'assertVisible' : 'tap' });
    }
  };


  // ─── Step editing ───
  // Editing/reordering/removing a step invalidates the last test run, so clear its highlights.
  const clearTestStatus = () => setTestStatus((m) => (Object.keys(m).length ? {} : m));
  const updateStep = (id, patch) => { clearTestStatus(); setSteps((prev) => prev.map((s) => (s.id === id ? { ...s, ...patch } : s))); };
  const removeStep = (id) => { clearTestStatus(); setSteps((prev) => prev.filter((s) => s.id !== id)); };
  const moveStep = (id, dir) => { clearTestStatus(); setSteps((prev) => {
    const i = prev.findIndex((s) => s.id === id);
    const j = i + dir;
    if (i < 0 || j < 0 || j >= prev.length) return prev;
    const next = prev.slice();
    [next[i], next[j]] = [next[j], next[i]];
    return next;
  }); };

  // ─── Tool palette: pick a catalog tool to run on the device + record as a step ───
  const [paletteOpen, setPaletteOpen] = React.useState(false);
  // null = the picked tool becomes a NEW step; a step id = append the tool to that existing step.
  const [paletteTarget, setPaletteTarget] = React.useState(null);
  const [pq, setPq] = React.useState('');
  const openToolPalette = (target) => { setPaletteTarget(target == null ? null : target); setPq(''); setPaletteOpen(true); };
  const closeToolPalette = () => { setPaletteOpen(false); setPaletteTarget(null); setPq(''); };
  // Scope the catalog to the active target + connected platform, matching a real run on this device.
  const toolScope = React.useMemo(() => (target ? TB.scopeTrailmaps(target, platform) : null), [target, platform]);
  const paletteTools = React.useMemo(() => {
    let arr = tools.data || [];
    if (toolScope) arr = arr.filter((t) => !t.trailmap || toolScope.has(t.trailmap));
    const q = pq.trim().toLowerCase();
    if (q) {
      arr = arr.filter((t) => [t.id, t.trailmap, t.flavor, t.description, t.className].some((v) => v && String(v).toLowerCase().includes(q))
        || (t.parameters || []).some((p) => String(p.name).toLowerCase().includes(q)));
    }
    return arr.slice(0, 200);
  }, [tools.data, toolScope, pq]);
  const addToolFromPalette = (t) => {
    if (paletteTarget === 'pending') {
      // Append to the in-progress proposal's tool series (before it's confirmed).
      setPending((p) => (p ? { ...p, yaml: appendToolToStepYaml(p.yaml, t) } : p));
    } else if (paletteTarget != null) {
      // Append to the targeted committed step's tool list.
      setSteps((prev) => prev.map((s) => (s.id === paletteTarget ? { ...s, yaml: appendToolToStepYaml(s.yaml, t) } : s)));
    } else {
      appendStep({ verify: false, text: '', label: t.id, yaml: buildToolStepYaml(t) });
    }
    closeToolPalette();
  };
  React.useEffect(() => {
    if (!paletteOpen) return;
    const h = (e) => { if (e.key === 'Escape') closeToolPalette(); };
    window.addEventListener('keydown', h);
    return () => window.removeEventListener('keydown', h);
  }, [paletteOpen]);

  // A transport-level failure (the browser's fetch to the daemon dropped) vs. a real device error.
  // In a WKWebView a dropped fetch surfaces as "Load failed"; neither is a device result, so it's
  // safe to retry once rather than show the cryptic message.
  const isTransportError = (e) => /load failed|failed to fetch|networkerror|the network connection was lost|connection refused/i.test(e || '');
  async function runStep(step) {
    const id = connDeviceRef.current;
    if (!id) return { ok: false, text: 'Connect a device first.' };
    const runnable = buildRunnableToolYaml(step.label || 'step', step.yaml);
    if (!runnable) return { ok: false, text: 'This step has no runnable tool (check the YAML).' };
    let r = await TB.runToolQuick(runnable, id);
    if (r.success !== true && isTransportError(r.error)) {
      r = await TB.runToolQuick(runnable, id); // retry once — the daemon may have restarted
    }
    if (r.success === true) return { ok: true, text: r.result || 'OK', ms: r.durationMs || 0 };
    const text = isTransportError(r.error)
      ? 'Couldn’t reach the daemon to run this step — it may have restarted. Reconnect the device and try again.'
      : (r.error || 'Run failed');
    return { ok: false, text, ms: r.durationMs || 0 };
  }

  // Replay every recorded step on the device, top to bottom, lighting each card up as it runs.
  // Stops at the first failure; runs from the current screen. Prompt-only steps are skipped.
  async function testTrail() {
    if (testing) return;
    const id = connDeviceRef.current;
    if (!id) { setSaveErr('Connect a device first to test the trail.'); return; }
    const runnable = (s) => buildRunnableToolYaml(s.label || 'step', s.yaml);
    if (!steps.some(runnable)) { setSaveErr('No runnable steps to test yet — add a tool step first.'); return; }
    setSaveErr(null); setShowYaml(false); setTestStatus({}); setTesting(true); testAbortRef.current = false;
    for (let i = 0; i < steps.length; i++) {
      if (testAbortRef.current) break;
      const s = steps[i];
      if (!runnable(s)) continue;
      setTestStatus((m) => ({ ...m, [s.id]: 'running' }));
      let r;
      try { r = await runStep(s); } catch (e) { r = { ok: false, text: String(e) }; }
      setTestStatus((m) => ({ ...m, [s.id]: r.ok ? 'pass' : 'fail' }));
      if (!r.ok) { setSaveErr('Step ' + (i + 1) + ' failed: ' + r.text); break; }
    }
    setTesting(false);
  }
  const stopTest = () => { testAbortRef.current = true; };

  // Dispatch the pasted ad hoc YAML as a full run on this device (same recipe as the trail
  // run flow: connect, dispatch, then follow it live in Active).
  async function runAdhocYaml() {
    const id = tbId();
    if (!adhocYaml.trim() || !id || adhocRunning) return;
    setAdhocRunning(true); setAdhocErr(null);
    try {
      const connected = platform === 'web' ? true : await TB.connectDevice(id);
      if (!connected) { setAdhocErr('Could not connect to the selected device.'); return; }
      const resp = await TB.dispatchRun(id, adhocYaml);
      if (!resp || resp.ok === false || resp.success === false) {
        setAdhocErr((resp && resp.error) || 'Could not start the run.');
        return;
      }
      const parsed = parseTrailYaml(adhocYaml);
      const cfg = parsed.ok ? (parsed.config || {}) : {};
      TB.recordPendingRun({
        title: cfg.title || adhocName || 'Pasted YAML',
        target: cfg.target,
        device: (device && device.name) || selectedId,
        sessionId: resp.sessionId,
      });
      go('active', { followLive: Date.now() });
    } catch (e) {
      setAdhocErr((e && e.message) || 'Could not start the run.');
    } finally {
      setAdhocRunning(false);
    }
  }

  // The assembled <platform>.trail.yaml exactly as Save would write it (trailhead baked in as step 0).
  const previewYaml = React.useMemo(() => {
    if (!window.jsyaml) return '';
    const recorded = steps.filter((s) => (s.text || '').trim() || (s.yaml || '').trim());
    const leadStep = trailheadLeadStep(selectedTh, thDetail, thFilledArgs());
    const all = leadStep ? [leadStep, ...recorded] : recorded;
    if (!all.length) return '';
    return buildRecordedTrailYaml(target || 'recording', target, platform, all);
  }, [steps, selectedTh, thDetail, thArgs, target, platform]);

  async function save() {
    if (saving) return;
    const recorded = steps.filter((s) => (s.text || '').trim() || (s.yaml || '').trim());
    const blocker = trailSaveBlocker({ hasSteps: recorded.length > 0, availTrailheads, selectedTh, thMissingRequired });
    if (blocker) { setSaveErr(blocker); return; }
    setSaveErr(null);
    // Companion mode has no destination to ask for - the session's folder is the destination.
    if (companion) { doCompanionSave(); return; }
    // All checks passed: ask where in the library the trail should live.
    setSaveOpen(true);
  }

  // Companion save: the write goes through the daemon's companion route, which alone may announce
  // recording-saved to the agent. No blaze.yaml is written - the agent owns every non-recording
  // file in its folder; the UI contributes only the demonstrated <variant>.trail.yaml.
  async function doCompanionSave() {
    if (saving) return;
    setSaving(true); setSaveErr(null);
    const recorded = steps.filter((s) => (s.text || '').trim() || (s.yaml || '').trim());
    const leadStep = trailheadLeadStep(selectedTh, thDetail, thFilledArgs());
    const all = leadStep ? [leadStep, ...recorded] : recorded;
    const nm = (companion.folder || '').split('/').filter(Boolean).pop() || 'recording';
    const trailYaml = buildRecordedTrailYaml(nm, target, platform, all);
    // The variant fallback must match what the armed card and the footer PROMISED - 'recording',
    // never the live platform, or the two screens would name different files for the same save.
    const saved = await TB.companionSaveRecording(companion.runId, companion.variant || 'recording', trailYaml, platform || null);
    setSaving(false);
    if (!saved.ok) {
      const msg = saved.error || 'Could not save the recording.';
      if (/has ended|not found/i.test(msg)) {
        // The session is gone (disconnected, idle-reaped): release the latch so the demonstrated
        // steps aren't stranded - the normal library save takes over.
        latchCompanion(null);
        setSaveErr("Your agent's session has ended, so the recording could not be delivered to it. Save the trail into your library instead.");
      } else {
        setSaveErr(msg);
      }
      return;
    }
    // Delivered: the companion cycle is complete, so the latch and the demonstrated steps clear -
    // this mounted-but-hidden surface must not still be armed if reopened later. The adopted
    // marker clears too: a post-save re-arm of the same variant is a NEW cycle (fresh confirm
    // gate, clean slate), not a re-entry into this finished one.
    adoptedCompanionRef.current = null;
    latchCompanion(null);
    setSteps([]); setPending(null); setPendingPrompt(''); setTestStatus({});
    go('companion', { runId: companion.runId });
  }

  // The dialog's confirm: write the bundle folder straight into the trail library at `dest`
  // (blaze.yaml + the recorded <platform>.trail.yaml), then land on it in the Trails list.
  async function doSave(dest) {
    if (saving) return;
    setSaving(true); setSaveErr(null);
    const recorded = steps.filter((s) => (s.text || '').trim() || (s.yaml || '').trim());
    // Bake the chosen trailhead in as step 0 so the saved trail is reproducible from the known state.
    const leadStep = trailheadLeadStep(selectedTh, thDetail, thFilledArgs());
    const saved = await saveTrailToLibrary({
      dest, target, platform, objective: context.trim(),
      steps: leadStep ? [leadStep, ...recorded] : recorded,
    });
    setSaving(false);
    if (!saved.ok) { setSaveErr(saved.error); return; }
    setSaveOpen(false);
    go('trails', { sel: saved.id });
  }

  // ─── Guided focus: which stage the author should look at next ───
  // Context → Trailhead → Steps, advancing only as each is satisfied; the active stage gets the ring.
  const contextDone = context.trim() !== '' && context.trim() !== 'Validates that a user can';
  const trailheadNeeded = availTrailheads.length > 0;
  const trailheadDone = !trailheadNeeded || (!!selectedTh && thMissingRequired.length === 0);
  const activeStage = contextFocused || !contextDone
    ? 'context'
    : !trailheadDone
      ? 'trailhead'
      : 'steps';
  const STAGE_ORDER = { context: 0, trailhead: 1, steps: 2 };
  // The active stage is expanded; tapping a collapsed stage re-opens it (`editing`).
  const contextExpanded = activeStage === 'context';
  const trailheadExpanded = activeStage === 'trailhead' || editing === 'trailhead';
  React.useEffect(() => { setEditing(null); }, [activeStage]);
  // Stage container style: dim the not-yet-reached stages; accent ring on the active one.
  const stageStyle = (stage, accent = 'var(--tb-ai)') => {
    const isActive = activeStage === stage;
    const isFuture = STAGE_ORDER[stage] > STAGE_ORDER[activeStage];
    return {
      opacity: isFuture ? 0.4 : 1,
      transition: 'opacity .25s var(--ease-out-soft), box-shadow .2s var(--ease-out-soft)',
      borderRadius: 12,
      // A crisp 2px ring only — no blur glow, which the scroll container would clip.
      ...(isActive ? { boxShadow: `0 0 0 2px ${accent}` } : {}),
    };
  };
  // On connect, focus Context; on each real stage change, scroll the active stage into view.
  React.useEffect(() => {
    if (!conn) { prevStageRef.current = null; return; }
    const changed = prevStageRef.current !== activeStage;
    prevStageRef.current = activeStage;
    if (!changed) return;
    const wrap = activeStage === 'context' ? contextCardRef.current
      : activeStage === 'trailhead' ? trailheadWrapRef.current
        : stepsWrapRef.current;
    if (wrap) wrap.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
    // Only steal focus for Context — later stages just highlight.
    if (activeStage === 'context' && contextRef.current) contextRef.current.focus();
  }, [conn, activeStage]);

  // ─── Render ───
  const noDevices = deviceList.length === 0;
  // Device aspect (height/width): the real device once connected; otherwise the selected platform's
  // default (portrait phone, landscape web) so picking a device animates the frame toward its shape.
  const platRatio = (p) => (p === 'web' ? 0.62 : 2.16);
  const ratio = conn && conn.deviceWidth > 0 ? conn.deviceHeight / conn.deviceWidth : (device ? platRatio(device.platform) : 2);
  const availH = typeof window !== 'undefined' ? window.innerHeight : 900;
  // Fit the frame inside the (resizable) left column width AND the available height, at the device
  // aspect — so it never overflows or forces a scrollbar.
  const fitH = rootH > 0 ? rootH - 200 : availH - 200;
  const maxFrameW = Math.max(220, leftW - 14);
  let fittedW = maxFrameW, fittedH = fittedW * ratio;
  if (fittedH > fitH) { fittedH = fitH; fittedW = fittedH / ratio; }
  const frameH = Math.max(260, Math.round(fittedH));
  const frameW = Math.round(fittedW);
  const frameAnim = dragging ? 'none' : 'width .24s ease, height .24s ease';
  const platLabel = platform === 'ios' ? 'iOS' : platform === 'android' ? 'Android' : platform === 'web' ? 'Web' : (platform || '');
  // Map device-coordinate bounds → a percentage box over the mirror (objectFit:fill, so 1:1).
  const boxPct = (b) => {
    const dw = (conn && conn.deviceWidth) || 1, dh = (conn && conn.deviceHeight) || 1;
    return { left: (b.left / dw * 100) + '%', top: (b.top / dh * 100) + '%', width: ((b.right - b.left) / dw * 100) + '%', height: ((b.bottom - b.top) / dh * 100) + '%' };
  };
  const elList = React.useMemo(() => {
    const q = elQuery.trim().toLowerCase();
    if (!q) return els;
    return els.filter((e) => [e.label, e.type].some((v) => v && String(v).toLowerCase().includes(q)));
  }, [els, elQuery]);

  // Ad hoc YAML runner card + its Run footer. Shared by the connected right panel's "Run YAML"
  // tab and the disconnected view (the command palette can open this before a Record session
  // exists — dispatching a run doesn't need the mirror, it connects the device itself).
  const runYamlCard = (
    <div className="tb-card" style={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
      <div style={{ flex: '0 0 auto', display: 'flex', alignItems: 'center', gap: 8, padding: '9px 12px', borderBottom: '1px solid var(--tb-hairline)' }}>
        <Ico n="braces" s={14} c="var(--text-subtle)" />
        <span style={{ fontSize: 12.5, fontWeight: 700, color: 'var(--text-standard)' }}>{adhocName || 'Trail YAML'}</span>
        <span className="tb-sub" style={{ fontSize: 11 }}>runs on {(device && device.name) || 'the selected device'}</span>
      </div>
      <textarea value={adhocYaml} onChange={(e) => { setAdhocYaml(e.target.value); setAdhocErr(null); }} spellCheck={false}
        placeholder={'- config:\n    title: "Ad hoc run"\n- prompts:\n  - step: "Open the app"'}
        style={{ flex: 1, minHeight: 0, width: '100%', boxSizing: 'border-box', display: 'block', resize: 'none', border: 'none', outline: 'none', background: '#0a0a0a', color: '#d7dee8', padding: '12px 14px', fontFamily: 'var(--font-mono)', fontSize: 12, lineHeight: 1.55, tabSize: 2 }} />
    </div>
  );
  const runYamlFooter = (
    <div style={{ borderTop: '1px solid var(--tb-hairline)', paddingTop: 12, marginTop: 12, display: 'flex', gap: 10, alignItems: 'center' }}>
      <span className="tb-sub" style={{ fontSize: 11.5, flex: 1, minWidth: 0 }}>{adhocErr
        ? <span role="alert" style={{ color: 'var(--tb-danger-text)' }}>{adhocErr}</span>
        : 'Runs the pasted YAML as a full run — follow it in Active.'}</span>
      <Btn kind="primary" ico={adhocRunning ? 'loader-2' : 'play'} spin={adhocRunning} onClick={runAdhocYaml} disabled={!adhocYaml.trim() || adhocRunning || !device}>
        {adhocRunning ? 'Starting…' : 'Run YAML'}
      </Btn>
    </div>
  );

  return (
    <div ref={rootRef} style={{ display: 'flex', flexDirection: 'column', height: '100%', overflow: 'hidden' }}>
      {/* Header band — title on the left, connection cluster on the right. */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 16, padding: '18px 32px', borderBottom: '1px solid var(--tb-hairline)' }}>
        <Ico n="pointer" s={19} c="var(--tb-ai)" style={{ flex: '0 0 auto' }} />
        <div style={{ minWidth: 0, flex: '0 0 auto' }}>
          <div style={{ fontSize: 16, fontWeight: 650, fontFamily: 'var(--font-display)', color: 'var(--text-standard)', lineHeight: 1.2 }}>Compose a trail</div>
          <div className="tb-sub" style={{ fontSize: 12, marginTop: 1 }}>Drive a real device. Every action becomes an editable step.</div>
        </div>
        <span style={{ flex: 1 }} />
        <AppIcon target={target} size={24} radius={6} fallbackColor="var(--text-subtle-variant)" />
        <span style={{ fontSize: 13, fontWeight: 600, color: 'var(--text-standard)', whiteSpace: 'nowrap', maxWidth: 160, overflow: 'hidden', textOverflow: 'ellipsis' }}>{(gt && (gt.label || gt.target)) || 'No target'}</span>
        {connecting && (
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: 8, fontSize: 13, color: 'var(--text-subtle)' }}>
            <Ico n="loader-2" s={15} spin /> Connecting…
          </span>
        )}
      </div>
      {connErr && <div role="alert" style={{ padding: '12px 32px 0', color: 'var(--tb-danger-text)', fontSize: 12.5, lineHeight: 1.5 }}>{connErr}</div>}

      {!conn ? (
        /* Disconnected: the device frame holds the chooser — pick a device, then Connect. */
        <div style={{ flex: 1, display: 'flex', gap: 14, minHeight: 0, padding: '16px 32px 24px' }}>
          {/* Left — device frame with the chooser inside. overflow:visible so the frame's soft drop
              shadow isn't clipped into a hard-edged band (the frame nearly fills this column's width). */}
          <div style={{ width: leftW, flex: '0 0 auto', display: 'flex', flexDirection: 'column', minHeight: 0, overflow: 'visible' }}>
            <div style={{ width: frameW + 14, margin: '0 auto', boxSizing: 'border-box', borderRadius: 24, background: '#0b0b0c', border: '1px solid var(--tb-hairline)', padding: 7, boxShadow: '0 12px 40px rgba(0,0,0,.45)', transition: frameAnim }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '4px 10px 9px' }}>
                <PlatformGlyph platform={device ? device.platform : null} s={14} c="var(--text-subtle)" />
                <span style={{ fontSize: 11.5, fontWeight: 600, color: 'var(--text-subtle-variant)', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', flex: 1, minWidth: 0 }}>{device ? device.name + (platLabel ? ' · ' + platLabel : '') : 'Choose a device'}</span>
              </div>
              {/* The chooser fits its rows, capped at the device aspect height so a long list scrolls. */}
              <div style={{ width: '100%', height: connecting ? frameH : undefined, maxHeight: frameH, borderRadius: 18, background: '#000', boxSizing: 'border-box', overflow: 'hidden', display: 'flex', flexDirection: 'column', transition: frameAnim }}>
                {noDevices ? (
                  <div style={{ flex: 1, display: 'grid', placeItems: 'center', textAlign: 'center', padding: 20 }}>
                    <div>
                      <Ico n="triangle-alert" s={22} c="var(--tb-amber)" />
                      <div style={{ marginTop: 10, fontSize: 13, color: 'var(--tb-amber)' }}>No device connected</div>
                      <div className="tb-sub" style={{ fontSize: 11.5, lineHeight: 1.5, maxWidth: 220, margin: '6px auto 0' }}><a onClick={() => go('home')} style={{ color: 'var(--tb-primary-green)', cursor: 'pointer', textDecoration: 'underline' }}>Pick a target &amp; device on Home</a>.</div>
                    </div>
                  </div>
                ) : (
                  <>
                    <div style={{ padding: '12px 12px 8px', display: 'flex', alignItems: 'center', gap: 9, flex: '0 0 auto' }}>
                      <span style={{ fontSize: 12.5, fontWeight: 700, color: 'var(--text-standard)' }}>{connecting ? 'Connecting…' : 'Choose a device'}</span>
                      {deviceList.length > 0 && <Chip>{deviceList.length}</Chip>}
                    </div>
                    <div style={{ flex: 1, minHeight: 0, overflowY: 'auto', padding: '0 12px', display: 'flex', flexDirection: 'column', gap: 8 }}>
                      {deviceList.map((d) => {
                        const sel = d.id === selectedId;
                        // Lock device switching while a connect is in flight.
                        return (
                          <div key={d.id} role="button" tabIndex={connecting ? -1 : 0} onClick={connecting ? undefined : () => setSelectedId(d.id)}
                            onKeyDown={connecting ? undefined : (e) => { if (e.key === 'Enter') setSelectedId(d.id); }}
                            style={{ display: 'flex', alignItems: 'center', gap: 11, padding: '11px 13px', borderRadius: 10, cursor: connecting ? 'default' : 'pointer', opacity: connecting && !sel ? 0.45 : 1,
                              border: '1px solid ' + (sel ? 'var(--tb-primary-green)' : 'rgba(255,255,255,.08)'), background: sel ? 'rgba(0,224,19,.08)' : 'rgba(255,255,255,.03)' }}>
                            <PlatformGlyph platform={d.platform} s={18} c={sel ? 'var(--tb-primary-green)' : 'var(--text-subtle)'} />
                            <div style={{ flex: 1, minWidth: 0 }}>
                              <div style={{ fontSize: 13.5, fontWeight: 600, color: 'var(--text-standard)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{d.name}</div>
                              <div className="tb-sub" style={{ fontSize: 11.5 }}>{d.platform}{d.driver && d.driver !== '?' ? ' · ' + d.driver : ''}</div>
                            </div>
                            {sel && <Ico n={connecting ? 'loader-2' : 'check-circle-2'} s={16} c="var(--tb-primary-green)" spin={connecting} />}
                          </div>
                        );
                      })}
                    </div>
                    <div style={{ padding: 12, display: 'flex', gap: 8, alignItems: 'center', flex: '0 0 auto', borderTop: '1px solid rgba(255,255,255,.06)' }}>
                      <Btn kind="primary" ico={connecting ? 'loader-2' : 'plug'} spin={connecting} onClick={() => connect()} disabled={!selectedId || connecting}>
                        {connecting ? 'Connecting…' : 'Connect device'}
                      </Btn>
                      {connecting && <Btn kind="ghost" ico="x" onClick={cancelConnect}>Cancel</Btn>}
                    </div>
                  </>
                )}
              </div>
            </div>
          </div>
          <Splitter onDown={startDrag} />
          {rightTab === 'runyaml' ? (
            /* The palette's "Run ad hoc YAML…" lands here when no Record session exists yet — the
               runner doesn't need the mirror (dispatching connects the device itself), so show it
               against the device selected in the chooser instead of hiding it behind Connect. */
            <div style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column', minHeight: 0, padding: 6 }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 10 }}>
                <span style={{ fontSize: 12.5, fontWeight: 700 }}>Run YAML</span>
                <span style={{ flex: 1 }} />
                <Btn sm kind="ghost" ico="trash-2" onClick={() => { setAdhocYaml(''); setAdhocName(''); setAdhocErr(null); }} disabled={!adhocYaml.trim()}>Clear</Btn>
                <Btn sm kind="ghost" ico="x" onClick={() => setRightTab('steps')}>Close</Btn>
              </div>
              {runYamlCard}
              {runYamlFooter}
            </div>
          ) : (
          /* Right — Context is editable now, no wait. Padding gives the active-stage ring room. */
          <div style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column', minHeight: 0, overflowY: 'auto', gap: 12, padding: 6 }}>
            <ContextCard value={context} onChange={(e) => setContext(e.target.value)} expanded={contextExpanded}
              onFocus={() => setContextFocused(true)} onBlur={() => setContextFocused(false)}
              inputRef={contextRef} cardRef={contextCardRef} ringStyle={stageStyle('context')} />
            <div className="tb-sub" style={{ fontSize: 12, lineHeight: 1.6, display: 'inline-flex', alignItems: 'flex-start', gap: 8, padding: '0 2px' }}>
              <Ico n="list-checks" s={15} c="var(--text-subtle)" style={{ flex: '0 0 auto', marginTop: 1 }} />
              <span>{connecting ? 'Connecting to the device… meanwhile, write what this trail validates above.' : 'Pick a device and connect to start recording steps. You can write the context now.'}</span>
            </div>
          </div>
          )}
        </div>
      ) : (
        /* Connected: left = device stage + drive controls; right = the recorded trail. */
        <div style={{ flex: 1, display: 'flex', gap: 14, minHeight: 0, padding: '16px 32px 24px' }}>
          {/* Left — the device stage, framed like a simulator. overflow:visible so the frame's soft
              drop shadow isn't clipped into a hard-edged band (the frame nearly fills this column). */}
          <div style={{ width: leftW, flex: '0 0 auto', display: 'flex', flexDirection: 'column', minHeight: 0, overflow: 'visible' }}>
            <div style={{ width: frameW + 14, margin: '0 auto', boxSizing: 'border-box', borderRadius: 24, background: '#0b0b0c', border: '1px solid var(--tb-hairline)', padding: 7, boxShadow: '0 12px 40px rgba(0,0,0,.45)', transition: frameAnim }}>
              {/* Device toolbar: device picker + hardware-button equivalents + disconnect. */}
              <div style={{ display: 'flex', alignItems: 'center', gap: 7, padding: '4px 6px 9px' }}>
                <Select value={selectedId || ''} onChange={(e) => switchDevice(e.target.value)}
                  title="Switch device" style={{ flex: 1, minWidth: 0, maxWidth: 220 }}
                  options={deviceList.map((d) => ({ value: d.id, label: d.name + ' · ' + (d.platform === 'ios' ? 'iOS' : d.platform === 'android' ? 'Android' : d.platform) }))} />
                {platform === 'android' && (
                  <button onClick={() => dispatch({ type: 'pressKey', key: 'Back' })} disabled={busy} title="Back" style={navBtn(busy)}>
                    <Ico n="arrow-left" s={14} c="var(--text-subtle-variant)" />
                  </button>
                )}
                {(platform === 'ios' || platform === 'android') && (
                  <button onClick={() => dispatch({ type: 'pressKey', key: 'Home' })} disabled={busy} title="Home" style={navBtn(busy)}>
                    <Ico n="house" s={13} c="var(--text-subtle-variant)" />
                  </button>
                )}
                <button onClick={disconnect} title="Disconnect" style={navBtn(false)}>
                  <Ico n="unplug" s={13} c="var(--text-subtle)" />
                </button>
              </div>
              <div ref={imgWrapRef}
                onPointerDown={onPointerDown} onPointerUp={onPointerUp}
                style={{ width: '100%', height: frameH, borderRadius: 18, background: '#000',
                  position: 'relative', overflow: 'hidden', touchAction: 'none', transition: frameAnim,
                  cursor: busy ? 'progress' : 'crosshair', userSelect: 'none' }}>
                {/* Both surfaces are always mounted while connected so the live-stream transport can
                    write into them and toggle their display (canvas for H.264, img for JPEG/poll). */}
                <img ref={mirrorImgRef} alt="device" draggable={false} style={{ position: 'absolute', inset: 0, width: '100%', height: '100%', objectFit: 'fill', display: 'none', pointerEvents: 'none' }} />
                <canvas ref={mirrorCanvasRef} aria-label="device" style={{ position: 'absolute', inset: 0, width: '100%', height: '100%', objectFit: 'fill', display: 'none', pointerEvents: 'none' }} />
                {!frame && <div style={{ position: 'absolute', inset: 0, display: 'grid', placeItems: 'center', color: 'var(--text-subtle)', fontSize: 13 }}><Ico n="loader-2" s={20} spin /> </div>}
                {busy && <div style={{ position: 'absolute', top: 10, right: 10, background: 'rgba(0,0,0,.62)', color: '#fff', fontSize: 11, padding: '4px 8px', borderRadius: 8, display: 'flex', gap: 6, alignItems: 'center' }}><Ico n="loader-2" s={12} spin /> capturing</div>}
                {/* Armed to append a tap/verify tool to the open step on the next tap. */}
                {appendArm && !busy && (
                  <div style={{ position: 'absolute', top: 10, left: '50%', transform: 'translateX(-50%)', display: 'flex', alignItems: 'center', gap: 8, background: 'rgba(0,0,0,.72)', border: '1px solid ' + (appendArm === 'assertVisible' ? 'var(--tb-link)' : '#c4a8ff'), color: '#fff', fontSize: 11.5, fontWeight: 600, padding: '5px 10px', borderRadius: 999, whiteSpace: 'nowrap' }}>
                    <Ico n={appendArm === 'assertVisible' ? 'circle-check' : 'pointer'} s={13} c={appendArm === 'assertVisible' ? 'var(--tb-link)' : '#c4a8ff'} />
                    Tap an element to add a {appendArm === 'assertVisible' ? 'check' : 'tap'} to this step
                    <span role="button" tabIndex={0} onClick={(e) => { e.stopPropagation(); setAppendArm(null); }} onPointerDown={(e) => e.stopPropagation()} onPointerUp={(e) => e.stopPropagation()} title="Cancel" style={{ cursor: 'pointer', display: 'inline-flex', color: 'var(--text-subtle)' }}><Ico n="x" s={13} /></span>
                  </div>
                )}
                {/* View-tree toggle — labeled boxes to see the structure and aim taps. */}
                {frame && (
                  <button onClick={(e) => { e.stopPropagation(); setShowTree((v) => !v); }}
                    onPointerDown={(e) => e.stopPropagation()} onPointerUp={(e) => e.stopPropagation()}
                    title="Show the view tree (labeled boxes) on the device"
                    style={{ position: 'absolute', top: 10, left: 10, zIndex: 4, display: 'inline-flex', alignItems: 'center', gap: 6, padding: '4px 9px', borderRadius: 8, cursor: 'pointer',
                      border: '1px solid ' + (showTree ? 'var(--tb-primary-green)' : 'rgba(255,255,255,.18)'),
                      background: showTree ? 'rgba(0,224,19,.16)' : 'rgba(0,0,0,.55)', color: showTree ? 'var(--tb-primary-green)' : '#cbd5e1', fontSize: 11, fontWeight: 600 }}>
                    <Ico n="list-tree" s={13} c={showTree ? 'var(--tb-primary-green)' : '#cbd5e1'} /> View tree
                  </button>
                )}
                {/* Element boxes + labels: interactive ring green, display-only ring blue. */}
                {(showTree || rightTab === 'elements') && elList.map((el, i) => {
                  const lbl = (el.resourceId || '').trim() || (el.label || '').trim() || (el.type || '').trim();
                  const tint = el.interactive ? '0,224,19' : '94,155,255';
                  const w = el.right - el.left, h = el.bottom - el.top;
                  const details = [
                    el.label ? '“' + el.label + '”' : null,
                    el.type || null,
                    el.resourceId ? 'id: ' + el.resourceId : null,
                    (el.interactive ? 'interactive' : 'display only') + ' · ' + w + '×' + h,
                  ].filter(Boolean).join('\n');
                  return (
                    <div key={'o' + i} style={{ ...boxPct(el), position: 'absolute', border: `1px solid rgba(${tint},.45)`, borderRadius: 2, pointerEvents: 'none' }}>
                      {lbl && (
                        <span role="button" tabIndex={0} title={details} aria-label={'Select ' + lbl}
                          onClick={(e) => { e.stopPropagation(); if (!busy) proposeElement(el); }}
                          onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.stopPropagation(); e.preventDefault(); if (!busy) proposeElement(el); } }}
                          onPointerDown={(e) => e.stopPropagation()} onPointerUp={(e) => e.stopPropagation()}
                          style={{ position: 'absolute', top: 0, left: 0, maxWidth: '100%', boxSizing: 'border-box', fontSize: 9, lineHeight: 1.25, padding: '0 3px', borderRadius: '0 0 3px 0', background: 'rgba(0,0,0,.72)', color: el.interactive ? '#7CFFA0' : '#bcd6ff', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', cursor: 'pointer', pointerEvents: 'auto' }}>{lbl}</span>
                      )}
                    </div>
                  );
                })}
                {hoverEl && <div style={{ ...boxPct(hoverEl), position: 'absolute', border: '2px solid var(--tb-link)', background: 'rgba(94,155,255,.14)', borderRadius: 3, pointerEvents: 'none' }} />}
                {pending && pending.element && <div style={{ ...boxPct(pending.element), position: 'absolute', border: '2px solid ' + (pending.action === 'assertVisible' ? 'var(--tb-link)' : '#c4a8ff'), background: pending.action === 'assertVisible' ? 'rgba(94,155,255,.14)' : 'rgba(168,130,255,.14)', borderRadius: 3, pointerEvents: 'none' }} />}
              </div>
            </div>

            <div className="tb-sub" style={{ fontSize: 11, marginTop: 10, lineHeight: 1.5 }}>{pending ? 'Describe and confirm the proposed step on the right to run it.' : (mode === 'check' ? 'Nothing runs on the device in Check mode. You only record what should be visible.' : 'You confirm each step before it runs on the device.')}</div>
          </div>

          <Splitter onDown={startDrag} />
          {/* Right - the recorded trail. minWidth floor: a persisted wide stage width
              (tb-interact-leftw, up to 860px) on a now-narrow window would otherwise
              collapse this pane and its full-width textareas to a sliver. */}
          <div style={{ flex: 1, minWidth: 360, display: 'flex', flexDirection: 'column', minHeight: 0 }}>
            {/* Tabs: the recorded trail, the on-screen element inspector (the accessibility tree),
                or the ad hoc YAML runner. */}
            <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 10 }}>
              <div style={{ display: 'inline-flex', border: '1px solid var(--tb-hairline)', borderRadius: 9, overflow: 'hidden' }}>
                {[['steps', 'Steps', steps.length], ['elements', 'Elements', els.length], ['runyaml', 'Run YAML', null]].map(([val, lbl, n]) => {
                  const on = rightTab === val;
                  const showN = val === 'steps' || (val === 'elements' && els.length > 0);
                  return (
                    <button key={val} onClick={() => setRightTab(val)}
                      style={{ border: 'none', cursor: 'pointer', padding: '6px 13px', fontSize: 12.5, fontWeight: 650, display: 'inline-flex', alignItems: 'center', gap: 7,
                        background: on ? 'var(--bg-standard)' : 'transparent', color: on ? 'var(--text-standard)' : 'var(--text-subtle)' }}>
                      {lbl}{showN ? <span style={{ fontSize: 11, fontWeight: 700, color: on ? 'var(--text-subtle-variant)' : 'var(--text-subtle)' }}>{n}</span> : null}
                    </button>
                  );
                })}
              </div>
              <span style={{ flex: 1 }} />
              {rightTab === 'steps' && (
                <Btn sm kind={showYaml ? 'primary' : 'ghost'} ico="braces" onClick={() => setShowYaml((v) => !v)} title="Toggle the raw trail YAML view">YAML</Btn>
              )}
              {rightTab === 'elements' && <Btn sm kind="ghost" ico={elsLoading ? 'loader-2' : 'refresh-cw'} spin={elsLoading} onClick={loadTree} disabled={elsLoading}>Refresh</Btn>}
              {rightTab === 'runyaml' && <Btn sm kind="ghost" ico="trash-2" onClick={() => { setAdhocYaml(''); setAdhocName(''); setAdhocErr(null); }} disabled={!adhocYaml.trim()}>Clear</Btn>}
            </div>

            {rightTab === 'steps' ? (
              showYaml ? (
                /* Raw YAML view — the assembled trail file Save would write, read-only. */
                <div className="tb-card" style={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
                  <div style={{ flex: '0 0 auto', display: 'flex', alignItems: 'center', gap: 8, padding: '9px 12px', borderBottom: '1px solid var(--tb-hairline)' }}>
                    <Ico n="file-code" s={14} c="var(--text-subtle)" />
                    <span className="tb-mono" style={{ fontSize: 12, fontWeight: 700, color: 'var(--text-standard)' }}>{(platform || 'recording')}.trail.yaml</span>
                    <span className="tb-sub" style={{ fontSize: 11 }}>read-only preview</span>
                    <span style={{ flex: 1 }} />
                    <Btn sm kind="ghost" ico="copy" onClick={() => { if (previewYaml && navigator.clipboard) navigator.clipboard.writeText(previewYaml); }} disabled={!previewYaml}>Copy</Btn>
                  </div>
                  <pre style={{ flex: 1, minHeight: 0, overflow: 'auto', margin: 0, padding: '12px 14px', fontFamily: 'var(--font-mono)', fontSize: 12, lineHeight: 1.55, color: '#cbd5e1', whiteSpace: 'pre', tabSize: 2 }}>{previewYaml ? ((context.trim() ? '# objective: ' + context.trim() + '\n\n' : '') + previewYaml) : '# Add a step to see the trail YAML.'}</pre>
                </div>
              ) : (
              <div ref={stepsScrollRef} style={{ flex: 1, overflowY: 'auto', minHeight: 0, display: 'flex', flexDirection: 'column', gap: 10, padding: 6 }}>
                {/* Context — stage 1 of the guided flow. Feeds blaze.yaml's objective. */}
                <ContextCard value={context} onChange={(e) => setContext(e.target.value)} expanded={contextExpanded}
                  onFocus={() => setContextFocused(true)} onBlur={() => setContextFocused(false)}
                  inputRef={contextRef} cardRef={contextCardRef} ringStyle={stageStyle('context')} />
                {/* Step 0 — the required trailhead the trail starts from. Stage 2 of the guided flow. */}
                <div ref={trailheadWrapRef} style={{ flexShrink: 0, ...stageStyle('trailhead') }}>
                  <TrailheadStep
                    target={target} platform={platform} trailheads={availTrailheads} metaByName={thMetaByName}
                    selected={selectedTh} onSelect={setTrailheadId} detail={thDetail} loading={thLoading}
                    params={thParams} args={thArgs} onArg={(name, val) => setThArgs((a) => ({ ...a, [name]: val }))}
                    missingRequired={thMissingRequired}
                    accountParam={accountParam} accountValue={accountValue} onAccount={setAccount}
                    canRun={!!conn} onGoToTrailhead={goToTrailhead} run={thRun} go={go}
                    expanded={trailheadExpanded} onExpand={() => setEditing('trailhead')} />
                </div>
                {/* Committed steps in order, top to bottom; the pending proposal is appended at the end. */}
                {steps.map((s, i) => (
                  <StepCard key={s.id} step={s} index={i} count={steps.length} canRun={!!conn} toolMap={toolMap} go={go} runStatus={testStatus[s.id]}
                    onChange={(patch) => updateStep(s.id, patch)} onRemove={() => removeStep(s.id)} onMove={(dir) => moveStep(s.id, dir)} onRun={() => runStep(s)} onAddTool={() => openToolPalette(s.id)} />
                ))}
                {/* Stage 3 of the guided flow — the steps. Dimmed until the trailhead is set; the
                    "Add the next step" affordance gets the accent ring once it's the focus. */}
                <div ref={stepsWrapRef} style={{ flexShrink: 0, opacity: STAGE_ORDER.steps > STAGE_ORDER[activeStage] ? 0.4 : 1, transition: 'opacity .25s var(--ease-out-soft)' }}>
                {pending ? (
                  <ProposalCard pending={pending} num={steps.length + 1} prompt={pendingPrompt} setPrompt={setPendingPrompt} busy={busy} toolMap={toolMap} catalog={tools.data || []} scope={toolScope} platform={platform} go={go}
                    onConfirm={confirmPending} onCancel={cancelPending} onPickOption={pickOption} onPickYaml={pickYaml} onAddCustom={() => openToolPalette('pending')}
                    armed={appendArm} onArmAppend={setAppendArm} />
                ) : (
                  /* Dashed "next step" slot — Tap / Verify set the mode; Custom opens the palette. */
                  <div style={{ flexShrink: 0, border: '1.5px dashed var(--tb-hairline)', borderRadius: 12, padding: 13, display: 'flex', flexDirection: 'column', gap: 11, background: 'rgba(255,255,255,.012)', ...(activeStage === 'steps' ? { boxShadow: '0 0 0 2px var(--tb-ai)' } : {}), transition: 'box-shadow .2s var(--ease-out-soft)' }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                      <span style={{ width: 20, height: 20, borderRadius: 6, border: '1.5px dashed var(--text-subtle)', display: 'grid', placeItems: 'center', color: 'var(--text-subtle)', fontSize: 11, fontWeight: 700, flex: '0 0 auto' }}>{steps.length + 1}</span>
                      <span style={{ fontSize: 12.5, fontWeight: 700, color: 'var(--text-standard)' }}>Add the next step</span>
                    </div>
                    <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                      <button onClick={() => { setMode('interact'); setHoverEl(null); }} disabled={busy} style={nextStepBtn(mode === 'interact', '#c4a8ff')} title="Then tap the screen to propose a tap">
                        <Ico n="pointer" s={16} c={mode === 'interact' ? '#c4a8ff' : 'var(--text-subtle)'} style={{ flex: '0 0 auto' }} />
                        <div style={{ minWidth: 0 }}><div style={{ fontSize: 12.5, fontWeight: 700 }}>Tap</div><div className="tb-sub" style={{ fontSize: 10.5 }}>tap the screen</div></div>
                      </button>
                      <button onClick={() => { setMode('check'); setHoverEl(null); }} disabled={busy} style={nextStepBtn(mode === 'check', 'var(--tb-link)')} title="Then tap the element you want to assert is visible">
                        <Ico n="circle-check" s={16} c={mode === 'check' ? 'var(--tb-link)' : 'var(--text-subtle)'} style={{ flex: '0 0 auto' }} />
                        <div style={{ minWidth: 0 }}><div style={{ fontSize: 12.5, fontWeight: 700 }}>Verify</div><div className="tb-sub" style={{ fontSize: 10.5 }}>tap what to check</div></div>
                      </button>
                      <button onClick={() => openToolPalette(null)} disabled={busy} style={nextStepBtn(false, 'var(--tb-running)')} title="Pick any tool from the catalog">
                        <Ico n="wrench" s={15} c="var(--text-subtle)" style={{ flex: '0 0 auto' }} />
                        <div style={{ minWidth: 0 }}><div style={{ fontSize: 12.5, fontWeight: 700 }}>Custom tool</div><div className="tb-sub" style={{ fontSize: 10.5 }}>pick from catalog</div></div>
                      </button>
                    </div>
                    <div className="tb-sub" style={{ fontSize: 11, lineHeight: 1.5 }}>
                      {mode === 'check'
                        ? <>Tap an element on the device to record what should be visible. Or browse <strong style={{ color: 'var(--text-subtle-variant)', fontWeight: 600 }}>Elements</strong>.</>
                        : <>Tap, swipe, or long-press the device to propose a step — you describe and confirm it before it runs. Or browse <strong style={{ color: 'var(--text-subtle-variant)', fontWeight: 600 }}>Elements</strong>.</>}
                    </div>
                  </div>
                )}
                </div>
              </div>
              )
            ) : rightTab === 'elements' ? (
              <ElementsPanel els={elList} total={els.length} loading={elsLoading} err={elsErr} query={elQuery} setQuery={setElQuery}
                mode={mode} disabled={busy} onHover={setHoverEl} onPick={proposeElement} />
            ) : (
              /* Ad hoc YAML runner — paste trail YAML and dispatch it as a full run on this device. */
              runYamlCard
            )}

            {rightTab === 'runyaml' ? runYamlFooter : (
            <div style={{ borderTop: '1px solid var(--tb-hairline)', paddingTop: 12, marginTop: 12, display: 'flex', gap: 10, alignItems: 'center' }}>
              {/* The companion destination shows even with zero steps: the latch silently redirecting
                  a save is exactly the surprise this line (and its escape hatch) exists to prevent. */}
              <span className="tb-sub" style={{ fontSize: 11.5, flex: 1, minWidth: 0 }}>{companion ? (testing ? 'Running every step on the device…' : <>Save writes <span className="tb-mono">{TB.variantSlug(companion.variant || 'recording')}.trail.yaml</span> into <span className="tb-mono">{companion.folder}</span> for your agent.</>) : (steps.length === 0 ? 'Record or add a step to save this trail.' : (testing ? 'Running every step on the device…' : 'Test replays the steps on the device. Save writes the trail into your library.'))}</span>
              {saveErr && <span role="alert" style={{ color: 'var(--tb-danger-text)', fontSize: 12 }}>{saveErr}</span>}
              {companion && (
                /* The abandoned-cycle exit: drops the agent's save destination (steps stay) so a
                   later unrelated recording can't silently land in the agent's folder. Announced
                   to the agent - otherwise it tails for recording-saved forever. */
                <Btn sm ico="x" title="Stop recording for your agent - Save goes back to your library"
                  onClick={() => {
                    TB.companionUserAction(companion.runId, 'user-action', { actionId: 'recording-cancelled' });
                    // Cancel frees the device dedupe: the agent will likely re-ask, and a re-arm
                    // of the SAME variant is same-cycle (no reset), so the adopt-time announce
                    // must be allowed to answer the re-posted select-device.
                    deviceReportedRef.current = null;
                    latchCompanion(null);
                  }}>
                  Cancel agent recording
                </Btn>
              )}
              {/* Test the whole trail — runs each step on the device, highlighting it inline. */}
              <Btn sm kind={testing ? 'danger' : 'ghost'} ico={testing ? 'square' : 'play'} onClick={testing ? stopTest : testTrail}
                disabled={!conn || steps.length === 0} title={!conn ? 'Connect a device first' : 'Run every step on the device'}>
                {testing ? 'Stop' : 'Test trail'}
              </Btn>
              <Btn kind="primary" ico={saving ? 'loader-2' : 'save'} spin={saving} onClick={save} disabled={saving || steps.length === 0}>
                {saving ? 'Saving…' : (companion ? 'Save recording' : 'Save trail')}
              </Btn>
            </div>
            )}
          </div>
        </div>
      )}

      {saveOpen && (
        <SaveTrailDialog
          target={target} busy={saving} error={saveErr}
          onCancel={() => { setSaveOpen(false); setSaveErr(null); }}
          onSave={doSave} />
      )}

      {paletteOpen && (
        <div onClick={closeToolPalette} style={{ position: 'fixed', inset: 0, zIndex: 80, background: 'rgba(0,0,0,.45)', display: 'grid', placeItems: 'start center', paddingTop: '12vh' }}>
          <div onClick={(e) => e.stopPropagation()} style={{ width: 680, maxWidth: '92vw', maxHeight: '74vh', display: 'flex', flexDirection: 'column', background: 'var(--bg-elevated)', border: '1px solid var(--tb-hairline)', borderRadius: 14, boxShadow: '0 18px 50px rgba(0,0,0,.5)', overflow: 'hidden' }}>
            {paletteTarget != null && (
              <div className="tb-sub" style={{ fontSize: 11.5, padding: '8px 16px 0' }}>{paletteTarget === 'pending' ? 'Adding a tool to this step — it runs after the existing tool(s).' : `Adding a tool to step ${(steps.findIndex((s) => s.id === paletteTarget) + 1) || ''} — it will run after the existing tool(s).`}</div>
            )}
            <div style={{ flex: '0 0 auto', display: 'flex', alignItems: 'center', gap: 11, padding: '14px 16px', borderBottom: '1px solid var(--tb-hairline)' }}>
              <Ico n="wrench" s={17} c="var(--text-subtle)" />
              <input autoFocus value={pq} onChange={(e) => setPq(e.target.value)} placeholder={paletteTarget != null ? 'Search by name, params, or description…' : 'Search tools by name, params, or description…'}
                style={{ flex: 1, background: 'transparent', border: 'none', outline: 'none', color: 'var(--text-standard)', fontSize: 15 }} />
              {paletteTools.length > 0 && <span className="tb-sub" style={{ fontSize: 11, flex: '0 0 auto' }}>{paletteTools.length}{paletteTools.length === 200 ? '+' : ''}</span>}
              <span className="tb-kbd">esc</span>
            </div>
            <div style={{ flex: '1 1 auto', minHeight: 0, overflowY: 'auto', paddingBottom: 6 }}>
              {tools.loading && <div className="tb-sub" style={{ padding: 14, fontSize: 13 }}>Loading tools…</div>}
              {!tools.loading && paletteTools.length === 0 && <div className="tb-sub" style={{ padding: 14, fontSize: 13 }}>No tools match “{pq}”.</div>}
              {/* Aligned table; the full parameter signature rides on each row's hover title. */}
              {!tools.loading && paletteTools.length > 0 && (
                <div className="tb-paltbl-head"><span>Tool</span><span>Trailmap</span><span>Type</span><span style={{ textAlign: 'right' }}>Args</span></div>
              )}
              {paletteTools.map((t) => {
                const params = t.parameters || [];
                const flavor = t.flavor ? String(t.flavor).toLowerCase() : null;
                const sig = params.length
                  ? params.map((p) => p.name + (p.required ? '*' : '') + ': ' + (p.type || 'any')).join(', ')
                  : 'no parameters';
                const hover = sig + (t.description ? '\n\n' + t.description : '');
                return (
                  <div key={t.trailmap + '/' + t.id} role="button" tabIndex={0} className="tb-paltbl-row" title={hover}
                    onClick={() => addToolFromPalette(t)} onKeyDown={(e) => { if (e.key === 'Enter') addToolFromPalette(t); }}>
                    <div className="tb-paltbl-tool"><Ico n="wrench" s={14} c="var(--text-subtle-variant)" style={{ flex: '0 0 auto' }} /><span className="nm">{t.id}</span></div>
                    <div className="tb-paltbl-cell">{t.trailmap ? <Chip>{t.trailmap}</Chip> : <span className="tb-sub" style={{ fontSize: 11 }}>—</span>}</div>
                    <div className="tb-paltbl-cell">{flavor ? <Chip>{flavor}</Chip> : <span className="tb-sub" style={{ fontSize: 11 }}>—</span>}</div>
                    <div className="tb-paltbl-args">{params.length === 0 ? '—' : params.length + (params.length === 1 ? ' arg' : ' args')}</div>
                  </div>
                );
              })}
            </div>
            {!tools.loading && paletteTools.length > 0 && (
              <div style={{ flex: '0 0 auto', display: 'flex', alignItems: 'center', gap: 10, padding: '9px 16px', borderTop: '1px solid var(--tb-hairline)' }}>
                <span className="tb-sub" style={{ fontSize: 11 }}>{paletteTools.length}{paletteTools.length === 200 ? '+' : ''} tool{paletteTools.length === 1 ? '' : 's'}</span>
                <span style={{ flex: 1 }} />
                <span className="tb-sub" style={{ fontSize: 11, display: 'inline-flex', alignItems: 'center', gap: 6 }}><span className="tb-kbd">↵</span> add · <span className="tb-kbd">esc</span> close</span>
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
}

// One committed step. Display mode reuses trail-detail's StepRow with a hover control strip; edit
// mode swaps in the prompt field + a raw YAML editor.
function StepCard({ step, index, count, canRun, toolMap = new Set(), go, onChange, onRemove, onMove, onRun, onAddTool, runStatus }) {
  useLucide();
  const [editing, setEditing] = React.useState(false);
  const [running, setRunning] = React.useState(false);
  const [outcome, setOutcome] = React.useState(null);
  const tools = React.useMemo(() => parseRecordStepTools(step.yaml || ''), [step.yaml]);
  const hasTools = tools.length > 0;
  // When a "Test trail" run reaches this step, scroll it into view so the highlight is visible.
  const cardRef = React.useRef(null);
  React.useEffect(() => { if (runStatus === 'running' && cardRef.current) cardRef.current.scrollIntoView({ block: 'nearest', behavior: 'smooth' }); }, [runStatus]);
  const run = async () => {
    if (running || !canRun) return;
    setRunning(true); setOutcome(null);
    try { setOutcome(await onRun()); } catch (e) { setOutcome({ ok: false, text: String(e) }); }
    setRunning(false);
  };

  if (editing) {
    return (
      <div className="tb-card" style={{ overflow: 'hidden', flexShrink: 0, borderColor: step.verify ? 'var(--tb-link)' : '#c4a8ff' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '8px 11px' }}>
          <span style={{ width: 20, height: 20, borderRadius: 6, background: 'var(--bg-standard)', color: 'var(--text-subtle)', fontSize: 11, fontWeight: 700, display: 'grid', placeItems: 'center', flex: '0 0 auto' }}>{index + 1}</span>
          <div style={{ display: 'inline-flex', border: '1px solid var(--tb-hairline)', borderRadius: 7, overflow: 'hidden' }}>
            {[['Step', false], ['Verify', true]].map(([lbl, v]) => (
              <button key={lbl} onClick={() => onChange({ verify: v })}
                style={{ border: 'none', cursor: 'pointer', padding: '4px 11px', fontSize: 12, fontWeight: 600,
                  background: step.verify === v ? (v ? 'rgba(94,155,255,.16)' : 'rgba(168,130,255,.16)') : 'transparent',
                  color: step.verify === v ? (v ? 'var(--tb-link)' : '#c4a8ff') : 'var(--text-subtle)' }}>{lbl}</button>
            ))}
          </div>
          <span style={{ flex: 1 }} />
          <Btn sm kind="primary" ico="check" onClick={() => setEditing(false)}>Done</Btn>
        </div>
        <div style={{ borderTop: '1px solid var(--tb-hairline)', background: 'var(--bg-standard)', padding: '11px 12px' }}>
          <label className="tb-eyebrow" style={{ fontSize: 9.5, display: 'block', marginBottom: 4 }}>{step.verify ? 'Assertion' : 'Instruction'}</label>
          <textarea value={step.text || ''} onChange={(e) => onChange({ text: e.target.value })}
            placeholder={step.verify ? 'What should be true? e.g. The home screen shows the balance' : 'What does this step do? e.g. Tap the balance amount'}
            spellCheck={false}
            style={{ width: '100%', boxSizing: 'border-box', minHeight: 42, resize: 'vertical', background: '#0a0a0a', border: '1px solid var(--tb-hairline)', borderRadius: 8, outline: 'none', color: 'var(--text-standard)', padding: '8px 10px', font: 'inherit', fontSize: 13.5, lineHeight: 1.5 }} />
        </div>
        {hasTools && (
          <div style={{ borderTop: '1px solid var(--tb-hairline)', background: 'var(--bg-standard)', padding: '10px 12px' }}>
            <label className="tb-eyebrow" style={{ fontSize: 9.5, display: 'block', marginBottom: 4 }}>Tool YAML</label>
            <textarea value={step.yaml || ''} onChange={(e) => onChange({ yaml: e.target.value })} spellCheck={false}
              style={{ width: '100%', boxSizing: 'border-box', minHeight: 96, resize: 'vertical', background: '#0a0a0a', border: '1px solid var(--tb-hairline)', borderRadius: 8, outline: 'none', color: '#cbd5e1', padding: '9px 11px', fontFamily: 'var(--font-mono)', fontSize: 11.5, lineHeight: 1.5 }} />
          </div>
        )}
      </div>
    );
  }

  const display = { kind: step.verify ? 'verify' : 'step', text: (step.text || '').trim() || step.label || '', recorded: hasTools, tools };
  // A "Test trail" run rings the card and swaps the hover controls for a status pill.
  const ring = runStatus === 'running' ? 'var(--tb-running)' : runStatus === 'pass' ? 'var(--tb-pass)' : runStatus === 'fail' ? 'var(--tb-fail)' : null;
  const pill = runStatus === 'pass'
    ? { ico: 'circle-check', label: 'Passed', col: 'var(--tb-pass)', bg: 'rgba(0,224,19,.12)' }
    : runStatus === 'fail'
      ? { ico: 'circle-x', label: 'Failed', col: 'var(--tb-danger-text)', bg: 'rgba(248,71,82,.14)' }
      : runStatus === 'running'
        ? { ico: 'loader-2', label: 'Running', col: 'var(--tb-running)', bg: 'rgba(94,155,255,.14)' }
        : null;
  return (
    <div ref={cardRef} style={{ position: 'relative', flexShrink: 0, borderRadius: 12, transition: 'box-shadow .15s var(--ease-out-soft)', ...(ring ? { boxShadow: '0 0 0 2px ' + ring } : {}) }}>
      <StepRow step={display} idx={index} go={go} toolMap={toolMap} />
      {pill ? (
        <div style={{ position: 'absolute', top: 9, right: 11, display: 'inline-flex', alignItems: 'center', gap: 5, padding: '3px 10px', borderRadius: 999, background: pill.bg, color: pill.col, fontSize: 11, fontWeight: 700 }}>
          <Ico n={pill.ico} s={12} c={pill.col} spin={runStatus === 'running'} /> {pill.label}
        </div>
      ) : (
      <div style={{ position: 'absolute', top: 9, right: 11, display: 'flex', alignItems: 'center', gap: 1 }}>
        {hasTools && <button title={canRun ? 'Run this step on the device' : 'Connect a device to run'} onClick={run} disabled={running || !canRun} style={iconBtn(running || !canRun)}><Ico n={running ? 'loader-2' : 'play'} s={14} c="var(--tb-pass)" spin={running} /></button>}
        {onAddTool && <button title="Add another tool to this step" onClick={onAddTool} style={iconBtn(false)}><Ico n="wrench" s={13} c="var(--text-subtle)" /></button>}
        <button title="Edit step" onClick={() => setEditing(true)} style={iconBtn(false)}><Ico n="pencil" s={13} c="var(--text-subtle)" /></button>
        <button title="Move up" onClick={() => onMove(-1)} disabled={index === 0} style={iconBtn(index === 0)}><Ico n="chevron-up" s={14} /></button>
        <button title="Move down" onClick={() => onMove(1)} disabled={index === count - 1} style={iconBtn(index === count - 1)}><Ico n="chevron-down" s={14} /></button>
        <button title="Delete" onClick={onRemove} style={iconBtn(false)}><Ico n="trash-2" s={14} c="var(--text-subtle)" /></button>
      </div>
      )}
      {outcome && (
        <div style={{ display: 'flex', alignItems: 'center', gap: 7, padding: '6px 11px', marginTop: -8, marginBottom: 10, borderRadius: 8, background: outcome.ok ? 'rgba(0,224,19,.05)' : 'rgba(248,71,82,.07)', border: '1px solid var(--tb-hairline)' }}>
          <Ico n={outcome.ok ? 'circle-check' : 'circle-x'} s={13} c={outcome.ok ? 'var(--tb-pass)' : 'var(--tb-fail)'} />
          <span style={{ fontSize: 11.5, color: outcome.ok ? 'var(--tb-pass)' : 'var(--tb-danger-text)', flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{outcome.text}</span>
          {outcome.ms > 0 && <span className="tb-sub" style={{ fontSize: 10.5 }}>{(outcome.ms / 1000).toFixed(1)}s</span>}
        </div>
      )}
    </div>
  );
}

function iconBtn(disabled) {
  return { background: 'transparent', border: 'none', cursor: disabled ? 'default' : 'pointer', opacity: disabled ? 0.3 : 0.8,
    padding: 3, display: 'grid', placeItems: 'center', color: 'var(--text-standard)', flex: '0 0 auto' };
}

// A device-toolbar control button (Back / Home / Disconnect).
function navBtn(disabled) {
  return { background: 'var(--bg-subtle)', border: '1px solid var(--tb-hairline)', borderRadius: 7,
    cursor: disabled ? 'default' : 'pointer', opacity: disabled ? 0.4 : 1, padding: '5px 9px',
    display: 'grid', placeItems: 'center', flex: '0 0 auto' };
}

// A small ordinal badge marking the steps of the proposal flow (① describe → ② tool).
function stepDotStyle() {
  return { width: 16, height: 16, borderRadius: 99, border: '1px solid var(--text-subtle)', color: 'var(--text-subtle)',
    fontSize: 9.5, fontWeight: 700, display: 'inline-grid', placeItems: 'center', flex: '0 0 auto' };
}
function nextStepBtn(active, accent) {
  return { flex: '1 1 0', minWidth: 124, display: 'flex', alignItems: 'center', gap: 9, padding: '10px 12px',
    borderRadius: 9, cursor: 'pointer', textAlign: 'left',
    border: '1px solid ' + (active ? accent : 'var(--tb-hairline)'),
    background: active ? 'rgba(255,255,255,.05)' : 'transparent', color: 'var(--text-standard)' };
}
function tblHeadStyle() {
  return { padding: '6px 10px', fontSize: 9, fontWeight: 700, letterSpacing: '.06em', textTransform: 'uppercase',
    color: 'var(--text-subtle)', background: 'var(--bg-subtle)', borderBottom: '1px solid var(--tb-hairline)' };
}
function tblCellStyle(last) {
  return { padding: '7px 10px', borderBottom: last ? 'none' : '1px solid var(--tb-hairline)', minWidth: 0 };
}
const isArgPlaceholder = (v) => typeof v === 'string' && v.startsWith('<') && v.endsWith('>');
const argEditText = (v) => (v && typeof v === 'object') ? JSON.stringify(v) : String(v);
function parseArgText(text, type) {
  const ty = (type || '').toLowerCase();
  const t = text.trim();
  if (ty === 'boolean') return t === 'true';
  if (['int', 'integer', 'number', 'long', 'float', 'double'].includes(ty)) { const n = Number(t); return Number.isFinite(n) ? n : text; }
  if (ty === 'string') return text;
  // Unknown/object-typed args (e.g. nodeSelector): accept JSON, then bool/number, else string.
  if (t.startsWith('{') || t.startsWith('[')) { try { return JSON.parse(t); } catch (_) { return text; } }
  if (t === 'true' || t === 'false') return t === 'true';
  if (t !== '' && Number.isFinite(Number(t))) return Number(t);
  return text;
}

// Find the first `*Regex` STRING field at any depth in a node selector and return its path +
// pattern, for the focused regex editor. Only `*Regex` fields qualify (not literal params like `text`).
function selectorRegexInfo(v) {
  const obj = (v && typeof v === 'object' && !Array.isArray(v)) ? v
    : (typeof v === 'string' && v.trim().startsWith('{') ? (() => { try { return JSON.parse(v); } catch (_) { return null; } })() : null);
  if (!obj || typeof obj !== 'object' || Array.isArray(obj)) return null;
  let found = null;
  const walk = (node, path) => {
    if (found || !node || typeof node !== 'object' || Array.isArray(node)) return;
    for (const k of Object.keys(node)) {
      const val = node[k];
      if (/Regex$/.test(k) && typeof val === 'string') { found = { path: [...path, k], field: k, pattern: val }; return; }
      if (val && typeof val === 'object') walk(val, [...path, k]);
    }
  };
  walk(obj, []);
  if (!found) return null;
  return { obj, path: found.path, field: found.field, pattern: found.pattern };
}
function setAtPath(obj, path, value) {
  if (path.length === 0) return value;
  const [head, ...rest] = path;
  return { ...obj, [head]: setAtPath((obj && obj[head]) || {}, rest, value) };
}

// A focused regex editor + live validator for a node selector's `*Regex` field; keeps the rest.
function SelectorRegexEditor({ info, onCommit }) {
  useLucide();
  const { path, field } = info;
  const [pattern, setPattern] = React.useState(String(info.pattern == null ? '' : info.pattern));
  React.useEffect(() => { setPattern(String(info.pattern == null ? '' : info.pattern)); }, [info.pattern]);
  const [test, setTest] = React.useState('');
  let err = null, re = null;
  try { re = new RegExp(pattern); } catch (e) { err = e.message; }
  const matches = re && test !== '' ? re.test(test) : null;
  const kind = field.replace(/Regex$/, '').replace(/([a-z])([A-Z])/g, '$1 $2').toLowerCase();
  const crumbs = path.slice(0, -1);
  // Never write an uncompilable pattern back into the selector — leave the last valid value in place.
  const commit = () => { if (!err) onCommit(setAtPath(info.obj, path, pattern)); };
  const inputBase = { flex: 1, minWidth: 0, boxSizing: 'border-box', background: '#0a0a0a', borderRadius: 6, outline: 'none', fontFamily: 'var(--font-mono)', fontSize: 12, padding: '5px 8px' };
  return (
    <div style={{ width: '100%', display: 'flex', flexDirection: 'column', gap: 6, padding: '2px 0' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap' }}>
        {crumbs.map((c, i) => <Chip key={i}>{c}</Chip>)}
        <span className="tb-sub" style={{ fontSize: 10.5 }}>matches <strong style={{ color: 'var(--text-subtle-variant)', fontWeight: 600 }}>{kind}</strong> · regex</span>
      </div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 5 }}>
        <span className="tb-mono" style={{ color: 'var(--text-subtle)', fontSize: 12, flex: '0 0 auto' }}>/</span>
        <input value={pattern} onChange={(e) => setPattern(e.target.value)} onBlur={commit}
          onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); e.currentTarget.blur(); } }}
          spellCheck={false} placeholder="regex pattern" title="Edit the match pattern (regular expression)"
          style={{ ...inputBase, color: 'var(--text-standard)', border: '1px solid ' + (err ? 'var(--tb-danger-text)' : 'var(--tb-hairline)') }} />
        <span className="tb-mono" style={{ color: 'var(--text-subtle)', fontSize: 12, flex: '0 0 auto' }}>/</span>
        <Ico n={err ? 'circle-x' : 'circle-check'} s={14} c={err ? 'var(--tb-danger-text)' : 'var(--tb-pass)'} title={err || 'Valid regex'} style={{ flex: '0 0 auto' }} />
      </div>
      {err && <div style={{ color: 'var(--tb-danger-text)', fontSize: 10.5, lineHeight: 1.4 }}>{err}</div>}
      <div style={{ display: 'flex', alignItems: 'center', gap: 7 }}>
        <input value={test} onChange={(e) => setTest(e.target.value)} placeholder="Test against sample text…" spellCheck={false}
          style={{ ...inputBase, fontFamily: 'inherit', fontSize: 11.5, background: 'var(--bg-subtle)', color: 'var(--text-subtle-variant)', border: '1px solid var(--tb-hairline)' }} />
        {test !== '' && !err && (
          <span style={{ fontSize: 10.5, fontWeight: 700, flex: '0 0 auto', display: 'inline-flex', alignItems: 'center', gap: 4, color: matches ? 'var(--tb-pass)' : 'var(--text-subtle)' }}>
            <Ico n={matches ? 'check' : 'x'} s={12} c={matches ? 'var(--tb-pass)' : 'var(--text-subtle)'} />{matches ? 'matches' : 'no match'}
          </span>
        )}
      </div>
    </div>
  );
}

// One tool within a proposal's series — collapsed summary, or an editable Parameter/Value table.
function ProposalToolRow({ idx, entry, catalog = [], collapsed, onToggle, onSetArg, onClearArg, onRemove, headerRight, subtitle }) {
  useLucide();
  const tool = (catalog || []).find((t) => t.id === entry.name);
  const curArgs = (entry.args && typeof entry.args === 'object' && !Array.isArray(entry.args)) ? entry.args : {};
  const [edits, setEdits] = React.useState({});
  React.useEffect(() => { setEdits({}); }, [entry.name, collapsed]);
  const commit = (name, type) => {
    if (!(name in edits)) return;
    const text = edits[name];
    if (text.trim() === '') onClearArg(name); else onSetArg(name, parseArgText(text, type));
    setEdits(({ [name]: _, ...rest }) => rest);
  };
  // Scripted (.ts) tools carry no params in the catalog — fetch their `<I>` fields on demand.
  const [scriptedParams, setScriptedParams] = React.useState(null);
  React.useEffect(() => {
    const isScripted = tool && String(tool.flavor || '').toLowerCase() === 'scripted';
    if (!isScripted || ((tool.parameters || []).length) || !tool.trailmap || !TB.scriptedToolParams) return;
    let alive = true;
    TB.scriptedToolParams(tool.trailmap, entry.name).then((ps) => { if (alive) setScriptedParams(ps || []); });
    return () => { alive = false; };
  }, [entry.name, tool && tool.trailmap, tool && tool.flavor]);
  // Catalog (or on-demand scripted) params unioned with any args set in the YAML.
  const catalogParams = ((tool && tool.parameters) || []).length ? tool.parameters : (scriptedParams || []);
  const catalogNames = new Set(catalogParams.map((p) => p.name));
  const extra = Object.keys(curArgs).filter((k) => !catalogNames.has(k)).map((k) => ({ name: k, type: '', required: false }));
  const isSet = (n) => Object.prototype.hasOwnProperty.call(curArgs, n) && !isArgPlaceholder(curArgs[n]);
  const params = [...catalogParams, ...extra].sort((a, b) => (isSet(b.name) ? 1 : 0) - (isSet(a.name) ? 1 : 0));
  // Compact summary of the set args, for the collapsed row.
  const setArgs = Object.entries(curArgs).filter(([, v]) => !isArgPlaceholder(v));
  return (
    <div style={{ border: '1px solid var(--tb-hairline)', borderRadius: 9, overflow: 'hidden', background: 'var(--bg-elevated)' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 7, padding: '7px 9px', cursor: 'pointer' }} onClick={onToggle}>
        <Ico n={collapsed ? 'chevron-right' : 'chevron-down'} s={14} c="var(--text-subtle)" style={{ flex: '0 0 auto' }} />
        <Ico n="wrench" s={13} c="var(--tb-running)" style={{ flex: '0 0 auto' }} />
        <span className="tb-mono" style={{ fontSize: 12.5, fontWeight: 700, color: 'var(--text-standard)', flex: collapsed ? '0 0 auto' : 1, whiteSpace: 'nowrap' }}>{entry.name}</span>
        {collapsed && setArgs.length > 0 && (
          <span className="tb-mono" style={{ fontSize: 11, color: 'var(--text-subtle)', flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
            {setArgs.map(([k, v]) => k + ': ' + (typeof v === 'object' ? JSON.stringify(v) : String(v))).join('  ·  ')}
          </span>
        )}
        {!collapsed && subtitle}
        <span onClick={(e) => e.stopPropagation()} style={{ display: 'inline-flex', alignItems: 'center', gap: 6, flex: '0 0 auto' }}>
          {!collapsed && headerRight}
          {onRemove && <button title="Remove this tool" onClick={onRemove} style={iconBtn(false)}><Ico n="trash-2" s={13} c="var(--text-subtle)" /></button>}
        </span>
      </div>
      {!collapsed && (
        params.length === 0
          ? <div className="tb-sub" style={{ fontSize: 11, padding: '0 10px 9px' }}>No arguments.</div>
          : (
            <div style={{ borderTop: '1px solid var(--tb-hairline)' }}>
              <div style={{ display: 'grid', gridTemplateColumns: 'minmax(110px, 40%) 1fr' }}>
                <div style={tblHeadStyle()}>Parameter</div>
                <div style={tblHeadStyle()}>Value</div>
                {params.map((p, i) => {
                  const has = Object.prototype.hasOwnProperty.call(curArgs, p.name);
                  const v = curArgs[p.name];
                  const needs = p.required && (!has || isArgPlaceholder(v));
                  const last = i === params.length - 1;
                  // A node selector with a single regex field gets the focused regex editor.
                  const sel = (has && !isArgPlaceholder(v)) ? selectorRegexInfo(v) : null;
                  return (
                    <React.Fragment key={p.name}>
                      <div style={tblCellStyle(last)}>
                        <div style={{ display: 'flex', alignItems: 'baseline', gap: 6, flexWrap: 'wrap' }}>
                          <span className="tb-mono" style={{ fontSize: 12, fontWeight: 700, color: 'var(--text-standard)' }}>{p.name}</span>
                          {p.type ? <span className="tb-sub" style={{ fontSize: 9.5 }}>{p.type}{p.required ? ' · required' : ''}</span> : null}
                        </div>
                        {p.description && <div className="tb-sub" style={{ fontSize: 10.5, lineHeight: 1.4, marginTop: 1 }}>{p.description}</div>}
                      </div>
                      <div style={{ ...tblCellStyle(last), display: 'flex', alignItems: sel ? 'stretch' : 'center' }}>
                        {sel ? (
                          <SelectorRegexEditor info={sel} onCommit={(obj) => onSetArg(p.name, obj)} />
                        ) : (
                          <input
                            value={edits[p.name] !== undefined ? edits[p.name] : (has && !isArgPlaceholder(v) ? argEditText(v) : '')}
                            placeholder={needs ? 'fill this in' : '—'}
                            onChange={(e) => setEdits((a) => ({ ...a, [p.name]: e.target.value }))}
                            onBlur={() => commit(p.name, p.type)}
                            onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); e.currentTarget.blur(); } }}
                            spellCheck={false} title="Click to edit this value"
                            style={{ width: '100%', boxSizing: 'border-box', background: '#0a0a0a', border: '1px solid var(--tb-hairline)', borderRadius: 6, outline: 'none', color: needs ? 'var(--tb-amber)' : 'var(--text-subtle-variant)', fontFamily: 'var(--font-mono)', fontSize: 11.5, padding: '4px 7px' }} />
                        )}
                      </div>
                    </React.Fragment>
                  );
                })}
              </div>
            </div>
          )
      )}
    </div>
  );
}

// The trail's objective ("Context"). Expanded shows the helper text; collapsed shrinks to the value.
function ContextCard({ value, onChange, expanded, onFocus, onBlur, inputRef, cardRef, ringStyle }) {
  useLucide();
  return (
    <div ref={cardRef} className="tb-card" onClick={() => { if (!expanded && inputRef && inputRef.current) inputRef.current.focus(); }}
      style={{ flexShrink: 0, padding: '12px 14px', display: 'flex', flexDirection: 'column', gap: 8, cursor: expanded ? 'default' : 'pointer', ...(ringStyle || {}) }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        <Ico n="target" s={15} c="var(--tb-ai)" />
        <span style={{ fontSize: 12.5, fontWeight: 700, color: 'var(--text-standard)' }}>Context</span>
        <span className="tb-sub" style={{ fontSize: 11, opacity: expanded ? 1 : 0, transition: 'opacity .2s var(--ease-out-soft)' }}>what this trail validates</span>
      </div>
      <textarea ref={inputRef} value={value} onChange={onChange} onFocus={onFocus} onBlur={onBlur}
        onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); e.currentTarget.blur(); } }}
        placeholder="e.g. Validates that a user can send $5 to a friend" spellCheck={false}
        style={{ width: '100%', boxSizing: 'border-box', minHeight: expanded ? 46 : 0, resize: 'vertical', background: '#0a0a0a', border: '1px solid var(--tb-hairline)', borderRadius: 8, outline: 'none', color: 'var(--text-standard)', padding: '8px 10px', font: 'inherit', fontSize: 13.5, lineHeight: 1.5, transition: 'min-height .25s var(--ease-out-soft)' }} />
      <div style={{ maxHeight: expanded ? 80 : 0, opacity: expanded ? 1 : 0, overflow: 'hidden', transition: 'max-height .25s var(--ease-out-soft), opacity .2s var(--ease-out-soft)' }}>
        <div className="tb-sub" style={{ fontSize: 11, lineHeight: 1.5 }}>State the goal of this trail in plain language. The agent uses it to understand your intent — to recover when a screen looks unexpected, and to re-derive these steps on other platforms.</div>
      </div>
    </div>
  );
}

// The "propose the next step" card: the resolved tool (not yet run), a prompt field, and Confirm/Cancel.
function ProposalCard({ pending, num, prompt, setPrompt, busy, toolMap = new Set(), catalog = [], scope = null, platform = null, go, onConfirm, onCancel, onPickOption, onPickYaml, onAddCustom, armed = null, onArmAppend, advice = null }) {
  useLucide();
  const tools = React.useMemo(() => parseRecordStepTools(pending.yaml || ''), [pending.yaml]);
  const options = pending.options || [];
  const curYaml = (pending.yaml || '').trim();
  const isAssert = pending.action === 'assertVisible';
  const curToolName = tools[0] && tools[0].name;
  const curTool = (catalog || []).find((t) => t.id === curToolName);
  const relevant = React.useMemo(() => {
    const list = relevantTools(catalog, pending.gesture, scope, platform);
    // Make sure the currently-chosen tool is always in the list, even if it's not in the family.
    if (curToolName && !list.some((t) => t.id === curToolName)) {
      const cur = (catalog || []).find((t) => t.id === curToolName);
      return cur ? [cur, ...list] : list;
    }
    return list;
  }, [catalog, pending.gesture, curToolName, scope, platform]);
  const selectorObj = React.useMemo(() => bestSelectorObj(options), [options]);
  // Tool 0 (the gesture tool) can be changed: recommended selectors first (with stability tier),
  // then other relevant tools.
  const tool0Options = React.useMemo(() => {
    const recs = options.map((opt, i) => { const m = selectorMeta(opt); return { value: 'opt:' + i, label: opt.toolName, ico: 'star', icoColor: 'var(--tb-primary-green)', match: m.friendly, tier: m.tierLabel, tierTone: tierTone(m.tier) }; });
    const recToolNames = new Set(options.map((o) => o.toolName));
    const others = relevant.filter((t) => !recToolNames.has(t.id)).map((t) => { const n = (t.parameters || []).length; return { value: 'tool:' + t.id, label: t.id, ico: 'wrench', match: n === 0 ? 'no args' : n + ' arg' + (n === 1 ? '' : 's'), tier: null }; });
    return [...recs, ...others];
  }, [options, relevant]);
  const [tool0PickerOpen, setTool0PickerOpen] = React.useState(false);
  const [tool0Query, setTool0Query] = React.useState('');
  const [yamlOpen, setYamlOpen] = React.useState(false);
  React.useEffect(() => {
    if (!tool0PickerOpen) return;
    const h = (e) => { if (e.key === 'Escape') setTool0PickerOpen(false); };
    document.addEventListener('keydown', h);
    return () => document.removeEventListener('keydown', h);
  }, [tool0PickerOpen]);
  const filteredTool0Options = React.useMemo(() => {
    const q = tool0Query.trim().toLowerCase();
    if (!q) return tool0Options;
    return tool0Options.filter((o) => o.label.toLowerCase().includes(q) || (o.match || '').toLowerCase().includes(q) || (o.tier || '').toLowerCase().includes(q));
  }, [tool0Options, tool0Query]);
  // Tool series: a step is the NL prompt + an ordered list of tool calls; all edits rebuild the
  // step's `- tools:` YAML through onPickYaml. Tool 0 is the gesture tool (carries the selector picker).
  const toolEntries = tools.map((t) => ({ name: t.name, args: (t.args && typeof t.args === 'object' && !Array.isArray(t.args)) ? { ...t.args } : {} }));
  const rebuild = (arr) => { if (window.jsyaml && onPickYaml) onPickYaml(window.jsyaml.dump([{ tools: arr.map((t) => ({ [t.name]: t.args })) }], { lineWidth: -1 }).replace(/\n+$/, '')); };
  const cloneEntries = () => toolEntries.map((t) => ({ name: t.name, args: { ...t.args } }));
  const setArg = (idx, name, value) => { const arr = cloneEntries(); arr[idx].args[name] = value; rebuild(arr); };
  const clearArg = (idx, name) => { const arr = cloneEntries(); delete arr[idx].args[name]; rebuild(arr); };
  const removeTool = (idx) => rebuild(toolEntries.filter((_, i) => i !== idx));
  const addInputText = () => { const it = (catalog || []).find((t) => t.id === 'inputText'); if (it && onPickYaml) onPickYaml(appendToolToStepYaml(pending.yaml, it)); };
  // Tool 0's selector/tool, matched against the resolved options so the picker shows the right pick.
  const tool0 = toolEntries[0];
  const curOptIdx = options.findIndex((o) => { const ot = parseRecordStepTools(o.yaml)[0]; return ot && tool0 && ot.name === tool0.name && JSON.stringify(ot.args || {}) === JSON.stringify(tool0.args || {}); });
  const curRec = curOptIdx >= 0 ? selectorMeta(options[curOptIdx]) : null;
  const changeTool0 = (val) => {
    let y = null;
    if (val.startsWith('opt:')) { const o = options[parseInt(val.slice(4), 10)]; y = o && o.yaml; }
    else if (val.startsWith('tool:')) { const id = val.slice(5); const opt = options.find((o) => o.toolName === id); const tool = (catalog || []).find((t) => t.id === id); y = opt ? opt.yaml : (tool ? buildProposalToolYaml(tool, { element: pending.element, gesture: pending.gesture, selectorObj }) : null); }
    const nt = y ? parseRecordStepTools(y)[0] : null;
    if (!nt) return;
    const arr = cloneEntries();
    arr[0] = { name: nt.name, args: (nt.args && typeof nt.args === 'object' && !Array.isArray(nt.args)) ? nt.args : {} };
    rebuild(arr);
  };
  const [collapsed, setCollapsed] = React.useState({});
  const toggleCollapse = (idx) => setCollapsed((c) => ({ ...c, [idx]: !c[idx] }));
  // The agent's selector verdict (Create screen): only offer the one-click swap when the
  // recommendation differs from what's already chosen - endorsements annotate, never nag.
  const adviceOptIdx = React.useMemo(() => {
    if (!advice || advice.state !== 'done' || !advice.preferOption) return -1;
    const i = options.findIndex((o) => o.label === advice.preferOption);
    return i === curOptIdx ? -1 : i;
  }, [advice, options, curOptIdx]);
  // Match the saved trail step cards: STEP = purple, VERIFY (assert) = blue.
  const accent = isAssert ? 'var(--tb-link)' : '#c4a8ff';
  // A description is required before the step can be confirmed + run.
  const canConfirm = !!(prompt || '').trim();
  return (
    <>
    {/* flexShrink:0 so the card keeps its height in the scrolling column. */}
    <div className="tb-card" style={{ overflow: 'hidden', borderColor: accent, flexShrink: 0 }}>
      {/* One-line header: numbered like a saved step, STEP/VERIFY chip, and what was grabbed. */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '8px 11px' }}>
        <span style={{ width: 20, height: 20, borderRadius: 6, background: 'var(--bg-standard)', color: 'var(--text-subtle)', fontSize: 11, fontWeight: 700, display: 'grid', placeItems: 'center', flex: '0 0 auto' }}>{num || 1}</span>
        <Chip tone={isAssert ? 'blue' : 'purple'}>{isAssert ? 'VERIFY' : 'STEP'}</Chip>
        <span style={{ fontSize: 13, fontWeight: 700, color: 'var(--text-standard)', minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{pending.element ? elementTitle(pending.element) : pending.label}</span>
        <span style={{ flex: 1 }} />
        {/* Edit the whole step as raw YAML — top-right of the step. */}
        {onPickYaml && (
          <button onClick={() => setYamlOpen((v) => !v)} title="Edit the whole step as YAML"
            style={{ display: 'inline-flex', alignItems: 'center', gap: 5, background: yamlOpen ? 'var(--bg-prominent)' : 'transparent', border: '1px solid ' + (yamlOpen ? 'var(--tb-hairline-strong)' : 'transparent'), borderRadius: 7, cursor: 'pointer', color: yamlOpen ? 'var(--text-standard)' : 'var(--text-subtle)', fontSize: 11, fontWeight: 600, padding: '3px 8px', flex: '0 0 auto' }}>
            <Ico n="braces" s={12} c={yamlOpen ? 'var(--text-standard)' : 'var(--text-subtle)'} /> Edit YAML
          </button>
        )}
        <span className="tb-sub" style={{ fontSize: 10.5, flex: '0 0 auto', marginLeft: 8 }}>not run yet</span>
      </div>
      {!pending.element && (
        <div className="tb-sub" style={{ padding: '0 12px 8px', fontSize: 11.5, lineHeight: 1.4 }}>No labeled element here, so this falls back to screen coordinates.</div>
      )}
      {/* Describe first — the prompt is the primary thing; the tool it pairs with is shown below. */}
      <div style={{ borderTop: '1px solid var(--tb-hairline)', padding: '11px 12px', display: 'flex', flexDirection: 'column', gap: 7 }}>
        <label className="tb-eyebrow" style={{ fontSize: 9.5, display: 'flex', alignItems: 'center', gap: 7 }}><span style={stepDotStyle()}>1</span>{isAssert ? 'Describe the assertion' : 'Describe this step'} <span style={{ color: 'var(--tb-amber)' }}>*</span></label>
        <textarea autoFocus value={prompt} onChange={(e) => setPrompt(e.target.value)}
          placeholder={isAssert ? 'What should be visible? e.g. The Pay button is on screen' : 'What does this step accomplish? e.g. Tap the Pay button'}
          onKeyDown={(e) => { if ((e.metaKey || e.ctrlKey) && e.key === 'Enter') { e.preventDefault(); if (canConfirm) onConfirm(); } }}
          style={{ width: '100%', boxSizing: 'border-box', minHeight: 44, resize: 'vertical', background: '#0a0a0a', border: '1px solid var(--tb-hairline)', borderRadius: 8, outline: 'none', color: 'var(--text-standard)', padding: '8px 10px', font: 'inherit', fontSize: 13.5, lineHeight: 1.5 }} />
      </div>

      {/* The agent's reasoning strip (violet = the agent's lane, ch09): its take on the selector
          choice, annotating within its 3s budget or vanishing. Purely advisory - it never gates
          Confirm, and the thinking state is deliberately quiet (no layout shift on resolve). */}
      {advice && (
        <div style={{ borderTop: '1px solid var(--tb-hairline)', padding: '8px 12px', display: 'flex', alignItems: 'flex-start', gap: 8, background: 'color-mix(in srgb, var(--tb-ai) 7%, transparent)' }}>
          <Ico n={advice.state === 'thinking' ? 'loader-circle' : 'sparkles'} s={13} c="var(--tb-ai)" spin={advice.state === 'thinking'} style={{ flex: '0 0 auto', marginTop: 1 }} />
          {advice.state === 'thinking' ? (
            <span className="tb-sub" style={{ fontSize: 11.5 }}>The agent is weighing the selector…</span>
          ) : (
            <div style={{ minWidth: 0, flex: 1, display: 'flex', flexDirection: 'column', gap: 6 }}>
              <span style={{ fontSize: 11.5, lineHeight: 1.45, color: 'var(--text-subtle-variant)' }}>{advice.reason}</span>
              {adviceOptIdx >= 0 && (
                <button onClick={() => changeTool0('opt:' + adviceOptIdx)} title={'Switch this step to ' + options[adviceOptIdx].label}
                  style={{ alignSelf: 'flex-start', display: 'inline-flex', alignItems: 'center', gap: 5, background: 'color-mix(in srgb, var(--tb-ai) 16%, transparent)', border: '1px solid var(--tb-ai)', borderRadius: 7, cursor: 'pointer', color: 'var(--tb-ai)', fontSize: 11, fontWeight: 700, padding: '3px 9px' }}>
                  <Ico n="wand-sparkles" s={12} c="var(--tb-ai)" /> Use {options[adviceOptIdx].label}
                </button>
              )}
            </div>
          )}
        </div>
      )}

      {/* 2 · Tools — the ordered series of tool calls this step runs. */}
      <div style={{ borderTop: '1px solid var(--tb-hairline)', background: 'var(--bg-standard)', padding: '11px 12px', display: 'flex', flexDirection: 'column', gap: 9 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 7 }}>
          <span style={stepDotStyle()}>2</span>
          <span className="tb-eyebrow" style={{ fontSize: 9.5 }}>Tools</span>
          {toolEntries.length > 1 && <span className="tb-sub" style={{ fontSize: 10.5 }}>{toolEntries.length} in order</span>}
          <span style={{ flex: 1 }} />
        </div>
        {yamlOpen ? (
          /* Edit the whole step as raw YAML (all tools + args). */
          <textarea autoFocus value={pending.yaml || ''} onChange={(e) => onPickYaml && onPickYaml(e.target.value)} spellCheck={false}
            style={{ width: '100%', boxSizing: 'border-box', minHeight: 160, resize: 'vertical', background: '#0a0a0a', border: '1px solid var(--tb-hairline)', borderRadius: 8, outline: 'none', color: '#cbd5e1', padding: '9px 11px', fontFamily: 'var(--font-mono)', fontSize: 12, lineHeight: 1.55 }} />
        ) : (
          <>
            {toolEntries.map((entry, i) => (
              <ProposalToolRow key={i} idx={i} entry={entry} catalog={catalog}
                collapsed={!!collapsed[i]} onToggle={() => toggleCollapse(i)}
                onSetArg={(name, value) => setArg(i, name, value)} onClearArg={(name) => clearArg(i, name)}
                onRemove={i > 0 ? () => removeTool(i) : null}
                subtitle={i === 0 && curRec ? <span className="tb-sub" style={{ fontSize: 11, flex: '0 0 auto', whiteSpace: 'nowrap' }}>{curRec.friendly} <span style={{ color: curRec.tierColor, fontWeight: 700 }}>({curRec.tierLabel})</span></span> : null}
                headerRight={i === 0 && tool0Options.length > 1 ? (
                  <button onClick={() => { setTool0Query(''); setTool0PickerOpen(true); }} title="Change the tool or match strategy"
                    style={{ display: 'inline-flex', alignItems: 'center', gap: 5, background: 'var(--bg-subtle)', border: '1px solid var(--tb-hairline-strong)', borderRadius: 7, cursor: 'pointer', color: 'var(--text-subtle-variant)', fontSize: 11, fontWeight: 600, padding: '4px 9px' }}>
                    Change <Ico n="chevrons-up-down" s={12} c="var(--text-subtle)" />
                  </button>
                ) : null} />
            ))}
            {/* Add another tool to THIS step: Tap/Verify arm the next device tap; Type text/Custom add directly. */}
            <div style={{ display: 'flex', alignItems: 'center', gap: 7, marginTop: 2 }}>
              <Ico n="plus" s={12} c="var(--text-subtle)" />
              <span className="tb-eyebrow" style={{ fontSize: 9.5 }}>{toolEntries.length > 0 ? 'Add another tool' : 'Add a tool'}</span>
              <span className="tb-sub" style={{ fontSize: 10.5 }}>{armed ? 'tap an element on the device to add it' : 'runs after the one' + (toolEntries.length === 1 ? '' : 's') + ' above'}</span>
            </div>
            <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
              <button onClick={() => onArmAppend && onArmAppend(armed === 'tap' ? null : 'tap')} style={nextStepBtn(armed === 'tap', '#c4a8ff')} title="Tap an element on the device to add a tap to this step">
                <Ico n="pointer" s={15} c={armed === 'tap' ? '#c4a8ff' : 'var(--text-subtle)'} style={{ flex: '0 0 auto' }} />
                <div style={{ minWidth: 0 }}><div style={{ fontSize: 12.5, fontWeight: 700 }}>Tap</div><div className="tb-sub" style={{ fontSize: 10.5 }}>tap the screen</div></div>
              </button>
              <button onClick={() => onArmAppend && onArmAppend(armed === 'assertVisible' ? null : 'assertVisible')} style={nextStepBtn(armed === 'assertVisible', 'var(--tb-link)')} title="Tap an element on the device to add a visibility check to this step">
                <Ico n="circle-check" s={15} c={armed === 'assertVisible' ? 'var(--tb-link)' : 'var(--text-subtle)'} style={{ flex: '0 0 auto' }} />
                <div style={{ minWidth: 0 }}><div style={{ fontSize: 12.5, fontWeight: 700 }}>Verify</div><div className="tb-sub" style={{ fontSize: 10.5 }}>tap what to check</div></div>
              </button>
              <button onClick={addInputText} style={nextStepBtn(false, 'var(--tb-primary-green)')} title="Add a Type text (inputText) tool to this step">
                <Ico n="keyboard" s={15} c="var(--text-subtle)" style={{ flex: '0 0 auto' }} />
                <div style={{ minWidth: 0 }}><div style={{ fontSize: 12.5, fontWeight: 700 }}>Type text</div><div className="tb-sub" style={{ fontSize: 10.5 }}>inputText</div></div>
              </button>
              {/* Only hosts that provide a tool palette get the Custom button (the Interact screen
                  does; the Create trail rail doesn't). */}
              {onAddCustom && (
                <button onClick={onAddCustom} style={nextStepBtn(false, 'var(--tb-running)')} title="Add any tool from the catalog to this step">
                  <Ico n="wrench" s={15} c="var(--text-subtle)" style={{ flex: '0 0 auto' }} />
                  <div style={{ minWidth: 0 }}><div style={{ fontSize: 12.5, fontWeight: 700 }}>Custom tool</div><div className="tb-sub" style={{ fontSize: 10.5 }}>pick from catalog</div></div>
                </button>
              )}
            </div>
          </>
        )}
      </div>
      <div style={{ borderTop: '1px solid var(--tb-hairline)', padding: '10px 12px', display: 'flex', gap: 8, justifyContent: 'flex-end', alignItems: 'center' }}>
        {!canConfirm && <span className="tb-sub" style={{ fontSize: 11, flex: 1, minWidth: 0 }}>Describe this step to continue.</span>}
        <Btn sm kind="ghost" ico="x" onClick={onCancel} disabled={busy}>Cancel</Btn>
        <Btn sm kind="primary" ico={busy ? 'loader-2' : 'check'} spin={busy} onClick={onConfirm} disabled={busy || !canConfirm} title={!canConfirm ? 'Describe this step first' : undefined}>{isAssert ? 'Add assertion' : 'Confirm & run'}</Btn>
      </div>
    </div>
    {/* Full-screen picker for tool 0's match strategy / tool. */}
    {tool0PickerOpen && (
      <div onClick={() => setTool0PickerOpen(false)} style={{ position: 'fixed', inset: 0, zIndex: 90, background: 'rgba(0,0,0,.5)', display: 'grid', placeItems: 'start center', paddingTop: '11vh' }}>
        <div onClick={(e) => e.stopPropagation()} style={{ width: 600, maxWidth: '92vw', maxHeight: '74vh', display: 'flex', flexDirection: 'column', background: 'var(--bg-elevated)', border: '1px solid var(--tb-hairline)', borderRadius: 14, boxShadow: '0 18px 50px rgba(0,0,0,.5)', overflow: 'hidden' }}>
          <div style={{ flex: '0 0 auto', display: 'flex', alignItems: 'center', gap: 11, padding: '14px 16px', borderBottom: '1px solid var(--tb-hairline)' }}>
            <Ico n="target" s={16} c="var(--text-subtle)" />
            <input autoFocus value={tool0Query} onChange={(e) => setTool0Query(e.target.value)} placeholder="Search match strategies and tools…"
              style={{ flex: 1, background: 'transparent', border: 'none', outline: 'none', color: 'var(--text-standard)', fontSize: 15 }} />
            <span className="tb-kbd">esc</span>
          </div>
          <div className="tb-paltbl-head" style={{ gridTemplateColumns: 'minmax(0,1fr) minmax(0,1.1fr) 110px' }}>
            <span>Tool</span><span>Match strategy</span><span style={{ textAlign: 'right' }}>Reliability</span>
          </div>
          <div style={{ flex: '1 1 auto', minHeight: 0, overflowY: 'auto', paddingBottom: 6 }}>
            {filteredTool0Options.length === 0 && <div className="tb-sub" style={{ padding: 14, fontSize: 13 }}>No matches.</div>}
            {filteredTool0Options.map((o) => {
              const sel = o.value === (curOptIdx >= 0 ? 'opt:' + curOptIdx : (tool0 ? 'tool:' + tool0.name : ''));
              return (
                <div key={o.value} role="button" tabIndex={0} className={'tb-paltbl-row' + (sel ? ' active' : '')} style={{ gridTemplateColumns: 'minmax(0,1fr) minmax(0,1.1fr) 110px' }}
                  onClick={() => { changeTool0(o.value); setTool0PickerOpen(false); }}
                  onKeyDown={(e) => { if (e.key === 'Enter') { changeTool0(o.value); setTool0PickerOpen(false); } }}>
                  <div className="tb-paltbl-tool"><Ico n={o.ico} s={14} c={o.icoColor || 'var(--text-subtle-variant)'} style={{ flex: '0 0 auto' }} /><span className="nm">{o.label}</span></div>
                  <div className="tb-paltbl-cell"><span className="tb-sub" style={{ fontSize: 12 }}>{o.match || '—'}</span></div>
                  <div className="tb-paltbl-cell" style={{ textAlign: 'right' }}>{o.tier ? <Chip tone={o.tierTone}>{o.tier}</Chip> : <span className="tb-sub" style={{ fontSize: 11 }}>—</span>}</div>
                </div>
              );
            })}
          </div>
        </div>
      </div>
    )}
    </>
  );
}

// The Elements inspector: the accessibility tree, filtered to interactive/labeled elements. Hovering
// highlights an element on the mirror; clicking proposes a step for it (a tap, or an assert in Check mode).
function ElementsPanel({ els = [], total = 0, loading, err, query, setQuery, mode, disabled, onHover, onPick }) {
  useLucide();
  const isCheck = mode === 'check';
  return (
    <div style={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 7, background: 'var(--bg-subtle)', border: '1px solid var(--tb-hairline)', borderRadius: 8, padding: '6px 9px', marginBottom: 8 }}>
        <Ico n="search" s={13} c="var(--text-subtle)" />
        <input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="Filter by label or type…"
          style={{ flex: 1, background: 'transparent', border: 'none', outline: 'none', color: 'var(--text-standard)', fontSize: 12.5 }} />
      </div>
      <div className="tb-sub" style={{ fontSize: 11, marginBottom: 8, lineHeight: 1.45 }}>
        {isCheck ? 'Click an element to assert it is visible. Hover to find it on screen.' : 'Click an element to tap it. Hover to find it on screen.'}
      </div>
      <div style={{ flex: 1, overflowY: 'auto', minHeight: 0, display: 'flex', flexDirection: 'column', gap: 5, paddingRight: 4 }}
        onMouseLeave={() => onHover(null)}>
        {loading && <div className="tb-sub" style={{ fontSize: 12.5, padding: '8px 2px', display: 'flex', alignItems: 'center', gap: 7 }}><Ico n="loader-2" s={14} spin /> Reading the screen…</div>}
        {!loading && err && <div role="alert" style={{ fontSize: 12.5, color: 'var(--tb-danger-text)', padding: '8px 2px', lineHeight: 1.5 }}>{err} <span className="tb-sub">Try Refresh.</span></div>}
        {!loading && !err && els.length === 0 && (
          <div className="tb-sub" style={{ fontSize: 12.5, padding: '8px 2px', lineHeight: 1.5 }}>{total === 0 ? 'No elements read yet. Press Refresh to scan the current screen.' : 'No elements match your filter.'}</div>
        )}
        {els.map((el, i) => {
          const label = (el.label || '').trim();
          const type = (el.type || '').trim();
          const rid = (el.resourceId || '').trim();
          const details = [label ? '“' + label + '”' : null, type || null, rid ? 'id: ' + rid : null,
            (isCheck ? 'Assert this is visible' : 'Tap this element')].filter(Boolean).join('\n');
          return (
            <div key={i} role="button" tabIndex={0}
              onMouseEnter={() => onHover(el)} onFocus={() => onHover(el)}
              onClick={() => { if (!disabled) onPick(el); }}
              onKeyDown={(e) => { if (e.key === 'Enter' && !disabled) onPick(el); }}
              title={details}
              style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '8px 10px', borderRadius: 9, cursor: disabled ? 'default' : 'pointer', flexShrink: 0,
                border: '1px solid var(--tb-hairline)', background: 'var(--bg-subtle)', opacity: disabled ? 0.55 : 1 }}>
              <Ico n={el.interactive ? 'mouse-pointer-click' : 'type'} s={14} c={el.interactive ? 'var(--tb-primary-green)' : 'var(--text-subtle)'} style={{ flex: '0 0 auto' }} />
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ fontSize: 13, fontWeight: 600, color: label ? 'var(--text-standard)' : 'var(--text-subtle)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{label || '(no label)'}</div>
                <div className="tb-sub" style={{ fontSize: 11, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                  {rid ? <span className="tb-mono">{rid}</span> : (type ? type + (el.interactive ? '' : ' · display only') : (el.interactive ? '' : 'display only'))}
                </div>
              </div>
              <Ico n={isCheck ? 'circle-check' : 'hand-pointer'} s={13} c="var(--text-subtle)" style={{ flex: '0 0 auto' }} />
            </div>
          );
        })}
      </div>
    </div>
  );
}
