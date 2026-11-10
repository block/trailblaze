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

  // Copy a contiguous range of authored steps into a runnable partial trail. The trailhead is
  // intentionally omitted: this workflow starts from the device's current screen instead of
  // replaying the test from the beginning. Config (target, devices, driver, memory, etc.) and each
  // selected step's full recording/extra data are preserved. Pure so both the saved-trail UI and
  // tests agree on exactly what partial execution means.
  function sliceSteps(model, start, end) {
    if (!model || !Array.isArray(model.steps) || !model.steps.length) return null;
    var lo = Math.max(0, Math.min(Number(start), Number(end)));
    var hi = Math.min(model.steps.length - 1, Math.max(Number(start), Number(end)));
    if (!Number.isFinite(lo) || !Number.isFinite(hi) || lo > hi) return null;
    var config = Object.assign({}, model.config || {});
    var originalTitle = config.title || 'Trail';
    config.title = 'Partial: ' + originalTitle + ' (' + (lo + 1) + (lo === hi ? '' : '–' + (hi + 1)) + ')';
    return {
      config: config,
      platforms: (model.platforms || []).slice(),
      trailhead: null,
      steps: model.steps.slice(lo, hi + 1).map(function (s) {
        return Object.assign({}, s, {
          recording: Object.assign({}, s.recording || {}),
          extra: Object.assign({}, s.extra || {}),
        });
      }),
    };
  }

  // A device classifier such as `android-phone` belongs to the `android` recording family. The
  // matrix shows concrete runnable legs, while a family recording supplies the fallback for each
  // compatible leg. Keeping this resolution pure means the UI can collapse family columns without
  // rewriting or losing the authored YAML.
  function classifierFamily(key) {
    var clean = String(key || '').trim();
    if (!clean) return '';
    return clean.split(/[-_]/)[0].toLowerCase();
  }

  // Mirror TrailblazeClassifierLineage.resolutionChain for the classifier shapes exposed by the
  // browser model: most-specific compound key, progressively broader parents, each bare segment,
  // then the universal `all` recording. The executor uses the same ordering, so a matrix cell never
  // claims a deterministic recording is missing when the runtime would replay it.
  function classifierLineage(key) {
    var clean = String(key || '').trim().toLowerCase();
    if (!clean) return [];
    var parts = clean.split(/[-_]/).filter(Boolean);
    var chain = [];
    var add = function (candidate) {
      if (candidate && chain.indexOf(candidate) < 0 && candidate !== 'all') chain.push(candidate);
    };
    add(clean);
    for (var end = parts.length - 1; end > 0; end -= 1) add(parts.slice(0, end).join('-'));
    parts.slice().reverse().forEach(add);
    chain.push('all');
    return chain;
  }

  function recordingForLeg(recording, leg) {
    var rec = recording || {};
    var lineage = classifierLineage(leg);
    for (var i = 0; i < lineage.length; i += 1) {
      var sourceKey = lineage[i];
      if (Object.prototype.hasOwnProperty.call(rec, sourceKey)) {
        return { tools: rec[sourceKey], sourceKey: sourceKey, exact: i === 0, explicitNoop: Array.isArray(rec[sourceKey]) && rec[sourceKey].length === 0 };
      }
    }
    return { tools: undefined, sourceKey: null, exact: false, explicitNoop: false };
  }

  // Group one row's visible legs into the cells a grid should draw. Adjacent legs that resolve to the
  // SAME authored key are one cell: `android:` covering android-phone and android-tablet is a single
  // entry in the file, so drawing it once per leg repeats identical content and reads as two
  // independent recordings. Merging on the resolved key (not on deep-equal tools) keeps the cell
  // honest - two legs that each authored the same calls stay separate, because they are separate
  // entries and editing one must not touch the other. A leg with nothing recorded never merges, so
  // every unrecorded leg keeps its own cell to record into.
  //
  // `allLegs` is every leg of the trail, which is wider than `columns` when the declared order
  // separates same-family legs or when a leg sits past the visible cap. It decides whether a merge
  // is allowed at all: see below.
  function legCells(recording, columns, allLegs) {
    var legs = columns || [];
    var cellFor = function (leg) {
      var resolved = recordingForLeg(recording, leg);
      return {
        legs: [leg],
        sourceKey: resolved.sourceKey,
        tools: resolved.tools,
        exact: resolved.exact,
        explicitNoop: resolved.explicitNoop,
      };
    };
    var consumers = {};
    ((allLegs && allLegs.length ? allLegs : legs)).forEach(function (leg) {
      var key = recordingForLeg(recording, leg).sourceKey;
      if (key) consumers[key] = (consumers[key] || 0) + 1;
    });
    var runs = [];
    legs.forEach(function (leg) {
      var cell = cellFor(leg);
      var prev = runs[runs.length - 1];
      if (prev && cell.sourceKey && prev.sourceKey === cell.sourceKey) {
        prev.legs.push(leg);
        // `exact` belongs to the whole run: one fallback consumer means the cell shows a broader
        // entry than its own columns name, which is exactly what the "from <key>" label reports.
        prev.exact = prev.exact && cell.exact;
        return;
      }
      runs.push(cell);
    });
    // A merged cell speaks for the authored entry it edits, so it may only merge when it covers
    // EVERY leg resolving to that entry. A consumer it cannot show - a same-family leg the declared
    // column order separated, or one past the visible-column cap - would otherwise be changed by an
    // edit with nothing on screen to reveal it. Those legs keep one cell each, which writes a
    // per-leg override and labels itself as one.
    return runs.reduce(function (out, cell) {
      var incomplete = cell.legs.length > 1 && consumers[cell.sourceKey] > cell.legs.length;
      return out.concat(incomplete ? cell.legs.map(cellFor) : [cell]);
    }, []);
  }

  // Build the visible logical-leg columns. Declared device keys are authoritative and retain their
  // order. Recording-only concrete classifiers follow. A generic family remains visible only when
  // the trail has no concrete leg in that family. The UI deliberately caps the surface at six legs
  // and returns every omitted key as explicit overflow instead of silently dropping it.
  function logicalLegs(model, maxVisible) {
    var max = Number.isFinite(maxVisible) && maxVisible > 0 ? Math.floor(maxVisible) : 6;
    var declared = Object.keys(((model || {}).config || {}).devices || {});
    var declaredSet = {};
    var seen = [];
    var add = function (p) { if (p && seen.indexOf(p) < 0) seen.push(p); };
    declared.forEach(function (p) { declaredSet[p] = true; add(p); });
    ((model || {}).platforms || []).forEach(add);
    var invalid = seen.filter(function (p) { return !/^[A-Za-z0-9][A-Za-z0-9._-]*$/.test(p); });
    var valid = seen.filter(function (p) { return invalid.indexOf(p) < 0; });
    var hasConcrete = {};
    valid.forEach(function (p) {
      var family = classifierFamily(p);
      if (family && p.toLowerCase() !== family) hasConcrete[family] = true;
    });
    var candidates = valid.filter(function (p) {
      // Every configured device is an independent runnable leg, even when one configured key is
      // also the family fallback for another (for example `ios` and `ios-iphone`). Only collapse a
      // generic family that came from recording data alone.
      if (declaredSet[p]) return true;
      var family = classifierFamily(p);
      return p.toLowerCase() !== family || !hasConcrete[family];
    });
    var columns = candidates.slice(0, max);
    var overflow = candidates.slice(max);
    var warnings = [];
    if (invalid.length) warnings.push('Unsupported device classifier' + (invalid.length === 1 ? '' : 's') + ': ' + invalid.join(', '));
    if (overflow.length) warnings.push(overflow.length + ' device leg' + (overflow.length === 1 ? ' is' : 's are') + ' hidden; this view supports ' + max + '.');
    return { columns: columns, overflow: overflow, warnings: warnings };
  }

  // Remove one visible device leg without deleting a broader recording that another visible leg
  // still reuses. A fallback source is removed only when the deleted leg was its final consumer;
  // otherwise the remaining leg keeps the shared recording and the generic source stays collapsed.
  function removeLogicalLeg(model, key) {
    if (!model || !key) return model;
    var devices = Object.assign({}, ((model.config || {}).devices || {}));
    delete devices[key];
    var otherLegs = logicalLegs(model, Number.MAX_SAFE_INTEGER).columns.filter(function (leg) { return leg !== key; });
    var stripRow = function (row) {
      if (!row) return row;
      var recording = Object.assign({}, row.recording || {});
      var source = recordingForLeg(recording, key).sourceKey;
      var shared = source && otherLegs.some(function (leg) { return recordingForLeg(recording, leg).sourceKey === source; });
      delete recording[key];
      if (source && shared) recording[source] = row.recording[source];
      else if (source) delete recording[source];
      return Object.assign({}, row, { recording: recording });
    };
    var trailhead = stripRow(model.trailhead);
    var steps = (model.steps || []).map(stripRow);
    var retained = {};
    Object.keys(devices).forEach(function (p) { retained[p] = true; });
    var retainRecordings = function (row) { Object.keys((row && row.recording) || {}).forEach(function (p) { retained[p] = true; }); };
    retainRecordings(trailhead);
    steps.forEach(retainRecordings);
    return Object.assign({}, model, {
      platforms: (model.platforms || []).filter(function (p) { return retained[p]; }),
      config: Object.assign({}, model.config || {}, { devices: devices }),
      trailhead: trailhead,
      steps: steps,
    });
  }

  // ── config labels ──
  // `config.labels` and `config.generatedLabels` are written one flow mapping per line, fields in the
  // order type/name/when/except/survey/reasoning — the form the CLI's emitter writes (TrailLabels.renderBlock in
  // trailblaze-trail-format) and `trailblaze check` requires of generatedLabels. js-yaml would expand
  // each entry into a block mapping, so a save from the editor would fail the check. Keep the quoting
  // rule here identical to TrailLabels.flowScalar.
  var LABEL_KEYS = ['labels', 'generatedLabels'];
  var LABEL_FIELDS = ['type', 'name', 'when', 'except', 'survey', 'reasoning'];
  // Fields holding a list of strings (the devices a label holds on), written `[a, b]`.
  var LABEL_LIST_FIELDS = ['when', 'except'];
  var PLAIN_SAFE = /^[A-Za-z0-9_./()+](?:[A-Za-z0-9 _./@()+'-]*[A-Za-z0-9_./@()+'-])?$/;
  var RESERVED_PLAIN = ['null', '~', 'true', 'false', 'yes', 'no', 'on', 'off', 'y', 'n'];
  var NUMBER_LIKE = /^(?:[0-9]|[+.][0-9]|\.(?:inf|nan)$)/i;

  function labelScalar(value) {
    var v = String(value);
    if (PLAIN_SAFE.test(v) && RESERVED_PLAIN.indexOf(v.toLowerCase()) < 0 && !NUMBER_LIKE.test(v)) return v;
    var out = '"';
    for (var i = 0; i < v.length; i++) {
      var ch = v.charAt(i);
      var code = v.charCodeAt(i);
      if (ch === '\\') out += '\\\\';
      else if (ch === '"') out += '\\"';
      else if (ch === '\n') out += '\\n';
      else if (ch === '\t') out += '\\t';
      else if (ch === '\r') out += '\\r';
      else if (code < 0x20 || (code >= 0x7f && code <= 0x9f) || code === 0x2028 || code === 0x2029 || code === 0xfeff) {
        out += '\\u' + ('000' + code.toString(16)).slice(-4);
      } else out += ch;
    }
    return out + '"';
  }

  // One label as `{ type: journey, name: checkout }` or `{ type: t, name: n, when: [ios] }`; absent
  // optional fields are left out.
  function renderLabelEntry(label) {
    var parts = [];
    LABEL_FIELDS.forEach(function (f) {
      if (label[f] == null) return;
      var value = LABEL_LIST_FIELDS.indexOf(f) >= 0
        ? '[' + label[f].map(labelScalar).join(', ') + ']'
        : labelScalar(label[f]);
      parts.push(f + ': ' + value);
    });
    return '{ ' + parts.join(', ') + ' }';
  }

  function isStringList(v) {
    return Array.isArray(v) && v.every(function (x) { return typeof x === 'string'; });
  }

  // A label list the one-line form can write: mappings holding only known fields, each a string (a
  // list of strings for `when`/`except`). Anything else (a Date or number js-yaml typed, a bare
  // string entry, a field this editor doesn't know) is left to the dumper untouched, so nothing is
  // dropped.
  function isWritableLabelList(v) {
    return Array.isArray(v) && v.every(function (l) {
      return l && typeof l === 'object' && !Array.isArray(l) &&
        Object.keys(l).every(function (f) { return LABEL_FIELDS.indexOf(f) >= 0; }) &&
        LABEL_FIELDS.every(function (f) {
          if (l[f] == null) return true;
          return LABEL_LIST_FIELDS.indexOf(f) >= 0 ? isStringList(l[f]) : typeof l[f] === 'string';
        });
    });
  }

  // Dump a unified doc with [dump] (js-yaml in the browser), then write each config label list in the
  // one-line form. Falls back to [dump] alone when the dumper's output has no line to splice into
  // (a flow-style dumper), so a placeholder can never reach the file.
  function dumpUnifiedDoc(doc, dump) {
    var config = doc && doc.config;
    var lists = {};
    var out = doc;
    if (config && typeof config === 'object') {
      var patched = Object.assign({}, config);
      LABEL_KEYS.forEach(function (k) {
        if (isWritableLabelList(config[k])) {
          lists[k] = config[k];
          patched[k] = 'TRAIL_LABELS_PLACEHOLDER_' + k;
        }
      });
      if (Object.keys(lists).length) out = Object.assign({}, doc, { config: patched });
    }
    if (out === doc) return dump(doc);
    var text = dump(out);
    var spliced = Object.keys(lists).every(function (k) {
      var line = new RegExp('^( *)' + k + ": ['\"]?TRAIL_LABELS_PLACEHOLDER_" + k + "['\"]?$", 'm');
      if (!line.test(text)) return false;
      text = text.replace(line, function (_, indent) {
        if (!lists[k].length) return indent + k + ': []';
        return indent + k + ':\n' + lists[k].map(function (l) { return indent + '  - ' + renderLabelEntry(l); }).join('\n');
      });
      return true;
    });
    return spliced ? text : dump(doc);
  }

  // Load a trail with [jsyaml], keeping config label fields exactly as written. js-yaml's default
  // schema types an unquoted `001` as 1 and `2026-11-03` as a Date, where the CLI reads both as the
  // strings they are; the label lists are re-read with the failsafe schema, which types nothing.
  function loadUnifiedDoc(yaml, jsyaml) {
    var doc = jsyaml.load(yaml);
    if (!jsyaml.FAILSAFE_SCHEMA) return doc;
    var raw;
    try { raw = jsyaml.load(yaml, { schema: jsyaml.FAILSAFE_SCHEMA }); } catch (e) { return doc; }
    return withSourceLabels(doc, raw);
  }

  // [doc] with its config label fields spelled as in [raw], the same file loaded with every scalar a
  // string. Field by field: a field the typed load read as null stays null (the failsafe load spells
  // an explicit `null` as the string "null"), and only a field it typed as something other than a
  // string takes the raw spelling. A list whose shape the two loads disagree on is left as typed.
  function withSourceLabels(doc, raw) {
    var config = doc && !Array.isArray(doc) && doc.config;
    var rawConfig = raw && !Array.isArray(raw) && raw.config;
    if (!config || typeof config !== 'object' || !rawConfig || typeof rawConfig !== 'object') return doc;
    var patched = null;
    LABEL_KEYS.forEach(function (k) {
      var typed = config[k];
      var source = rawConfig[k];
      if (!Array.isArray(typed) || !Array.isArray(source) || typed.length !== source.length) return;
      var changed = false;
      var restored = typed.map(function (entry, i) {
        var rawEntry = source[i];
        if (!entry || typeof entry !== 'object' || Array.isArray(entry) || !rawEntry || typeof rawEntry !== 'object') return entry;
        var out = entry;
        LABEL_FIELDS.forEach(function (f) {
          var v = entry[f];
          var r = rawEntry[f];
          var retype = LABEL_LIST_FIELDS.indexOf(f) >= 0
            ? Array.isArray(v) && !isStringList(v) && isStringList(r) && r.length === v.length
            : v != null && typeof v !== 'string' && typeof r === 'string';
          if (retype) {
            if (out === entry) out = Object.assign({}, entry);
            out[f] = r;
            changed = true;
          }
        });
        return out;
      });
      if (changed) {
        patched = patched || Object.assign({}, config);
        patched[k] = restored;
      }
    });
    return patched ? Object.assign({}, doc, { config: patched }) : doc;
  }

  var api = {
    dumpUnifiedDoc: dumpUnifiedDoc,
    loadUnifiedDoc: loadUnifiedDoc,
    withSourceLabels: withSourceLabels,
    renderLabelEntry: renderLabelEntry,
    recToTools: recToTools,
    toolsToRec: toolsToRec,
    unifiedDocToMatrix: unifiedDocToMatrix,
    matrixToUnifiedDoc: matrixToUnifiedDoc,
    sliceSteps: sliceSteps,
    classifierFamily: classifierFamily,
    classifierLineage: classifierLineage,
    recordingForLeg: recordingForLeg,
    legCells: legCells,
    logicalLegs: logicalLegs,
    removeLogicalLeg: removeLogicalLeg,
  };

  if (typeof module !== 'undefined' && module.exports) module.exports = api; // bun test / CommonJS
  if (typeof window !== 'undefined') window.TM = api;                        // browser classic script
})();
