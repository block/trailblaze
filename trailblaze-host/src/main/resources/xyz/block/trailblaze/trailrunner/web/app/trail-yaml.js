// Unified trail/blaze YAML producers AND reader-side normalizers — the single source of truth for
// turning the recorder's in-memory step model into on-disk `config:` + `trail:` YAML (unified map
// shape, never the legacy v1 list) AND for reading a saved trail back into the recorder/board's
// working shape. Dual-exported so the browser (`window.TrailYamlBuild`, loaded as a classic <script>
// before the .tsx screens) and `bun test` (CommonJS `module.exports`) share one implementation;
// the .tsx screens keep thin same-name wrappers that delegate here, so their call sites are
// unchanged. YAML (de)serialization is delegated to `window.jsyaml` (the js-yaml UMD global in the
// browser; a Bun.YAML shim in tests) at call time, so this file has no hard yaml-lib dependency; the
// normalizers (`normalizeTrailDoc`, `applyRecordingEdit`) are PURE parsed-object-in/out so the tsx
// edge keeps the yaml.load/dump and these stay unit-testable.
//
// Unified recordings are keyed by device classifier (lowercase platform, e.g. `android`) — the
// broadest classifier segment, so closest-wins replay matches on any device of that platform.
(function () {
  // Quote a string as a YAML scalar when it carries metacharacters (or is empty — a bare `key:`
  // parses as null, not "").
  function recordYamlValue(v) {
    const s = String(v == null ? '' : v);
    if (s === '' || /[:{}\[\],&*#?|<>=!%@`'"\\]/.test(s) || s.includes('\n')) return JSON.stringify(s);
    return s;
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

  // The tool list a trailhead run replays: the trailhead's own tools when present, else the single
  // launch tool with its (dot-path-expanded) args filled in.
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

  // Assemble the editable step cards into one runnable unified trail YAML (`config:` + `trail:` with
  // per-step `recording: { <classifier>: [tools] }`).
  function buildRecordedTrailYaml(title, target, platform, steps) {
    const classifier = String(platform || '').toLowerCase();
    const lines = ['config:', `  title: ${recordYamlValue(title)}`];
    if (target) lines.push(`  target: ${recordYamlValue(target)}`);
    // Emit a step's tools under `recording:` keyed by the device classifier, matching the unified
    // trail-detail shape (`recording: { <classifier>: [tools] }`).
    const recordingLines = (toolItems) => {
      const out = ['    recording:'];
      window.jsyaml.dump({ [classifier]: toolItems.map((t) => ({ [t.name]: t.args == null ? {} : t.args })) }, { lineWidth: -1 })
        .replace(/\n+$/, '').split('\n').forEach((l) => out.push('      ' + l));
      return out;
    };
    const stepLines = [];
    for (const s of steps) {
      const text = (s.text || '').trim();
      const toolItems = s.yaml ? parseRecordStepTools(s.yaml) : [];
      const hasRecording = toolItems.length && classifier && window.jsyaml;
      if (text) {
        stepLines.push(`  - ${s.verify ? 'verify' : 'step'}: ${recordYamlValue(text)}`);
        if (hasRecording) stepLines.push(...recordingLines(toolItems));
      } else if (hasRecording) {
        // A tools-only step (no NL text). Unified requires a step's intent, so synthesize it from the
        // step label (the tool id); the recording still replays deterministically regardless of text.
        stepLines.push(`  - step: ${recordYamlValue(s.label || '')}`);
        stepLines.push(...recordingLines(toolItems));
      }
    }
    // Always emit a `trail:` so the document stays the unified map shape even for a degenerate
    // recording that produces no step lines (all steps tools-only with a blank classifier, or empty).
    // An explicit `trail: []` reads as the decoder's allowed config-only metadata doc AND keeps the
    // web step-matrix model (trail-model.js) classifying it as unified — a bare `trail:` would parse
    // as null (not an empty list) and fall back to the legacy-list path.
    lines.push(stepLines.length ? 'trail:' : 'trail: []', ...stepLines);
    return lines.join('\n');
  }

  // Wrap a raw recorded-tool list ([{ toolName: body }, …]) in the single-step unified trail the
  // ToolRunRequest executor decodes and runs, keyed by the device classifier (lowercased platform)
  // so it replays deterministically on the connected device. Returns null with no tools or a blank
  // classifier. The Trailmaps "Run" tab and the Trail Detail cell runner hold the tool list directly
  // and call this; buildRunnableToolYaml is the variant that first extracts the list out of a step's
  // YAML string.
  function buildToolListRunYaml(label, tools, platform) {
    if (!window.jsyaml) return null;
    const classifier = String(platform || '').toLowerCase();
    if (!classifier || !tools || !tools.length) return null;
    const lines = ['config:', `  title: ${JSON.stringify('Run: ' + label)}`, 'trail:', `  - step: ${JSON.stringify('Run: ' + label)}`, '    recording:'];
    window.jsyaml.dump({ [classifier]: tools }, { lineWidth: -1 }).replace(/\n+$/, '').split('\n').forEach((l) => lines.push('      ' + l));
    return lines.join('\n');
  }

  // Wrap a step's tool(s) in the single-step unified trail ToolRunRequest needs (a bare `- tools:`
  // item is rejected). Extracts the tool list out of the step's YAML string, then delegates to
  // buildToolListRunYaml. Returns null if the step has no parseable tool.
  function buildRunnableToolYaml(label, stepYaml, platform) {
    if (!window.jsyaml) return null;
    let tools = null;
    try {
      const doc = window.jsyaml.load(stepYaml);
      const items = Array.isArray(doc) ? doc : [doc];
      for (const it of items) if (it && it.tools) tools = it.tools;
    } catch (e) { return null; }
    return buildToolListRunYaml(label, tools, platform);
  }

  // Wrap a trailhead's tools in the runnable unified trail shape so "Go to trailhead" replays it on
  // the device. The recording is keyed by the device classifier (lowercased platform).
  function buildTrailheadRunYaml(name, tools, args, platform) {
    if (!window.jsyaml) return null;
    const classifier = String(platform || '').toLowerCase();
    if (!classifier) return null;
    const lines = ['config:', `  title: ${JSON.stringify('Trailhead: ' + name)}`, 'trail:', `  - step: ${JSON.stringify('Enter trailhead: ' + name)}`, '    recording:'];
    window.jsyaml.dump({ [classifier]: trailheadRunTools(name, tools, args) }, { lineWidth: -1 }).replace(/\n+$/, '').split('\n').forEach((l) => lines.push('      ' + l));
    return lines.join('\n');
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
    const classifier = String(platform || '').toLowerCase();
    const lines = ['config:', `  title: ${yamlValue(title)}`];
    if (target) lines.push(`  target: ${yamlValue(target)}`);
    lines.push('trail:');
    (prependSteps || []).forEach((s) => {
      const args = Object.entries(s.args || {}).map(([k, v]) => `${k}: ${yamlValue(v)}`).join(', ');
      lines.push(`  - step: ${yamlValue(s.label)}`);
      if (classifier) {
        lines.push(
          '    recording:',
          `      ${classifier}:`,
          `        - ${s.tool}: ${args ? `{ ${args} }` : '{}'}`,
        );
      }
    });
    lines.push(`  - step: ${JSON.stringify(String(objective))}`);
    return lines.join('\n');
  }

  // Build a blaze.yaml from the structured fields: config (with the original objective preserved in
  // metadata) + an ordered list of do/verify steps, NO recordings. `extra` carries optional config
  // (context) and metadata (destination — the eventual commit home, kept in metadata so the OSS
  // TrailConfig model is untouched, same as objective). `steps` is an array of { kind, text }.
  function buildBlazeYaml(title, target, platform, objective, steps, extra) {
    extra = extra || {};
    const yamlValue = (v) => {
      if (typeof v === 'string') {
        if (v === '' || /[:{}\[\],&*#?|<>=!%@`'"\\]/.test(v) || v.includes('\n')) return JSON.stringify(v);
        return v;
      }
      return String(v);
    };
    const lines = ['config:', `  title: ${yamlValue(title)}`];
    if (target) lines.push(`  target: ${yamlValue(target)}`);
    if (extra.context) lines.push(`  context: ${yamlValue(extra.context)}`);
    const meta = [];
    if (objective) meta.push(`    objective: ${yamlValue(objective)}`);
    if (extra.destination) meta.push(`    destination: ${yamlValue(extra.destination)}`);
    if (meta.length) { lines.push('  metadata:'); meta.forEach((m) => lines.push(m)); }
    const trailSteps = (steps || []).filter((s) => (s.text || '').trim());
    if (trailSteps.length) {
      lines.push('trail:');
      trailSteps.forEach((s) => {
        const key = (s.kind === 'verify') ? 'verify' : 'step';
        lines.push(`  - ${key}: ${yamlValue(s.text)}`);
      });
    }
    return lines.join('\n');
  }

  // Re-serialize a bundle's blaze.yaml when saving STRUCTURED edits (title/target/platform/destination/
  // steps), while PRESERVING any config the structured editor doesn't model (tags, priority, driver,
  // skip, description, memory, source, electron, id, plus extra metadata keys) — anything a user typed
  // in the raw YAML editor. Reads BOTH the legacy v1 list shape (`[{config},{trailhead},{prompts}]`)
  // and the unified map shape (`{config, trailhead, trail}`) so an existing file typed in either
  // format round-trips; always WRITES the unified map shape. Falls back to a clean buildBlazeYaml if
  // the existing file can't be parsed.
  function mergeBlazeYaml(existingYaml, fields) {
    const f = fields || {};
    if (!window.jsyaml) return buildBlazeYaml(f.title, f.target, f.platform, f.objective, f.steps, { context: f.context, destination: f.destination });
    let config = {};
    // Preserve the `trailhead:` (deterministic step 0) verbatim across a structured save, exactly
    // like config — the step editor doesn't model it, so rebuilding {config, trail} alone would
    // silently drop it.
    let trailhead = null;
    try {
      const doc = window.jsyaml.load(existingYaml);
      if (Array.isArray(doc)) {
        for (const it of doc) {
          if (it && it.config) config = { ...it.config };
          if (it && it.trailhead != null) trailhead = it.trailhead;
        }
      } else if (doc && typeof doc === 'object') {
        if (doc.config) config = { ...doc.config };
        if (doc.trailhead != null) trailhead = doc.trailhead;
      }
    } catch (e) {
      return buildBlazeYaml(f.title, f.target, f.platform, f.objective, f.steps, { context: f.context, destination: f.destination });
    }
    const set = (k, v) => { if (v != null && v !== '') config[k] = v; else delete config[k]; };
    set('title', f.title);
    set('target', f.target);
    // Note: no `platform` — UnifiedTrailConfig has no platform field (it's a v1-only key). Platform
    // is carried by the classifier keys under each step's `recording:`; emitting `config.platform`
    // here would be dropped by lenient parsing and rejected by strict trail validation. Any existing
    // `platform:` a file already carried is stripped below.
    delete config.platform;
    set('context', f.context);
    const meta = { ...(config.metadata || {}) };
    if (f.objective) meta.objective = f.objective; else delete meta.objective;
    if (f.destination) meta.destination = f.destination; else delete meta.destination;
    if (Object.keys(meta).length) config.metadata = meta; else delete config.metadata;
    const trail = (f.steps || []).filter((s) => s.text.trim()).map((s) => ({ [s.kind === 'verify' ? 'verify' : 'step']: s.text }));
    const out = { config };
    if (trailhead != null) out.trailhead = trailhead;
    if (trail.length) out.trail = trail;
    try {
      return window.jsyaml.dump(out, { lineWidth: -1, noRefs: true }).trimEnd();
    } catch (e) {
      return buildBlazeYaml(f.title, f.target, f.platform, f.objective, f.steps, { context: f.context, destination: f.destination });
    }
  }

  // Normalize a PARSED trail doc into the recorder/board working shape
  // `{ config, prompts, trailhead, toolsItems }`, reading BOTH the unified map (`config:` + `trail:`
  // with per-classifier `recording:`) and the legacy v1 list (`[{config},{trailhead},{prompts},…]`).
  // For unified docs the per-classifier step recordings are flattened into the board's single
  // `recording.tools` list (a saved variant file is single-classifier, so this is lossless); the v1
  // fallback preserves the exact historical item-scan behavior. Pure: the caller does the yaml.load.
  function normalizeTrailDoc(doc) {
    const TM = typeof window !== 'undefined' ? window.TM : null;
    const matrix = TM && TM.unifiedDocToMatrix ? TM.unifiedDocToMatrix(doc) : null;
    if (matrix) {
      const prompts = (matrix.steps || []).map((s) => {
        const p = { [s.kind === 'verify' ? 'verify' : 'step']: s.text };
        const tools = [];
        Object.keys(s.recording || {}).forEach((plat) => {
          (s.recording[plat] || []).forEach((t) => tools.push({ [t.name]: t.body }));
        });
        if (tools.length) p.recording = { tools };
        return p;
      });
      return { config: matrix.config || {}, prompts, trailhead: doc.trailhead != null ? doc.trailhead : null, toolsItems: [] };
    }
    // Legacy v1 list, or a config-only map the matrix normalizer declines (no `trail:`/`trailhead:`).
    let config = {};
    let prompts = [];
    let trailhead = null;
    const toolsItems = [];
    const items = Array.isArray(doc) ? doc : doc ? [doc] : [];
    for (const it of items) {
      if (it && it.config) config = it.config;
      if (it && it.prompts) prompts = it.prompts;
      if (it && it.trailhead != null) trailhead = it.trailhead;
      if (it && Array.isArray(it.tools)) toolsItems.push(it.tools);
    }
    return { config, prompts, trailhead, toolsItems };
  }

  // Patch one step's recorded tool list on a PARSED doc, in place, PRESERVING every other item
  // (config, trailhead, setup blocks). Shape-aware: a v1 list keeps the historical `- prompts:` /
  // `recording: { tools }` edit; a unified map edits `trail[promptIndex].recording[classifier]`
  // (a `[{tool: args}]` list). `promptIndex == null` creates a step (from `stepInfo {text,kind}`);
  // an empty `newToolsArray` removes that step's recording. Returns `{ value }` to serialize,
  // `{ noop: true }`, or `{ error }`. Pure: the caller does the yaml.load/dump.
  function applyRecordingEdit(doc, promptIndex, newToolsArray, stepInfo, classifier) {
    const hasTools = !!(newToolsArray && newToolsArray.length);
    const kindKey = (stepInfo && stepInfo.kind) === 'verify' ? 'verify' : 'step';
    if (Array.isArray(doc)) {
      const pItem = doc.find((it) => it && Array.isArray(it.prompts));
      if (promptIndex == null) {
        if (!hasTools) return { noop: true };
        const p = { [kindKey]: (stepInfo && stepInfo.text) || '', recording: { tools: newToolsArray } };
        if (pItem) pItem.prompts.push(p); else doc.push({ prompts: [p] });
      } else {
        const prompt = pItem && pItem.prompts[promptIndex];
        if (!prompt) return { error: 'That step moved — reload and try again.' };
        if (hasTools) prompt.recording = { ...(prompt.recording || {}), tools: newToolsArray };
        else if (prompt.recording) delete prompt.recording;
      }
      return { value: doc };
    }
    if (!doc || typeof doc !== 'object') return { error: 'Could not parse the recording.' };
    if (!Array.isArray(doc.trail)) doc.trail = [];
    if (promptIndex == null) {
      if (!hasTools) return { noop: true };
      doc.trail.push({ [kindKey]: (stepInfo && stepInfo.text) || '', recording: { [classifier]: newToolsArray } });
    } else {
      const step = doc.trail[promptIndex];
      if (!step) return { error: 'That step moved — reload and try again.' };
      if (hasTools) step.recording = { ...(step.recording || {}), [classifier]: newToolsArray };
      else if (step.recording) { delete step.recording[classifier]; if (!Object.keys(step.recording).length) delete step.recording; }
    }
    return { value: doc };
  }

  const api = {
    recordYamlValue, parseRecordStepTools, trailheadRunTools,
    buildRecordedTrailYaml, buildRunnableToolYaml, buildToolListRunYaml, buildTrailheadRunYaml,
    buildPromptTrailYaml, buildBlazeYaml, mergeBlazeYaml,
    normalizeTrailDoc, applyRecordingEdit,
  };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (typeof window !== 'undefined') window.TrailYamlBuild = api;
})();
