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
//   4. The full assembly, in two stages so every home shares the derivation regardless of how it
//      renders: buildSessionInputsFromZipBytes (bytes → the per-session renderer inputs) and
//      buildReportHtmlFromZipBytes (those inputs → the exact report HTML). A home that renders into
//      its own document (the in-app ?zip= screen, the standalone static edition) takes the second;
//      one that hydrates itself in place (the viewer shell) takes the first and needs neither HTML
//      builder. The run-report-core renderer both compose with is injected (globals in the browser,
//      the required module in tests).
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
    // A REJECTION, never a synchronous throw: inflating a corrupt entry throws, and every caller
    // handles a bad entry with `.catch` — a throw would escape that and fail the whole report
    // build over one unreadable file.
    if (entry.method === 8) {
      try { return Promise.resolve((inflateRaw || inflateRawWithDecompressionStream)(raw)); }
      catch (e) { return Promise.reject(e); }
    }
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

  var CAPTURE_METADATA_NAME = 'capture_metadata.json';
  var VIDEO_EXTENSIONS = { mp4: 'video/mp4', webm: 'video/webm', mov: 'video/quicktime' };

  function videoMimeType(name) {
    var dot = name.lastIndexOf('.');
    return VIDEO_EXTENSIONS[name.slice(dot + 1).toLowerCase()] || null;
  }

  // The playable recording in a session directory, from capture_metadata.json's artifact list (the
  // CaptureArtifact records the recorder wrote). Returns { fileName, startMs, endMs } or null.
  //
  // A VIDEO artifact names its own file. A VIDEO_FRAMES artifact names the sprite SHEET, which is
  // not playable — but it was derived from the recording and carries the same recorder bookends, so
  // its timestamps are paired with whatever video file the archive actually holds. Those bookends
  // are the whole point of reading this file: without them the recording can't be put on the
  // trace's clock, so an artifact missing them is no better than no artifact at all.
  function videoArtifactFrom(metadataText, fileNames) {
    var parsed;
    try { parsed = JSON.parse(metadataText); } catch (e) { return null; }
    var artifacts = (parsed && parsed.artifacts) || [];
    if (!artifacts.length) return null;
    var playable = fileNames.filter(function (name) { return videoMimeType(name); });
    var pick = null;
    for (var i = 0; i < artifacts.length; i++) {
      var a = artifacts[i] || {};
      var start = Number(a.startTimestampMs);
      var end = Number(a.endTimestampMs);
      if (!isFinite(start) || !isFinite(end) || end <= start) continue;
      if (a.type === 'VIDEO' && playable.indexOf(a.filename) >= 0) {
        return { fileName: a.filename, startMs: start, endMs: end };
      }
      // Held rather than returned: a VIDEO artifact later in the list is the better answer.
      if (a.type === 'VIDEO_FRAMES' && !pick && playable.length) {
        pick = { fileName: playable[0], startMs: start, endMs: end };
      }
    }
    return pick;
  }

  // The recording as something an element can play: an object URL over the archive's own bytes. No
  // transcode and no second download — the file is already in the zip this page fetched. Deliberately
  // NOT a data URI: these run to tens of megabytes, and base64 of that is neither cheap to build nor
  // safe to embed. Yields null where object URLs don't exist (a non-browser host), which just leaves
  // the consumer on screenshots.
  function sessionVideoClip(zipBytes, session, options) {
    var metaEntry = session.byFileName[CAPTURE_METADATA_NAME];
    var canObjectUrl = typeof URL !== 'undefined' && URL && typeof URL.createObjectURL === 'function'
      && typeof Blob !== 'undefined';
    if (!metaEntry || !canObjectUrl) return Promise.resolve(null);
    var inflateRaw = (options && options.inflateRaw) || null;
    return readZipEntry(zipBytes, metaEntry, inflateRaw).then(function (data) {
      var artifact = videoArtifactFrom(utf8.decode(data), flatFileNames(session.byFileName));
      var entry = artifact && session.byFileName[artifact.fileName];
      if (!entry) return null;
      return readZipEntry(zipBytes, entry, inflateRaw).then(function (bytes) {
        var mime = videoMimeType(artifact.fileName) || 'video/mp4';
        return {
          url: URL.createObjectURL(new Blob([bytes], { type: mime })),
          startMs: artifact.startMs,
          endMs: artifact.endMs,
          mime: mime,
        };
      });
    }).catch(function () { return null; }); // a broken/renamed artifact costs the video, not the report
  }

  // Group archive entries by top-level directory — one group per session, mirroring LogsRepo's
  // one-directory-per-session layout. Files in subdirectories are RETAINED under their
  // session-relative subpath (that's where `events/<name>.ndjson` streams and the `attachments/`
  // bytes those events reference live); the flat-file inventories built later (session logs,
  // screenshot images, playable videos) consider only files directly inside the session directory,
  // so tool artifacts like in-process-scripted-tools/ stay out of them. A flat archive (files at
  // the root, e.g. a zip made from inside the session dir) forms a single group with sessionId ''
  // — the caller falls back to the logs' own `session` field — and the session's own
  // subdirectories, which such an archive presents as top-level dirs, are folded back into it.
  //
  // The fold is by ABSENCE OF SESSION LOGS, not by a list of known directory names: AttachmentRef's
  // contract is that the path is authoritative and `attachments/` is only a convention, so a flat
  // archive referencing `media/take.wav` has to keep those bytes reachable under the root session's
  // own subpath. A folded group would otherwise be dropped outright (buildSessions skips a group
  // with no log files), so folding costs nothing and a real sibling session — which by definition
  // has its own logs — is never absorbed.
  function groupEntriesBySession(entries) {
    // Prototype-free on both levels: every key here is an archive-authored name, and on a plain
    // object a top-level entry called `__proto__` sets the prototype instead of a member — the
    // lookup that follows would then miss a file the archive really holds.
    var groups = Object.create(null);
    var order = [];
    entries.forEach(function (entry) {
      var slash = entry.name.indexOf('/');
      var sessionId = slash < 0 ? '' : entry.name.slice(0, slash);
      var fileName = slash < 0 ? entry.name : entry.name.slice(slash + 1);
      if (fileName === '') return;
      if (!groups[sessionId]) {
        groups[sessionId] = { sessionId: sessionId, byFileName: Object.create(null) };
        order.push(sessionId);
      }
      groups[sessionId].byFileName[fileName] = entry;
    });
    // Flat-archive fold: root files alongside a logless top-level dir can only be a session zipped
    // from inside its own directory, so that dir is one of the session's own subpaths.
    if (groups['']) {
      order = order.filter(function (id) {
        if (id === '' || flatFileNames(groups[id].byFileName).some(isSessionLogFileName)) return true;
        Object.keys(groups[id].byFileName).forEach(function (sub) {
          groups[''].byFileName[id + '/' + sub] = groups[id].byFileName[sub];
        });
        delete groups[id];
        return false;
      });
    }
    return order.map(function (id) { return groups[id]; });
  }

  // The flat-file inventory of a session group: only names directly inside the session directory.
  function flatFileNames(byFileName) {
    return Object.keys(byFileName).filter(function (name) { return name.indexOf('/') < 0; });
  }

  // Chronological log order (LogsRepo sorts parsed logs by timestamp — an Instant, so its
  // comparison runs past the millisecond). Date.parse stops AT the millisecond, and the records
  // this ordering exists to place — a driver action and the tool call it belongs to — land
  // microseconds apart, so a millisecond-only key calls them equal and the stable tiebreak falls
  // back to the numbered-filename order, which is the very order that needs correcting. `sub`
  // carries the digits past the millisecond; feed order still breaks a genuine tie.
  function sortLogsByTimestamp(logs) {
    return logs
      .map(function (log, index) {
        var ts = log && typeof log.timestamp === 'string' ? log.timestamp : '';
        var frac = /\.(\d+)/.exec(ts);
        return {
          log: log,
          index: index,
          at: Date.parse(ts) || 0,
          sub: frac ? Number((frac[1] + '000000000').slice(3, 9)) : 0,
        };
      })
      .sort(function (a, b) { return (a.at - b.at) || (a.sub - b.sub) || (a.index - b.index); })
      .map(function (row) { return row.log; });
  }

  // ---- Layer 3: run meta (Kotlin: SessionInfo.kt + RunReportGenerator.sessionMetaJson) --------

  var STATUS_CHANGE_LOG_CLASS = 'xyz.block.trailblaze.logs.client.TrailblazeLog.TrailblazeSessionStatusChangeLog';
  var SELF_HEAL_LOG_CLASS = 'xyz.block.trailblaze.logs.client.TrailblazeLog.SelfHealInvokedLog';
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

  // Mirrors SessionRecordingInfo.usedSelfHeal: a heal the run recovered from cleanly leaves the
  // terminal status plain Succeeded, so this log is the only evidence it happened.
  function usedSelfHeal(logs) {
    for (var i = 0; i < logs.length; i++) {
      if (logs[i].class === SELF_HEAL_LOG_CLASS) return true;
    }
    return false;
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

  // SessionResult.failurePayloadOf + failureCodeOf: the structured failure payload's
  // top-level string `code` (object payload only; non-string codes yield null).
  function failureCode(status) {
    switch (statusKind(status)) {
      case 'Ended.Failed':
      case 'Ended.FailedWithSelfHeal':
        var payload = status.failurePayload;
        if (!payload || typeof payload !== 'object' || Array.isArray(payload)) return null;
        return typeof payload.code === 'string' ? payload.code : null;
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
      // Same two values sessionMetaJson emits, so a report opened from a zip gets classifier-named
      // matrix columns and a searchable classifier just like a CI-generated one. The classifier is
      // the segments joined with '-' — the device's specific compound identity, which is what
      // TrailblazeClassifierLineage.resolutionChain puts at the head of its chain.
      var deviceClassifier = deviceInfo.classifiers
        .filter(function (c) { return c && String(c).trim() !== ''; })
        .join('-');
      if (deviceClassifier) meta.deviceClassifier = deviceClassifier;
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
    var code = failureCode(status);
    if (code) meta.failureCode = code;
    var kind = statusKind(status);
    if (kind === 'Ended.SucceededWithSelfHeal' || kind === 'Ended.FailedWithSelfHeal' || usedSelfHeal(logs)) {
      meta.selfHeal = true;
    }
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
      var fileNames = flatFileNames(group.byFileName);
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

  // ---- Session events + the attachments they reference ------------------------------------------

  var EVENTS_STREAM_RE = /^events\/[^/]+\.ndjson$/;

  // Generic session events (`events/<name>.ndjson`) → the same EventStream[] the bun driver embeds,
  // through the renderer's own decode pipeline (buildEventStream) so the two paths cannot drift. No
  // formatter modules exist on this path, so every stream keeps the generic full-payload shape (the
  // ctx therefore never gates anything — passed as not-passed, the keep-everything arm). An older
  // run-report-core bundle without buildEventStream leaves the report event-less, exactly as before.
  function sessionEventStreams(zipBytes, session, render, inflateRaw) {
    if (typeof render.buildEventStream !== 'function') return Promise.resolve(null);
    // The same read budgets the CLI's filesystem walk applies, defined once in run-report-events.ts
    // (see resolveRenderer). A stream is inflated in full before its first line is decoded, so a
    // small archive holding one highly compressible ndjson would otherwise decompress into the tab.
    if (!render.MAX_EVENT_STREAM_BYTES || !render.MAX_EVENT_STREAMS_TOTAL_CHARS) return Promise.resolve(null);
    var eventFiles = Object.keys(session.byFileName)
      .filter(function (name) { return EVENTS_STREAM_RE.test(name); })
      .sort();
    var streams = [];
    var totalChars = 0;
    var overTotal = false;
    var chain = Promise.resolve();
    eventFiles.forEach(function (name) {
      chain = chain.then(function () {
        if (overTotal) return;
        var entry = session.byFileName[name];
        // The archive's own declared size, checked before inflating rather than after.
        if (entry.uncompressedSize > render.MAX_EVENT_STREAM_BYTES) {
          console.error('events: skipping ' + name + ' — exceeds the ' + (render.MAX_EVENT_STREAM_BYTES / 1024 / 1024) + 'MB per-stream cap');
          return;
        }
        return readZipEntry(zipBytes, entry, inflateRaw).then(function (data) {
          var stream = render.buildEventStream(name.slice('events/'.length), utf8.decode(data).split('\n'), [], { sessionPassed: false });
          if (!stream) return;
          totalChars += JSON.stringify(stream).length;
          if (totalChars > render.MAX_EVENT_STREAMS_TOTAL_CHARS) {
            console.error('events: skipping ' + name + ' and later streams — session events exceed the ' + (render.MAX_EVENT_STREAMS_TOTAL_CHARS / 1024 / 1024) + 'MB total budget');
            overTotal = true;
            return;
          }
          streams.push(stream);
        }).catch(function () { /* a broken stream costs itself, not the report */ });
      });
    });
    return chain.then(function () { return streams.length ? streams : null; });
  }

  // Attachment refs embedded in event payloads (see AttachmentRef in trailblaze-models), resolved
  // to object URLs over the archive's own bytes — the same choice as sessionVideoClip: no base64
  // blow-up and no second download. A blob: value is bytes only this page holds, so it is stripped
  // again at every standalone-document serialization boundary (buildMultiReportHtml, the viewer's
  // export). Media types only: a hostile zip must not become a same-origin blob:text/html document.
  function sessionAttachments(zipBytes, session, streams, render, inflateRaw) {
    var canObjectUrl = typeof URL !== 'undefined' && URL && typeof URL.createObjectURL === 'function'
      && typeof Blob !== 'undefined';
    if (!streams || !canObjectUrl || typeof render.collectStreamAttachmentRefs !== 'function') {
      return Promise.resolve(null);
    }
    // Shared attachment policy, over the same channel as the detector (see resolveRenderer): the
    // MIME rule, the per-session ceiling, the materialization byte budget and the path rule are
    // defined once in run-report-events.ts. A bundle too old to export them materializes nothing
    // rather than applying a second copy of the limits.
    if (!render.ATTACHMENT_MIME || !render.MAX_ATTACHMENTS_PER_SESSION
      || !render.ATTACHMENT_MATERIALIZE_MAX_TOTAL_BYTES || typeof render.isSafeSessionRelativePath !== 'function') {
      return Promise.resolve(null);
    }
    var maxPerSession = render.MAX_ATTACHMENTS_PER_SESSION;
    // Prototype-free, because the keys are bundle-authored file names and the shared path rule
    // accepts any single segment: on a plain `{}`, `seen['constructor']` is already truthy, so an
    // attachment really named `constructor` would dedupe itself away before it was ever looked up.
    var seen = Object.create(null);
    var picked = render.collectStreamAttachmentRefs(streams).filter(function (ref) {
      if (seen[ref.path]) return false;
      seen[ref.path] = true;
      // The shared path rule, not just the exact-match lookup below: an archive can legitimately
      // hold an entry literally named `attachments/../outside.png`, and opening it would be the
      // traversal the policy exists to refuse.
      return render.isSafeSessionRelativePath(ref.path);
    });
    if (picked.length > maxPerSession) {
      console.error('attachments: materializing only the first ' + maxPerSession + ' of ' + picked.length + ' referenced attachment files');
      picked = picked.slice(0, maxPerSession);
    }
    // Prototype-free for the same reason, and one more: `attachments['__proto__'] = url` on a plain
    // object sets nothing at all — the assignment goes through Object.prototype's setter and the
    // entry silently never exists. The map is serialized as JSON, where a null prototype is invisible.
    var attachments = Object.create(null);
    var chain = Promise.resolve();
    // Every selected entry is inflated here, before the report renders and before anyone opens an
    // attachment, so the archive's own declared sizes have to bound it: 200 refs of a few hundred
    // MB each would otherwise decompress into the tab on load. Entries that don't fit the remaining
    // budget are left to their "in the session bundle, not embedded" note.
    //
    // Charged INSIDE the sequential chain, and refunded when the read fails: an entry that never
    // inflated is holding no memory, so keeping its reservation would let one corrupt early entry
    // claiming the whole budget silently drop every valid attachment after it. The refund cannot
    // breach the ceiling for the same reason — there are no bytes to double-count.
    var budget = render.ATTACHMENT_MATERIALIZE_MAX_TOTAL_BYTES;
    var skippedForBudget = 0;
    picked.forEach(function (ref) {
      // Exact-match lookup against the archive's own entry names.
      var entry = session.byFileName[ref.path];
      if (!entry || !render.ATTACHMENT_MIME.test(ref.mimeType)) return;
      chain = chain.then(function () {
        if (entry.uncompressedSize > budget) { skippedForBudget++; return; }
        budget -= entry.uncompressedSize;
        return readZipEntry(zipBytes, entry, inflateRaw).then(function (bytes) {
          attachments[ref.path] = URL.createObjectURL(new Blob([bytes], { type: ref.mimeType.toLowerCase() }));
        }).catch(function () {
          budget += entry.uncompressedSize; /* unreadable entry → in-bundle note, budget untouched */
        });
      });
    });
    return chain.then(function () {
      if (skippedForBudget) {
        console.error('attachments: ' + skippedForBudget + ' attachment file(s) left in the bundle — materializing them would exceed the '
          + render.ATTACHMENT_MATERIALIZE_MAX_TOTAL_BYTES + '-byte per-session budget');
      }
      return Object.keys(attachments).length ? attachments : null;
    });
  }

  // Every object URL a built session list owns, so a caller rendering archive after archive can hand
  // the bytes back to the browser instead of pinning them for the life of the document. Both
  // producers above have to be swept or the untouched one leaks exactly as before: the recording
  // clip (`videoClip.url`) and the attachment map (one URL per materialized media file, so an
  // archive full of audio pins far more here than the single clip does). Only `blob:` values are
  // returned — a `/static` link or a `data:` embed was not minted here and is not ours to revoke.
  function sessionObjectUrls(sessions) {
    if (!sessions || !sessions.length) return [];
    var urls = [];
    var seen = {};
    var add = function (value) {
      var url = String(value == null ? '' : value);
      if (url.slice(0, 5).toLowerCase() !== 'blob:' || seen[url]) return;
      seen[url] = true;
      urls.push(url);
    };
    Array.prototype.forEach.call(sessions, function (session) {
      if (!session || typeof session !== 'object') return;
      if (session.videoClip && typeof session.videoClip === 'object') add(session.videoClip.url);
      if (session.attachments && typeof session.attachments === 'object') {
        Object.keys(session.attachments).forEach(function (path) { add(session.attachments[path]); });
      }
    });
    return urls;
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
      packSessionInputsHierarchies: render.packSessionInputsHierarchies || g.packSessionInputsHierarchies,
      traceScreenshotFiles: render.traceScreenshotFiles || g.traceScreenshotFiles,
      buildEventStream: render.buildEventStream || g.buildEventStream,
      collectStreamAttachmentRefs: render.collectStreamAttachmentRefs || g.collectStreamAttachmentRefs,
      ATTACHMENT_MIME: render.ATTACHMENT_MIME || g.ATTACHMENT_MIME,
      MAX_ATTACHMENTS_PER_SESSION: render.MAX_ATTACHMENTS_PER_SESSION || g.MAX_ATTACHMENTS_PER_SESSION,
      ATTACHMENT_MATERIALIZE_MAX_TOTAL_BYTES: render.ATTACHMENT_MATERIALIZE_MAX_TOTAL_BYTES || g.ATTACHMENT_MATERIALIZE_MAX_TOTAL_BYTES,
      MAX_EVENT_STREAM_BYTES: render.MAX_EVENT_STREAM_BYTES || g.MAX_EVENT_STREAM_BYTES,
      MAX_EVENT_STREAMS_TOTAL_CHARS: render.MAX_EVENT_STREAMS_TOTAL_CHARS || g.MAX_EVENT_STREAMS_TOTAL_CHARS,
      isSafeSessionRelativePath: render.isSafeSessionRelativePath || g.isSafeSessionRelativePath,
    };
  }

  // Stage one of the shared pipeline: zip bytes → the per-session renderer inputs
  // ({ meta, trace, llmLogs, shots, recordingYaml, originalYaml }), which is exactly what
  // buildMultiReportHtml's `sessions` takes and what the viewer's payload carries. Split out of
  // buildReportHtmlFromZipBytes so a caller that renders IN PLACE (the viewer shell hydrating
  // itself) shares this derivation without going through an HTML string — it needs neither of the
  // two HTML builders, so it can embed the viewer bundle alone instead of all of run-report-core.
  //
  // options: { render?, onStage?, inflateRaw?, generatedAt? }. render defaults to the browser
  // globals (only extractTrace / extractLlmLogs / originalYamlFromLogs / traceScreenshotFiles are
  // consulted here);
  // inflateRaw defaults to null (browser DecompressionStream); generatedAt defaults to now and is
  // shared across every session in a multi-session archive.
  function buildSessionInputsFromZipBytes(zipBytes, options) {
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
          var wanted = render.traceScreenshotFiles(trace);
          var shots = {};
          var shotChain = Promise.resolve();
          wanted.forEach(function (file) {
            shotChain = shotChain.then(function () {
              return sessionImageDataUri(zipBytes, session, file, { inflateRaw: inflateRaw })
                .then(function (uri) { if (uri) shots[file] = uri; });
            });
          });
          return shotChain
            .then(function () { return sessionVideoClip(zipBytes, session, { inflateRaw: inflateRaw }); })
            .then(function (videoClip) {
              return sessionEventStreams(zipBytes, session, render, inflateRaw).then(function (events) {
                return sessionAttachments(zipBytes, session, events, render, inflateRaw).then(function (attachments) {
                  inputs.push({
                    meta: meta, trace: trace, llmLogs: llmLogs, shots: shots,
                    recordingYaml: session.recordingYaml, originalYaml: originalYaml,
                    videoClip: videoClip,
                    events: events, attachments: attachments,
                  });
                });
              });
            });
        });
      });
      return chain.then(function () {
        return { sessions: inputs, generatedAt: generatedAt, zipBytes: zipBytes.length };
      });
    });
  }

  // Stage two, for the homes that need a standalone document: the session inputs above rendered to
  // the exact report HTML. Trail Runner's in-app ?zip= screen and the standalone static edition
  // both land here, so neither can drift from the other as the renderer evolves. Returns
  // { html, sessions, zipBytes }; options are buildSessionInputsFromZipBytes's.
  function buildReportHtmlFromZipBytes(zipBytes, options) {
    options = options || {};
    var render = resolveRenderer(options.render);
    // Opt-in, because whether the emitted document outlives this page is the CALLER's fact: the
    // in-app ?zip= screen renders it as a same-origin iframe srcDoc (object URLs resolve), while
    // anything that downloads or persists it must not carry blob: values that resolve nowhere.
    var keepAttachmentObjectUrls = options.keepAttachmentObjectUrls === true;
    return buildSessionInputsFromZipBytes(zipBytes, options).then(function (built) {
      var inputs = built.sessions;
      // Compress the per-step view hierarchies before they're serialized into the document (same
      // gz side-channel the bun driver emits); an older bundle without the packer just embeds
      // them inline, exactly as before.
      var pack = render.packSessionInputsHierarchies
        ? render.packSessionInputsHierarchies(inputs)
        : Promise.resolve();
      return Promise.resolve(pack).then(function () {
        var s0 = inputs[0];
        // Both branches must carry the SAME session data: the one-session path used to drop
        // `events` and `attachments`, so a single-session archive rendered with no event streams
        // and no attachment rows at all while a two-session one rendered both.
        // `keepAttachmentObjectUrls` rides along because this HTML is rendered back into the
        // loading page (iframe srcDoc), where the archive's object URLs still resolve.
        var html = inputs.length === 1
          ? render.buildRunReportHtml({
            meta: s0.meta, trace: s0.trace, llmLogs: s0.llmLogs, shots: s0.shots,
            events: s0.events || null, attachments: s0.attachments || null,
            hierarchies: s0.hierarchies || null, hierarchiesGz: s0.hierarchiesGz || null,
            keepAttachmentObjectUrls: keepAttachmentObjectUrls,
          })
          : render.buildMultiReportHtml({ generatedAt: built.generatedAt, sessions: inputs, keepAttachmentObjectUrls: keepAttachmentObjectUrls });
        return { html: html, sessions: inputs, zipBytes: built.zipBytes };
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
    videoMimeType: videoMimeType,
    videoArtifactFrom: videoArtifactFrom,
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
    sessionVideoClip: sessionVideoClip,
    sessionEventStreams: sessionEventStreams,
    sessionAttachments: sessionAttachments,
    sessionObjectUrls: sessionObjectUrls,
    buildSessionInputsFromZipBytes: buildSessionInputsFromZipBytes,
    buildReportHtmlFromZipBytes: buildReportHtmlFromZipBytes,
  };

  if (typeof module !== 'undefined' && module.exports) module.exports = api; // bun test / CommonJS
  if (typeof window !== 'undefined') window.TbZipReport = api;               // browser classic script
})();
