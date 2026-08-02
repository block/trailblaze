// Session-zip → run-report assembly for the zip-report screen: everything needed to turn a
// downloaded session archive (the per-session zip CI publishes, or a `trailblaze report` export)
// into the inputs run-report-core.js renders — without a daemon, from bytes alone.
//
// Three layers, all DOM-free and unit-tested in the sibling zip-report-core.test.ts:
//
//   1. A minimal ZIP reader: central-directory parse + per-entry decompression. Plain (non-ZIP64)
//      archives only — session zips are a few MB. Inflate is injectable so bun tests use
//      node:zlib while the browser uses DecompressionStream('deflate-raw').
//   2. The LogsRepo read slice, ported from the Kotlin canonical source
//      (trailblaze-report/…/utils/LogsRepo.kt): which entries are session logs (hex-prefixed
//      .json, excluding capture_metadata.json), which are images (png/jpg/jpeg/webp), timestamp
//      log ordering, and grouping a multi-session archive by its top-level directories.
//   3. The run `meta` derivation, ported from the Kotlin canonical pair
//      SessionInfo.getSessionInfo() (trailblaze-models/…/logs/model/SessionInfo.kt) and
//      RunReportGenerator.sessionMetaJson() — display-name priority, status badge label,
//      failure reason, duration/ranAt formatting. Field names match the wire format the
//      generated trailrunner-dtos.ts types describe.
//   4. The full assembly (buildReportHtmlFromZipBytes): bytes → the exact report HTML both
//      report-from-zip homes show. This is the one pipeline the in-app ?zip= screen and the
//      standalone static edition share, so they can't drift; the run-report-core renderer it
//      composes with is injected (globals in the browser, the required module in tests).
//
// All request-free: callers hand in the zip bytes (however they fetched them).
(function () {
  'use strict';

  // ---- Layer 1: minimal ZIP reader ------------------------------------------------------------

  var EOCD_SIG = 0x06054b50; // end of central directory
  var CEN_SIG = 0x02014b50;  // central directory file header
  var LOC_SIG = 0x04034b50;  // local file header
  var utf8 = new TextDecoder('utf-8');

  // Locate the End Of Central Directory record: scan back from the tail across the maximum
  // possible trailing comment (64KB).
  function findEndOfCentralDirectory(bytes) {
    var view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    var earliest = Math.max(0, bytes.length - 22 - 0xffff);
    for (var offset = bytes.length - 22; offset >= earliest; offset--) {
      if (view.getUint32(offset, true) === EOCD_SIG) return offset;
    }
    throw new Error('Not a ZIP archive (no end-of-central-directory record found).');
  }

  // Parse the central directory into entry records. Directory entries (trailing '/') are skipped —
  // only files matter to session assembly.
  function parseZipEntries(bytes) {
    var view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    var eocd = findEndOfCentralDirectory(bytes);
    var count = view.getUint16(eocd + 10, true);
    var cenOffset = view.getUint32(eocd + 16, true);
    if (count === 0xffff || cenOffset === 0xffffffff) {
      throw new Error('ZIP64 archives are not supported (session zips are plain ZIP).');
    }
    var entries = [];
    var p = cenOffset;
    for (var i = 0; i < count; i++) {
      if (view.getUint32(p, true) !== CEN_SIG) {
        throw new Error('Corrupt ZIP: bad central-directory signature at entry ' + i + '.');
      }
      var flags = view.getUint16(p + 8, true);
      var method = view.getUint16(p + 10, true);
      var compressedSize = view.getUint32(p + 20, true);
      var uncompressedSize = view.getUint32(p + 24, true);
      var nameLength = view.getUint16(p + 28, true);
      var extraLength = view.getUint16(p + 30, true);
      var commentLength = view.getUint16(p + 32, true);
      var localHeaderOffset = view.getUint32(p + 42, true);
      var name = utf8.decode(bytes.subarray(p + 46, p + 46 + nameLength));
      if (compressedSize === 0xffffffff || uncompressedSize === 0xffffffff || localHeaderOffset === 0xffffffff) {
        throw new Error('ZIP64 archives are not supported (session zips are plain ZIP).');
      }
      if (!name.endsWith('/')) {
        entries.push({
          name: name,
          method: method,
          flags: flags,
          compressedSize: compressedSize,
          uncompressedSize: uncompressedSize,
          localHeaderOffset: localHeaderOffset,
        });
      }
      p += 46 + nameLength + extraLength + commentLength;
    }
    return entries;
  }

  // Default browser inflate. bun tests inject node:zlib's inflateRawSync instead.
  function inflateRawWithDecompressionStream(compressed) {
    var stream = new Blob([compressed]).stream().pipeThrough(new DecompressionStream('deflate-raw'));
    return new Response(stream).arrayBuffer().then(function (buffer) { return new Uint8Array(buffer); });
  }

  // Read + decompress one entry's bytes. The data offset comes from the LOCAL header (its
  // name/extra lengths can legitimately differ from the central directory's).
  function readZipEntry(bytes, entry, inflateRaw) {
    var view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    var p = entry.localHeaderOffset;
    if (view.getUint32(p, true) !== LOC_SIG) {
      return Promise.reject(new Error('Corrupt ZIP: bad local-header signature for "' + entry.name + '".'));
    }
    if (entry.flags & 0x1) {
      return Promise.reject(new Error('Encrypted ZIP entries are not supported ("' + entry.name + '").'));
    }
    var nameLength = view.getUint16(p + 26, true);
    var extraLength = view.getUint16(p + 28, true);
    var dataStart = p + 30 + nameLength + extraLength;
    var raw = bytes.subarray(dataStart, dataStart + entry.compressedSize);
    if (entry.method === 0) return Promise.resolve(raw);
    if (entry.method === 8) return Promise.resolve((inflateRaw || inflateRawWithDecompressionStream)(raw));
    return Promise.reject(new Error('Unsupported ZIP compression method ' + entry.method + ' for "' + entry.name + '".'));
  }

  // ---- Layer 2: LogsRepo read slice (Kotlin: trailblaze-report LogsRepo.kt) -------------------

  var RECORDING_YAML_NAME = 'recording.trail.yaml';
  // TrailblazeImageFormat extensions + "jpeg", per LogsRepo.getImagesForSession.
  var IMAGE_EXTENSIONS = { png: 'image/png', jpg: 'image/jpeg', jpeg: 'image/jpeg', webp: 'image/webp' };

  // LogsRepo.readLogFilesFromDisk: `extension == "json" && name.first().isHexDigit() && name != "capture_metadata.json"`.
  function isSessionLogFileName(name) {
    return name.endsWith('.json') && name !== 'capture_metadata.json' && /^[0-9a-fA-F]/.test(name);
  }

  function isImageFileName(name) {
    var dot = name.lastIndexOf('.');
    return dot > 0 && Object.prototype.hasOwnProperty.call(IMAGE_EXTENSIONS, name.slice(dot + 1).toLowerCase());
  }

  function imageMimeType(name) {
    var dot = name.lastIndexOf('.');
    return IMAGE_EXTENSIONS[name.slice(dot + 1).toLowerCase()] || 'application/octet-stream';
  }

  // Group archive entries by top-level directory — one group per session, mirroring LogsRepo's
  // one-directory-per-session layout. Only files directly inside the session directory count
  // (subdirectories like in-process-scripted-tools/ are tool artifacts, not session files). A
  // flat archive (files at the root, e.g. a zip made from inside the session dir) forms a single
  // group with sessionId '' — the caller falls back to the logs' own `session` field.
  function groupEntriesBySession(entries) {
    var groups = {};
    var order = [];
    entries.forEach(function (entry) {
      var slash = entry.name.indexOf('/');
      var sessionId = slash < 0 ? '' : entry.name.slice(0, slash);
      var fileName = slash < 0 ? entry.name : entry.name.slice(slash + 1);
      if (fileName === '' || fileName.indexOf('/') >= 0) return;
      if (!groups[sessionId]) {
        groups[sessionId] = { sessionId: sessionId, byFileName: {} };
        order.push(sessionId);
      }
      groups[sessionId].byFileName[fileName] = entry;
    });
    return order.map(function (id) { return groups[id]; });
  }

  // Chronological log order (LogsRepo sorts parsed logs by timestamp). Stable sort keeps the
  // numbered-filename feed order for identical millisecond stamps, so sub-millisecond fractions
  // Date.parse truncates can't reorder Kotlin-adjacent logs.
  function sortLogsByTimestamp(logs) {
    return logs
      .map(function (log, index) { return { log: log, index: index, at: Date.parse(log.timestamp) || 0 }; })
      .sort(function (a, b) { return (a.at - b.at) || (a.index - b.index); })
      .map(function (row) { return row.log; });
  }

  // ---- Layer 3: run meta (Kotlin: SessionInfo.kt + RunReportGenerator.sessionMetaJson) --------

  var STATUS_CHANGE_LOG_CLASS = 'xyz.block.trailblaze.logs.client.TrailblazeLog.TrailblazeSessionStatusChangeLog';
  var STATUS_CLASS_PREFIX = 'xyz.block.trailblaze.logs.model.SessionStatus.';
  var MCP_TEST_CLASS_NAME = 'MCP';

  // The status subtype's nesting path after the SessionStatus base — 'Started', 'Ended.Succeeded', …
  function statusKind(status) {
    if (!status || typeof status.class !== 'string' || status.class.indexOf(STATUS_CLASS_PREFIX) !== 0) return 'Unknown';
    return status.class.slice(STATUS_CLASS_PREFIX.length);
  }

  function getSessionStatus(logs) {
    for (var i = logs.length - 1; i >= 0; i--) {
      if (logs[i].class === STATUS_CHANGE_LOG_CLASS) return logs[i].sessionStatus;
    }
    return null; // SessionStatus.Unknown
  }

  function getSessionStartedInfo(logs) {
    for (var i = 0; i < logs.length; i++) {
      if (logs[i].class === STATUS_CHANGE_LOG_CLASS && statusKind(logs[i].sessionStatus) === 'Started') {
        return logs[i].sessionStatus;
      }
    }
    return null;
  }

  // TrailRecordings.shortTrailName: path → trail identity relative to its trails/ root.
  function shortTrailName(trailFilePath) {
    var relative = trailFilePath.replace(/\\/g, '/');
    var marker = relative.lastIndexOf('/trails/');
    if (marker >= 0) relative = relative.slice(marker + '/trails/'.length);
    if (relative.indexOf('trails/') === 0) relative = relative.slice('trails/'.length);
    if (relative.endsWith('/trail.yaml')) return relative.slice(0, -'/trail.yaml'.length);
    if (relative === 'trail.yaml') return relative;
    return relative.replace(/\.trail\.yaml$/, '');
  }

  // SessionInfo.displayName resolution priority (see the Kotlin kdoc): config title → config id →
  // trail path → testClass:testName (with the MCP transport marker suppressed) → sessionId.
  function sessionDisplayName(started, sessionId) {
    var config = (started && started.trailConfig) || null;
    if (config && config.title) return config.title;
    if (config && config.id) return config.id;
    var trailFilePath = started && started.trailFilePath;
    if (trailFilePath && trailFilePath.trim() !== '') return shortTrailName(trailFilePath);
    var testClass = started && started.testClassName;
    var displayTestClass = testClass && testClass.trim().toUpperCase() !== MCP_TEST_CLASS_NAME ? testClass : null;
    var testName = started && started.testMethodName;
    if (testName && testName.trim() !== '') return displayTestClass ? displayTestClass + ':' + testName : testName;
    if (displayTestClass) return displayTestClass;
    return sessionId;
  }

  // RunReportGenerator.statusLabel: badge class the viewer expects.
  function statusLabel(status) {
    switch (statusKind(status)) {
      case 'Ended.Succeeded':
      case 'Ended.SucceededWithSelfHeal':
        return 'passed';
      case 'Ended.Failed':
      case 'Ended.FailedWithSelfHeal':
      case 'Ended.TimeoutReached':
      case 'Ended.MaxCallsLimitReached':
        return 'failed';
      case 'Ended.Cancelled':
        return 'cancelled';
      case 'Started':
        return 'running';
      default:
        return 'unknown';
    }
  }

  // RunReportGenerator.failureReason: the header error banner text.
  function failureReason(status) {
    switch (statusKind(status)) {
      case 'Ended.Failed':
      case 'Ended.FailedWithSelfHeal':
        return status.exceptionMessage || null;
      case 'Ended.Cancelled':
        return status.cancellationMessage || null;
      case 'Ended.TimeoutReached':
        return status.message || null;
      case 'Ended.MaxCallsLimitReached':
        return 'Max LLM calls limit reached (' + status.maxCalls + ') for: ' + status.objectivePrompt;
      default:
        return null;
    }
  }

  // RunReportGenerator.formatDuration.
  function formatDuration(ms) {
    if (ms < 1000) return ms + 'ms';
    if (ms < 60000) return (ms / 1000).toFixed(1) + 's';
    return Math.floor(ms / 60000) + 'm ' + Math.floor((ms % 60000) / 1000) + 's';
  }

  // RunReportGenerator's HUMAN_TS ("yyyy-MM-dd HH:mm:ss", local time).
  function formatRanAt(epochMs) {
    var d = new Date(epochMs);
    var pad = function (n) { return String(n).padStart(2, '0'); };
    return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()) +
      ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes()) + ':' + pad(d.getSeconds());
  }

  // RunReportGenerator.sessionMetaJson, over wire-format logs. `extras` carries the fields the
  // caller sources elsewhere (recordingYaml from the archive, originalYaml from
  // originalYamlFromLogs, generatedAt).
  function buildRunMeta(logs, extras) {
    extras = extras || {};
    var status = getSessionStatus(logs);
    var started = getSessionStartedInfo(logs);
    var sessionId = (logs[0] && logs[0].session) || '';
    var firstAt = logs.length ? Date.parse(logs[0].timestamp) || 0 : 0;
    var lastAt = logs.length ? Date.parse(logs[logs.length - 1].timestamp) || firstAt : 0;

    var meta = {
      title: sessionDisplayName(started, sessionId),
      status: statusLabel(status),
    };
    var config = (started && started.trailConfig) || null;
    if (config && config.target) meta.target = config.target;
    var app = started && started.targetAppInfo;
    if (app) {
      meta.appId = app.appId;
      // "5.58.0.0 (67500009)" — same display rule as the Info tab and sessionMetaJson.
      var build = app.buildNumber || app.versionCode || null;
      var display = app.versionName ? (build ? app.versionName + ' (' + build + ')' : app.versionName) : build;
      if (display) meta.appVersion = display;
    }
    var deviceInfo = started && started.trailblazeDeviceInfo;
    var deviceId = (started && started.trailblazeDeviceId) || (deviceInfo && deviceInfo.trailblazeDeviceId);
    var platform = deviceId && deviceId.trailblazeDevicePlatform;
    if (platform) meta.platform = platform.toLowerCase();
    if (deviceId && deviceId.instanceId) meta.device = deviceId.instanceId;
    if (deviceInfo && deviceInfo.classifiers) {
      var deviceType = deviceInfo.classifiers
        .filter(function (c) { return !platform || c.toLowerCase() !== platform.toLowerCase(); })
        .join(' · ');
      if (deviceType) meta.deviceType = deviceType;
    }
    meta.duration = formatDuration(Math.max(0, lastAt - firstAt));
    if (firstAt) meta.ranAt = formatRanAt(firstAt);
    if (config && config.id) meta.trailId = config.id;
    if (config && config.metadata && Object.keys(config.metadata).length) meta.metadata = config.metadata;
    var trailFilePath = started && started.trailFilePath;
    if (trailFilePath && trailFilePath.trim() !== '') meta.cmd = './trailblaze run ' + trailFilePath;
    var error = failureReason(status);
    if (error) meta.error = error;
    var kind = statusKind(status);
    if (kind === 'Ended.SucceededWithSelfHeal' || kind === 'Ended.FailedWithSelfHeal') meta.selfHeal = true;
    if (extras.recordingYaml != null) meta.recordingYaml = extras.recordingYaml;
    if (extras.originalYaml != null) meta.originalYaml = extras.originalYaml;
    meta.generatedAt = extras.generatedAt || new Date().toLocaleString();
    return meta;
  }

  // ---- Assembly: zip bytes → renderable sessions -----------------------------------------------

  function bytesToBase64(bytes) {
    if (typeof Buffer !== 'undefined' && Buffer.from) return Buffer.from(bytes).toString('base64');
    var binary = '';
    for (var i = 0; i < bytes.length; i += 0x8000) {
      binary += String.fromCharCode.apply(null, bytes.subarray(i, Math.min(i + 0x8000, bytes.length)));
    }
    return btoa(binary);
  }

  // Parse the archive into sessions: chronologically-ordered log records, the image inventory
  // (bytes stay in the zip until sessionImageDataUri pulls one), and the recorded trail YAML.
  // Groups without any log file (e.g. a stray top-level dir) are skipped.
  function loadZipSessions(zipBytes, options) {
    var inflateRaw = (options && options.inflateRaw) || null;
    var groups = groupEntriesBySession(parseZipEntries(zipBytes));
    var sessions = [];
    var chain = Promise.resolve();
    groups.forEach(function (group) {
      var fileNames = Object.keys(group.byFileName);
      var logFiles = fileNames.filter(isSessionLogFileName).sort();
      if (!logFiles.length) return;
      chain = chain.then(function () {
        return Promise.all(logFiles.map(function (name) {
          return readZipEntry(zipBytes, group.byFileName[name], inflateRaw)
            .then(function (data) { return JSON.parse(utf8.decode(data)); });
        })).then(function (parsedLogs) {
          var logs = sortLogsByTimestamp(parsedLogs);
          var recordingEntry = group.byFileName[RECORDING_YAML_NAME];
          var yamlPromise = recordingEntry
            ? readZipEntry(zipBytes, recordingEntry, inflateRaw).then(function (data) { return utf8.decode(data); })
            : Promise.resolve(null);
          return yamlPromise.then(function (recordingYaml) {
            sessions.push({
              sessionId: group.sessionId || (logs[0] && logs[0].session) || 'session',
              logs: logs,
              imageFiles: fileNames.filter(isImageFileName).sort(),
              recordingYaml: recordingYaml,
              byFileName: group.byFileName,
            });
          });
        });
      });
    });
    return chain.then(function () { return sessions; });
  }

  // One screenshot the report's `shots` map should resolve, given a trace step's screenshotFile.
  // Callers pull only the screenshots the trace references (mirroring share-export's
  // collectScreenshots) rather than everything image-shaped in the archive (e.g. the video sprite
  // sheet). A screenshotFile is one of two shapes: a bare in-zip filename (the common case, read out
  // as a data URI) OR an absolute http(s) URL — test-farm runs store screenshots remotely and record
  // their lambda URLs, bundling only a subset locally. A URL is already a usable <img src>, so it's
  // passed through verbatim; only a bare filename is looked up in the archive.
  function sessionImageDataUri(zipBytes, session, fileName, options) {
    if (/^https?:\/\//i.test(fileName)) return Promise.resolve(fileName);
    var entry = session.byFileName[fileName];
    if (!entry) return Promise.resolve(null);
    var inflateRaw = (options && options.inflateRaw) || null;
    return readZipEntry(zipBytes, entry, inflateRaw).then(function (data) {
      return 'data:' + imageMimeType(fileName) + ';base64,' + bytesToBase64(data);
    });
  }

  // ---- Full assembly: zip bytes → rendered report HTML -----------------------------------------

  // The run-report-core.js functions this module composes with. Injectable via options.render (bun
  // tests pass the required() module); in the browser both report homes load run-report-core.js
  // first, so its RUN_REPORT_EXPORTS are already on the global object and are picked up here.
  function resolveRenderer(render) {
    var g = (typeof globalThis !== 'undefined') ? globalThis : {};
    render = render || {};
    return {
      extractTrace: render.extractTrace || g.extractTrace,
      extractLlmLogs: render.extractLlmLogs || g.extractLlmLogs,
      originalYamlFromLogs: render.originalYamlFromLogs || g.originalYamlFromLogs,
      buildRunReportHtml: render.buildRunReportHtml || g.buildRunReportHtml,
      buildMultiReportHtml: render.buildMultiReportHtml || g.buildMultiReportHtml,
    };
  }

  // The one pipeline both report-from-zip homes share — Trail Runner's in-app ?zip= screen and the
  // standalone static edition — so the two can't drift as the renderer evolves. Composes the
  // layers above (sessions → per-session run meta + the screenshots the trace references) with the
  // run-report-core renderer, then emits the single- or multi-session report HTML. Returns
  // { html, sessions, zipBytes }, where sessions are the per-session renderer inputs.
  //
  // options: { render?, onStage?, inflateRaw?, generatedAt? }. render defaults to the browser
  // globals; inflateRaw defaults to null (browser DecompressionStream); generatedAt defaults to now
  // and is shared across every session in a multi-session report.
  function buildReportHtmlFromZipBytes(zipBytes, options) {
    options = options || {};
    var render = resolveRenderer(options.render);
    var onStage = options.onStage || function () {};
    var inflateRaw = options.inflateRaw || null;
    var generatedAt = options.generatedAt || new Date().toLocaleString();

    return loadZipSessions(zipBytes, { inflateRaw: inflateRaw }).then(function (sessions) {
      if (!sessions.length) throw new Error('No Trailblaze session logs found in this archive.');
      var inputs = [];
      var chain = Promise.resolve();
      sessions.forEach(function (session) {
        chain = chain.then(function () {
          onStage('Building report… (' + session.sessionId + ')');
          var trace = render.extractTrace(session.logs);
          var llmLogs = render.extractLlmLogs(session.logs);
          var originalYaml = render.originalYamlFromLogs(session.logs);
          var meta = buildRunMeta(session.logs, {
            recordingYaml: session.recordingYaml, originalYaml: originalYaml, generatedAt: generatedAt,
          });
          // Only the screenshots the trace references — the archive may also hold sprite sheets and
          // other image-shaped artifacts the report never shows.
          var wanted = [];
          trace.forEach(function (t) {
            if (t.screenshotFile && wanted.indexOf(t.screenshotFile) < 0) wanted.push(t.screenshotFile);
          });
          var shots = {};
          var shotChain = Promise.resolve();
          wanted.forEach(function (file) {
            shotChain = shotChain.then(function () {
              return sessionImageDataUri(zipBytes, session, file, { inflateRaw: inflateRaw })
                .then(function (uri) { if (uri) shots[file] = uri; });
            });
          });
          return shotChain.then(function () {
            inputs.push({
              meta: meta, trace: trace, llmLogs: llmLogs, shots: shots,
              recordingYaml: session.recordingYaml, originalYaml: originalYaml,
            });
          });
        });
      });
      return chain.then(function () {
        var html = inputs.length === 1
          ? render.buildRunReportHtml({ meta: inputs[0].meta, trace: inputs[0].trace, llmLogs: inputs[0].llmLogs, shots: inputs[0].shots })
          : render.buildMultiReportHtml({ generatedAt: generatedAt, sessions: inputs });
        return { html: html, sessions: inputs, zipBytes: zipBytes.length };
      });
    });
  }

  var api = {
    // zip reader
    parseZipEntries: parseZipEntries,
    readZipEntry: readZipEntry,
    // LogsRepo read slice
    isSessionLogFileName: isSessionLogFileName,
    isImageFileName: isImageFileName,
    imageMimeType: imageMimeType,
    groupEntriesBySession: groupEntriesBySession,
    sortLogsByTimestamp: sortLogsByTimestamp,
    // session meta
    getSessionStatus: getSessionStatus,
    getSessionStartedInfo: getSessionStartedInfo,
    shortTrailName: shortTrailName,
    sessionDisplayName: sessionDisplayName,
    statusLabel: statusLabel,
    failureReason: failureReason,
    formatDuration: formatDuration,
    buildRunMeta: buildRunMeta,
    // assembly
    loadZipSessions: loadZipSessions,
    sessionImageDataUri: sessionImageDataUri,
    buildReportHtmlFromZipBytes: buildReportHtmlFromZipBytes,
  };

  if (typeof module !== 'undefined' && module.exports) module.exports = api; // bun test / CommonJS
  if (typeof window !== 'undefined') window.TbZipReport = api;               // browser classic script
})();
