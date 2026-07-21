// The step-matrix model for a UNIFIED trail — the single-file format (`config:` + `trail:` mapping,
// per-platform recordings inline under each step's `recording:`) that trails are moving toward. This
// layer is PURE (parsed-object in, parsed-object out — js-yaml stays at the tsx edges), which is what
// makes it unit-testable with `bun test` and lets the editor round-trip through the one file. See the
// sibling `trail-model.test.ts`.
//
// StepMatrix { config, platforms: string[], trailhead: Step|null, steps: Step[] }
// Step       { kind: 'trailhead'|'step'|'verify', text, recording: { [platform]: Tool[] }, extra }
//              A platform key PRESENT with an empty list is an explicit no-op for that device class
//              (`<classifier>: []` on disk — deliberately distinct from an ABSENT key, which can fall
//              back to a broader family recording at resolution time; see UnifiedTrailStep's kdoc).
// Tool       { name, body }   // body = the tool's args, whatever its YAML value (object, scalar,
//                             //        list). `null`/empty-object args normalize to `{}`.
//
// Dual-exported like app/editor/lsp-convert.js: `window.TM` for the classic-script browser load,
// `module.exports` for bun test. No external dependencies.
(function () {
  // Preserve a tool's args faithfully; only `null`/`undefined` and the empty object collapse to `{}`
  // (the canonical "no args" form the runtime expects). A scalar (`wait: 500`), list, or non-empty
  // object body is kept as-is so a parse→serialize round-trip never mangles it.
  function normBody(b) {
    if (b == null) return {};
    if (typeof b === 'object' && !Array.isArray(b) && Object.keys(b).length === 0) return {};
    return b;
  }

  // A platform's recording is a `{ tool: body }` map (a single-tool trailhead), a `[{ tool: body }]`
  // list (a trail step), or a legacy `recording.tools` list — all normalize to ordered [{name, body}].
  function recToTools(rec) {
    var out = [];
    var push = function (e) {
      if (e && typeof e === 'object' && !Array.isArray(e)) {
        var n = Object.keys(e)[0];
        if (n) out.push({ name: n, body: normBody(e[n]) });
      }
    };
    if (Array.isArray(rec)) rec.forEach(push);
    else if (rec && typeof rec === 'object') push(rec);
    return out;
  }

  // Serialize one platform's [{name, body}] back to the on-disk shape: a bare map for a single-tool
  // trailhead recording (trailheadForm), a list otherwise. Returns null for an empty list — the caller
  // decides between omitting the platform (trailhead) and emitting an explicit `[]` no-op (step).
  function toolsToRec(tools, trailheadForm) {
    var arr = (tools || []).map(function (t) { return { [t.name]: normBody(t.body) }; });
    if (!arr.length) return null;
    return trailheadForm && arr.length === 1 ? arr[0] : arr;
  }

  var STEP_KEYS = { step: 1, verify: 1, prompt: 1, recording: 1 };

  // Parsed unified doc (a `config`/`trailhead`/`trail` mapping) -> StepMatrix, or null when the doc
  // isn't that shape (so the caller falls back to the legacy path). Recordings bind to their step BY
  // IDENTITY (they live under the step), which is the whole point of the single-file format. Unknown
  // per-step keys are preserved in `extra` so a round-trip never drops them.
  function unifiedDocToMatrix(doc) {
    if (!doc || typeof doc !== 'object' || Array.isArray(doc)) return null;
    if (!Array.isArray(doc.trail) && !doc.trailhead) return null;
    var config = doc.config && typeof doc.config === 'object' && !Array.isArray(doc.config) ? doc.config : {};
    var platforms = [];
    var see = function (p) { if (p && platforms.indexOf(p) < 0) platforms.push(p); };
    Object.keys((config.devices) || {}).forEach(see);
    var mkStep = function (raw, isTrailhead) {
      var kind = isTrailhead ? 'trailhead' : ('verify' in raw ? 'verify' : 'step');
      var text = raw.step != null ? raw.step : raw.verify != null ? raw.verify : raw.prompt != null ? raw.prompt : '';
      var rawRec = raw.recording && typeof raw.recording === 'object' && !Array.isArray(raw.recording) ? raw.recording : {};
      var recording = {};
      Object.keys(rawRec).forEach(function (p) { see(p); recording[p] = recToTools(rawRec[p]); });
      var extra = {};
      Object.keys(raw).forEach(function (k) { if (!STEP_KEYS[k]) extra[k] = raw[k]; });
      return { kind: kind, text: String(text), recording: recording, extra: extra };
    };
    var trailhead = doc.trailhead && typeof doc.trailhead === 'object' && !Array.isArray(doc.trailhead)
      ? mkStep(doc.trailhead, true) : null;
    var steps = (Array.isArray(doc.trail) ? doc.trail : [])
      .filter(function (s) { return s && typeof s === 'object' && !Array.isArray(s); })
      .map(function (s) { return mkStep(s, false); });
    return { config: config, platforms: platforms, trailhead: trailhead, steps: steps };
  }

  // StepMatrix -> a plain unified doc object (the caller dumps it to YAML). Emits `config` verbatim,
  // then `trailhead` (when present) and `trail`. Inverse of unifiedDocToMatrix over the config/
  // trailhead/trail surface (the single-file format's shape).
  function matrixToUnifiedDoc(model) {
    var emit = function (s, isTrailhead) {
      var o = {};
      o[isTrailhead || s.kind !== 'verify' ? 'step' : 'verify'] = s.text;
      Object.assign(o, s.extra || {});
      var rec = {};
      var hasTools = false;
      (model.platforms || []).forEach(function (p) {
        var cell = (s.recording || {})[p];
        if (cell === undefined) return; // never recorded for this platform — omit the key
        var r = toolsToRec(cell, isTrailhead);
        if (r != null) { rec[p] = r; hasTools = true; }
        // An empty cell is the explicit `<classifier>: []` no-op — it must round-trip, because
        // omitting the key changes closest-wins resolution (absence can fall back to a broader
        // family recording like `android:`). A trailhead classifier must be exactly one tool map,
        // so only there does empty mean omit.
        else if (!isTrailhead) rec[p] = [];
      });
      if (Object.keys(rec).length) o.recording = rec;
      // `recordable: false` + a non-empty recording is a contradiction the Kotlin parser hard-rejects
      // (the flag means "always handled by the LLM"). Adding tools in the editor supersedes the flag,
      // so drop it rather than emit a file the runtime can't load. Explicit `[]` no-ops don't count.
      if (hasTools && o.recordable === false) delete o.recordable;
      return o;
    };
    var doc = { config: model.config || {} };
    if (model.trailhead) doc.trailhead = emit(model.trailhead, true);
    doc.trail = (model.steps || []).map(function (s) { return emit(s, false); });
    return doc;
  }

  var api = {
    recToTools: recToTools,
    toolsToRec: toolsToRec,
    unifiedDocToMatrix: unifiedDocToMatrix,
    matrixToUnifiedDoc: matrixToUnifiedDoc,
  };

  if (typeof module !== 'undefined' && module.exports) module.exports = api; // bun test / CommonJS
  if (typeof window !== 'undefined') window.TM = api;                        // browser classic script
})();
