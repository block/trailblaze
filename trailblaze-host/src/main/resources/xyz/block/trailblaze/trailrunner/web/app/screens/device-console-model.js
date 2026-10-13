// Pure logic behind the Devices screen: which gesture a pointer press-and-release on the mirror
// means, where it lands in device pixels, which hardware keys a platform offers, and the trail YAML
// a filled-in tool form becomes. Kept out of the React screen so each rule has a test - a wrong
// one sends the device somewhere the user didn't point.
//
// Dual-exported like follow-run-model.js: `window.DeviceConsoleModel` for the classic-script app
// and `module.exports` for Bun tests.
(function (root, factory) {
  var api = factory();
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.DeviceConsoleModel = api;
})(typeof window !== 'undefined' ? window : null, function () {
  // A press that travels less than this share of the device width is a tap or long press, not a
  // swipe. Matches the Record screen, so a gesture means the same thing on both.
  var SWIPE_FRACTION = 0.06;
  // Held longer than this without moving = long press.
  var LONG_PRESS_MS = 550;
  var NUMERIC = /^(int|long|float|double|number)$/i;
  var BOOLEAN = /^boolean$/i;
  // Parameters whose value is a mapping or a list rather than one scalar.
  var COMPOSITE = /^(object|array|list|map|any)$/i;

  function deviceIdFor(device) {
    if (!device) return null;
    return { instanceId: device.id, trailblazeDevicePlatform: String(device.platform || '').toUpperCase() };
  }

  // Map a client point inside `rect` (the mirror's on-screen box) to device pixels, clamped to the
  // screen. Null when the mirror has no size yet.
  function toDevicePoint(rect, clientX, clientY, deviceWidth, deviceHeight) {
    if (!rect || rect.width <= 0 || rect.height <= 0 || !deviceWidth || !deviceHeight) return null;
    var x = Math.round(((clientX - rect.left) / rect.width) * deviceWidth);
    var y = Math.round(((clientY - rect.top) / rect.height) * deviceHeight);
    return {
      x: Math.max(0, Math.min(deviceWidth - 1, x)),
      y: Math.max(0, Math.min(deviceHeight - 1, y)),
    };
  }

  // The DeviceInteraction a press at `down` released at `up` after `heldMs` stands for.
  function gestureFor(down, up, heldMs, deviceWidth) {
    var dist = Math.hypot(up.x - down.x, up.y - down.y);
    if (dist > deviceWidth * SWIPE_FRACTION) {
      return {
        type: 'swipe', startX: down.x, startY: down.y, endX: up.x, endY: up.y,
        durationMs: Math.max(120, Math.min(1200, Math.round(heldMs))),
      };
    }
    if (heldMs > LONG_PRESS_MS) return { type: 'longPress', x: down.x, y: down.y };
    return { type: 'tap', x: down.x, y: down.y };
  }

  // Hardware keys worth a button per platform: only keys the platform's live connection can press.
  // An iOS simulator has no Back, and its connection has no Home either. Web needs no more: the URL
  // bar and typed text cover it.
  function keysFor(platform) {
    var p = String(platform || '').toLowerCase();
    if (p === 'android') return ['Back', 'Home', 'Enter'];
    return ['Enter'];
  }

  // Which form control a tool parameter gets.
  function inputKindFor(param) {
    if (param && param.validValues && param.validValues.length) return 'select';
    var type = String((param && param.type) || '');
    if (BOOLEAN.test(type)) return 'boolean';
    if (NUMERIC.test(type)) return 'number';
    return 'text';
  }

  // A YAML scalar for a form value: booleans and numbers stay bare when the parameter is typed that
  // way and the value really is one; everything else is quoted so `"123"` or `"yes"` stays a string.
  function yamlScalar(value, type) {
    var v = String(value == null ? '' : value);
    // An object or list parameter typed as JSON is written as that JSON, which YAML reads as the
    // mapping or list itself; anything else stays a string, so the daemon reports what is wrong.
    if (COMPOSITE.test(String(type || '')) && /^\s*[[{]/.test(v)) {
      try { return JSON.stringify(JSON.parse(v)); } catch (_) { /* not JSON: quoted below */ }
    }
    if (BOOLEAN.test(String(type || '')) && /^(true|false)$/i.test(v)) return v.toLowerCase();
    if (NUMERIC.test(String(type || '')) && v.trim() !== '' && Number.isFinite(Number(v))) return v.trim();
    return JSON.stringify(v);
  }

  // Parameters that only mean something to the LLM calling the tool; a person filling in the form
  // has no use for them.
  var LLM_ONLY_PARAMS = { reasoning: true };

  // The tool's parameters in form order, required first, each tagged with whether it is optional.
  function paramsOf(tool) {
    if (!tool) return [];
    var required = (tool.requiredParameters || []).map(function (p) { return Object.assign({}, p, { optional: false }); });
    var optional = (tool.optionalParameters || [])
      .filter(function (p) { return !LLM_ONLY_PARAMS[p.name]; })
      .map(function (p) { return Object.assign({}, p, { optional: true }); });
    return required.concat(optional);
  }

  // Whether a parameter applies given the other values (a `visibleWhen` names a sibling and the
  // values that switch it on). Hidden parameters are neither shown nor written.
  function isVisible(param, values) {
    var w = param && param.visibleWhen;
    if (!w || !w.parameterName) return true;
    var current = values ? values[w.parameterName] : undefined;
    return (w.values || []).indexOf(String(current == null ? '' : current)) >= 0;
  }

  // The one-step trail that calls `tool` with `values` (name → raw form string) on a `platform`
  // device: a unified trail whose single step carries the call as its recording, keyed by the
  // device classifier (the lowercased platform) so it replays without the LLM. Optional params left
  // blank are omitted; required ones are always written so the daemon reports what is missing.
  // Dotted names (`selector.textRegex`) nest under their parent, the way the tool decodes them.
  function toolYaml(tool, values, platform) {
    if (!tool || !tool.name) return '';
    var classifier = String(platform || '').toLowerCase() || 'web';
    var vals = values || {};
    var entries = paramsOf(tool).filter(function (p) {
      if (!isVisible(p, vals)) return false;
      var v = vals[p.name];
      return !p.optional || (v != null && String(v).trim() !== '');
    });
    var title = JSON.stringify('Run ' + tool.name);
    var lines = ['config:', '  title: ' + title, 'trail:', '  - step: ' + title, '    recording:', '      ' + classifier + ':'];
    var call = '        - ' + tool.name + ':';
    var arg = '            ';
    if (entries.length === 0) {
      lines.push(call + ' {}');
      return lines.join('\n') + '\n';
    }
    lines.push(call);
    var nested = {};
    entries.forEach(function (p) {
      var dot = p.name.indexOf('.');
      if (dot < 0) {
        lines.push(arg + p.name + ': ' + yamlScalar(vals[p.name], p.type));
        return;
      }
      var parent = p.name.slice(0, dot);
      if (nested[parent]) return;
      nested[parent] = true;
      lines.push(arg + parent + ':');
      entries.forEach(function (child) {
        if (child.name.indexOf(parent + '.') !== 0) return;
        lines.push(arg + '  ' + child.name.slice(parent.length + 1) + ': ' + yamlScalar(vals[child.name], child.type));
      });
    });
    return lines.join('\n') + '\n';
  }

  // Starting form values: the first allowed value for an enum, `false` for a required boolean,
  // blank otherwise.
  function initialValues(tool) {
    var out = {};
    paramsOf(tool).forEach(function (p) {
      var kind = inputKindFor(p);
      if (kind === 'select' && !p.optional) out[p.name] = p.validValues[0];
      else if (kind === 'boolean' && !p.optional) out[p.name] = 'false';
      else out[p.name] = '';
    });
    return out;
  }

  return {
    SWIPE_FRACTION: SWIPE_FRACTION,
    LONG_PRESS_MS: LONG_PRESS_MS,
    deviceIdFor: deviceIdFor,
    toDevicePoint: toDevicePoint,
    gestureFor: gestureFor,
    keysFor: keysFor,
    inputKindFor: inputKindFor,
    yamlScalar: yamlScalar,
    paramsOf: paramsOf,
    isVisible: isVisible,
    toolYaml: toolYaml,
    initialValues: initialValues,
  };
});
