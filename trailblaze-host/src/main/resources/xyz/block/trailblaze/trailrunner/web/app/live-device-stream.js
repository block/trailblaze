// Live device mirror transport, shared by every Trail Runner surface that shows a live device
// (the interactive recorder + the companion/demonstrate mirror). It's the SAME layered stack the
// standalone `/devices` viewer (the `devices/index.html` resource in trailblaze-host) uses,
// consuming the daemon's `/devices/api/stream` WebSocket:
//
//   - Android + iOS  → Annex-B H.264 access units decoded in-browser via WebCodecs (openH264Stream)
//   - Web            → Chromium CDP JPEG screencast at video frame rate (openWebStream)
//   - Fallback       → the `/rpc-ws` SubscribeFrames JPEG stream (openJpegStream), then a terminal
//                      `/rpc/GetHostDeviceScreenRequest` snapshot poll (startFallback)
//
// Every layer degrades to the next on stall/error/unsupported, so the mirror always shows something
// — full frame rate where the platform + browser support it, screenshot cadence at the floor. A WEB
// mirror also probes its way back UP from a degraded state to the fast CDP path.
//
// Unlike the `/devices` viewer, the device is ALREADY connected by the caller (Trail Runner's
// `/trailrunner/api/record/connect`, which also publishes the stream into the shared session
// registry). So this transport never sends ConnectToDeviceRequest, and — critically — never sends
// DisconnectDeviceRequest: that would close the recorder-owned stream out from under the caller.
// The caller owns connect/disconnect; this owns only the pixels between them.
//
// Pure, DOM-free helpers (h264NalHeaders / h264Codec / pickStreamMode) are exported for unit tests;
// see the sibling live-device-stream.test.ts. All request paths are ROOT-absolute (leading `/`) so
// the Trail Runner page's `<base href="/trailrunner/">` never rewrites them.
(function () {
  'use strict';

  // Auto-upgrade cadence for a WEB mirror stuck on the JPEG/poll fallback: retry the fast CDP
  // screencast this often, treating a probe with no frame within the timeout as still-down.
  var WEB_UPGRADE_RETRY_MS = 4000;
  var WEB_UPGRADE_PROBE_TIMEOUT_MS = 4000;
  // No frame for this long re-arms the fallback ladder (matches the /devices viewer).
  var STALL_MS = 3500;

  // ---- Pure helpers (DOM-free, unit-tested) --------------------------------------------------

  // Byte offsets just past each Annex-B start code (00 00 01 / 00 00 00 01) — i.e. the index of each
  // NAL unit's first (header) byte.
  function h264NalHeaders(bytes) {
    var headers = [];
    for (var index = 0; index <= bytes.length - 4; index++) {
      if (bytes[index] !== 0 || bytes[index + 1] !== 0) continue;
      if (bytes[index + 2] === 1) {
        headers.push(index + 3);
        index += 2;
      } else if (bytes[index + 2] === 0 && bytes[index + 3] === 1) {
        headers.push(index + 4);
        index += 3;
      }
    }
    return headers;
  }

  // The WebCodecs `avc1.PPCCLL` codec string derived from the SPS NAL, or null if there's no SPS.
  function h264Codec(bytes) {
    var sps = h264NalHeaders(bytes).find(function (header) { return (bytes[header] & 0x1f) === 7; });
    if (sps == null || sps + 3 >= bytes.length) return null;
    var hex = function (value) { return value.toString(16).padStart(2, '0').toUpperCase(); };
    return 'avc1.' + hex(bytes[sps + 1]) + hex(bytes[sps + 2]) + hex(bytes[sps + 3]);
  }

  // Which transport to open for a platform, given WebCodecs availability. Mirrors the /devices
  // viewer: Android/iOS stream H.264 when the browser can decode it; Web uses the CDP JPEG
  // screencast; everything else goes straight to the JPEG/poll fallback. `hasWebCodecs` is injected
  // so the decision is pure and testable.
  function pickStreamMode(platform, hasWebCodecs) {
    var supportsH264 = platform === 'ANDROID' || platform === 'IOS';
    if (supportsH264 && hasWebCodecs) return 'h264';
    if (platform === 'WEB') return 'web';
    return 'jpeg';
  }

  // ---- Transport ------------------------------------------------------------------------------

  var hasWebCodecs = function () {
    return typeof window !== 'undefined' && 'VideoDecoder' in window && 'EncodedVideoChunk' in window;
  };

  function wsBase() {
    var scheme = location.protocol === 'https:' ? 'wss:' : 'ws:';
    return scheme + '//' + location.host;
  }

  function streamUrl(deviceId) {
    var params = new URLSearchParams({ instanceId: deviceId.instanceId, platform: deviceId.trailblazeDevicePlatform });
    return wsBase() + '/devices/api/stream?' + params;
  }

  async function rpc(name, body) {
    var response = await fetch('/rpc/' + name, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body || {}),
    });
    var raw = await response.text();
    var json = {};
    try { json = raw ? JSON.parse(raw) : {}; } catch (_) { /* keep raw error */ }
    if (!response.ok) throw new Error(json.message || json.error || raw || ('HTTP ' + response.status));
    return json;
  }

  function frameImageMime(frame) {
    if (/^image\/[a-z0-9.+-]+$/i.test(frame.mime || '')) return frame.mime;
    var base64 = frame.screenshotBase64 || '';
    if (base64.indexOf('/9j/') === 0) return 'image/jpeg';
    if (base64.indexOf('iVBOR') === 0) return 'image/png';
    if (base64.indexOf('UklGR') === 0) return 'image/webp';
    return 'image/jpeg';
  }

  /**
   * Opens the live mirror for an already-connected device and renders it into the caller's
   * `img` + `canvas` pair. Returns a handle with `close()` (idempotent).
   *
   * opts:
   *   deviceId       { instanceId, trailblazeDevicePlatform }
   *   deviceWidth/Height  initial device px (updated from the stream's `configuration` frame)
   *   img, canvas    the two render surfaces (this toggles their `display`)
   *   onFrame()      optional: called after each rendered frame (fps/first-frame hooks)
   *   onDims(w, h)   optional: device dimensions changed
   *   onError(msg)   optional: a non-fatal error worth surfacing (the stream keeps falling back)
   *   onNotConnected()  optional async: the poll fallback saw "device not connected"; the caller can
   *                     re-establish its connection (self-heal). Awaited before the next poll.
   *   pollFrame(deviceId)  optional async: terminal snapshot fallback; defaults to the generic RPC
   */
  function openLiveDeviceStream(opts) {
    var deviceId = opts.deviceId;
    var img = opts.img;
    var canvas = opts.canvas;
    var onFrame = opts.onFrame || function () {};
    var onDims = opts.onDims || function () {};
    var onError = opts.onError || function () {};
    var onNotConnected = opts.onNotConnected || null;
    var pollFrame = opts.pollFrame || function (id) {
      return rpc('GetHostDeviceScreenRequest', { trailblazeDeviceId: id, includeTree: false });
    };

    var entry = {
      width: opts.deviceWidth || 0,
      height: opts.deviceHeight || 0,
      socket: null,
      decoder: null,
      decoderConfigured: false,
      videoTimestamp: 0,
      fallback: false,
      stopped: false,
      objectUrl: null,
      lastFrameAt: 0,
      stallTimer: null,
      upgradeTimer: null,
      upgradeProbe: null,
      onStall: null,
      webDemoted: false,
    };

    function setDims(w, h) {
      if (w && h && (w !== entry.width || h !== entry.height)) { entry.width = w; entry.height = h; onDims(w, h); }
    }

    function frameRendered() {
      entry.lastFrameAt = (typeof performance !== 'undefined' ? performance.now() : Date.now());
      onFrame();
    }

    function armStallFallback() {
      clearTimeout(entry.stallTimer);
      // onStall lets the web fast path pick a softer fallback (demote to JPEG) before the terminal poll.
      entry.stallTimer = setTimeout(function () { (entry.onStall || startFallback)(); }, STALL_MS);
    }

    function streamHeartbeat() {
      // A heartbeat only proves the stream is alive AFTER the first frame has rendered — then a static
      // screen legitimately keeps sending heartbeats and we keep showing the last frame. Before the
      // first frame, a heartbeat must NOT re-arm the watchdog, or a decoder that receives access units
      // but never produces output would be kept alive forever and never fall back.
      if (entry.lastFrameAt) armStallFallback();
    }

    function showVideoFrame(frame) {
      if (entry.stopped) { frame.close(); return; }
      var width = frame.displayWidth || frame.codedWidth;
      var height = frame.displayHeight || frame.codedHeight;
      if (canvas.width !== width || canvas.height !== height) { canvas.width = width; canvas.height = height; }
      canvas.getContext('2d', { alpha: false, desynchronized: true }).drawImage(frame, 0, 0, width, height);
      frame.close();
      canvas.style.display = 'block';
      img.style.display = 'none';
      frameRendered();
      armStallFallback();
    }

    function showBinaryFrame(buffer) {
      if (entry.stopped) return;
      var url = URL.createObjectURL(new Blob([buffer], { type: 'image/jpeg' }));
      img.src = url;
      img.style.display = 'block';
      canvas.style.display = 'none';
      if (entry.objectUrl) URL.revokeObjectURL(entry.objectUrl);
      entry.objectUrl = url;
      frameRendered();
      if (!entry.fallback) armStallFallback();
    }

    function showPolledFrame(frame) {
      if (entry.stopped || !frame.screenshotBase64) return;
      setDims(frame.deviceWidth || entry.width, frame.deviceHeight || entry.height);
      var mime = frameImageMime(frame);
      var url = 'data:' + mime + ';base64,' + frame.screenshotBase64;
      img.src = url;
      img.style.display = 'block';
      canvas.style.display = 'none';
      if (entry.objectUrl) { URL.revokeObjectURL(entry.objectUrl); entry.objectUrl = null; }
      frameRendered();
      if (!entry.fallback) armStallFallback();
    }

    function decodeH264AccessUnit(data) {
      if (entry.stopped || !entry.decoder || entry.decoder.state === 'closed') return;
      var bytes = new Uint8Array(data);
      var keyFrame = h264NalHeaders(bytes).some(function (header) { return (bytes[header] & 0x1f) === 5; });
      if (!entry.decoderConfigured) {
        if (!keyFrame) return;
        var codec = h264Codec(bytes);
        if (!codec) throw new Error('The first H.264 key frame did not include an SPS.');
        // Match ws-scrcpy's proven WebCodecs config: omitting hardwareAcceleration lets Chrome choose
        // hardware when available and software in headless/virtualized environments.
        entry.decoder.configure({ codec: codec, optimizeForLatency: true });
        entry.decoderConfigured = true;
      }
      entry.decoder.decode(new EncodedVideoChunk({
        type: keyFrame ? 'key' : 'delta',
        timestamp: entry.videoTimestamp,
        duration: 40000,
        data: bytes,
      }));
      entry.videoTimestamp += 40000;
    }

    function openH264Stream() {
      var socket = new WebSocket(streamUrl(deviceId));
      socket.binaryType = 'arraybuffer';
      entry.socket = socket;
      entry.videoTimestamp = 0;
      entry.decoderConfigured = false;
      entry.decoder = new VideoDecoder({
        output: function (frame) { showVideoFrame(frame); },
        error: function (error) { onError('Video decode failed: ' + error.message); startFallback(); },
      });
      armStallFallback();
      socket.onmessage = function (event) {
        if (typeof event.data === 'string') {
          var message;
          try { message = JSON.parse(event.data); } catch (_) { return; }
          if (message.type === 'configuration') setDims(message.deviceWidth || entry.width, message.deviceHeight || entry.height);
          // Only heartbeats and decoded frames re-arm the watchdog; a one-shot 'configuration' is not
          // proof frames are flowing.
          if (message.type === 'heartbeat') streamHeartbeat();
          return;
        }
        try { decodeH264AccessUnit(event.data); } catch (error) { onError(error.message); startFallback(); }
      };
      socket.onerror = function () { startFallback(); };
      socket.onclose = function () { startFallback(); };
    }

    // Wires a live CDP-screencast socket as the active fast stream. Shared by openWebStream and the
    // auto-upgrade path so a recovered stream behaves identically to a fresh one.
    function adoptWebStreamSocket(socket) {
      entry.socket = socket;
      entry.webDemoted = false;
      entry.fallback = false;
      entry.onStall = demoteWebToJpeg;
      socket.onmessage = function (event) {
        if (typeof event.data === 'string') {
          var message;
          try { message = JSON.parse(event.data); } catch (_) { return; }
          if (message.type === 'configuration') setDims(message.deviceWidth || entry.width, message.deviceHeight || entry.height);
          if (message.type === 'heartbeat') streamHeartbeat();
          else armStallFallback();
          return;
        }
        showBinaryFrame(event.data);
      };
      socket.onerror = function () { demoteWebToJpeg(); };
      socket.onclose = function () { demoteWebToJpeg(); };
    }

    function openWebStream() {
      var socket = new WebSocket(streamUrl(deviceId));
      socket.binaryType = 'arraybuffer';
      adoptWebStreamSocket(socket);
      armStallFallback();
    }

    // Falls the web mirror back from the CDP screencast to the SubscribeFrames JPEG stream. Guarded so
    // a burst of error+close+stall only demotes once, and schedules a background probe to upgrade back.
    function demoteWebToJpeg() {
      if (entry.stopped || entry.webDemoted) return;
      entry.webDemoted = true;
      entry.onStall = null;
      clearTimeout(entry.stallTimer);
      if (entry.socket) { entry.socket.onclose = null; entry.socket.onerror = null; entry.socket.close(); entry.socket = null; }
      openJpegStream();
      scheduleWebUpgrade();
    }

    function scheduleWebUpgrade() {
      if (entry.stopped || deviceId.trailblazeDevicePlatform !== 'WEB') return;
      clearTimeout(entry.upgradeTimer);
      entry.upgradeTimer = setTimeout(function () { tryWebUpgrade(); }, WEB_UPGRADE_RETRY_MS);
    }

    function tryWebUpgrade() {
      if (entry.stopped || (!entry.webDemoted && !entry.fallback)) return;
      var probe = new WebSocket(streamUrl(deviceId));
      probe.binaryType = 'arraybuffer';
      entry.upgradeProbe = probe;
      var settled = false;
      var retryLater = function () {
        if (settled) return;
        settled = true;
        probe.onmessage = probe.onerror = probe.onclose = null;
        try { probe.close(); } catch (_) {}
        if (entry.upgradeProbe === probe) entry.upgradeProbe = null;
        scheduleWebUpgrade();
      };
      var probeTimer = setTimeout(retryLater, WEB_UPGRADE_PROBE_TIMEOUT_MS);
      probe.onmessage = function (event) {
        // Wait for a binary frame — text (heartbeat/configuration) alone doesn't prove frames flow.
        if (settled || typeof event.data === 'string') return;
        settled = true;
        clearTimeout(probeTimer);
        entry.upgradeProbe = null;
        entry.fallback = false;
        if (entry.socket && entry.socket !== probe) {
          entry.socket.onclose = null; entry.socket.onerror = null; entry.socket.onmessage = null;
          try { entry.socket.close(); } catch (_) {}
        }
        adoptWebStreamSocket(probe);
        showBinaryFrame(event.data);
      };
      probe.onerror = retryLater;
      probe.onclose = retryLater;
    }

    function openJpegStream() {
      var socket = new WebSocket(wsBase() + '/rpc-ws');
      entry.socket = socket;
      armStallFallback();
      socket.onopen = function () {
        socket.send(JSON.stringify({
          type: 'request', id: 'frames-' + deviceId.instanceId + '-' + entry.videoTimestamp,
          path: '/rpc/SubscribeFramesRequest',
          body: { trailblazeDeviceId: deviceId, intervalMs: 40 },
        }));
        armStallFallback();
      };
      socket.onmessage = function (event) {
        var envelope;
        try { envelope = JSON.parse(event.data); } catch (_) { return; }
        if (envelope.type === 'event' && envelope.path === '/event/frame') {
          var frame = envelope.body || {};
          if (frame.trailblazeDeviceId && frame.trailblazeDeviceId.instanceId === deviceId.instanceId) showPolledFrame(frame);
        } else if (envelope.type === 'response' && envelope.ok === false) {
          onError((envelope.error && envelope.error.message) || 'Frame subscription failed.');
          startFallback();
        }
      };
      socket.onerror = function () { startFallback(); };
      socket.onclose = function () { startFallback(); };
    }

    async function startFallback() {
      if (entry.stopped || entry.fallback) return;
      entry.fallback = true;
      clearTimeout(entry.stallTimer);
      if (entry.socket) { entry.socket.onclose = null; entry.socket.close(); entry.socket = null; }
      if (entry.decoder) {
        if (entry.decoder.state !== 'closed') entry.decoder.close();
        entry.decoder = null;
        entry.decoderConfigured = false;
      }
      while (!entry.stopped && entry.fallback) {
        try {
          var frame = await pollFrame(deviceId);
          if (entry.stopped || !entry.fallback) return;
          showPolledFrame(frame);
        } catch (error) {
          if (entry.stopped || !entry.fallback) return;
          // Self-heal: the daemon can lose the connection (restart, external disconnect). Let the
          // caller re-establish it rather than poll a dead stream forever.
          if (onNotConnected && /not connected/i.test(error.message || '')) {
            try { await onNotConnected(); } catch (_) {}
          } else {
            onError(error.message);
          }
          await new Promise(function (resolve) { setTimeout(resolve, 300); });
        }
        await new Promise(function (resolve) { setTimeout(resolve, 80); });
      }
    }

    function open() {
      var mode = pickStreamMode(deviceId.trailblazeDevicePlatform, hasWebCodecs());
      // Both Android and iOS stream Annex-B H.264 over /devices/api/stream (iOS via baguette). If the
      // daemon can't stream iOS (baguette absent) it closes the socket and onclose falls back to JPEG.
      if (mode === 'h264') openH264Stream();
      else if (mode === 'web') openWebStream();
      else openJpegStream();
    }

    function close() {
      entry.stopped = true;
      entry.fallback = false;
      entry.onStall = null;
      entry.webDemoted = false;
      clearTimeout(entry.stallTimer);
      clearTimeout(entry.upgradeTimer);
      if (entry.upgradeProbe) { entry.upgradeProbe.onclose = null; entry.upgradeProbe.onerror = null; entry.upgradeProbe.onmessage = null; try { entry.upgradeProbe.close(); } catch (_) {} entry.upgradeProbe = null; }
      if (entry.socket) { entry.socket.onclose = null; entry.socket.close(); entry.socket = null; }
      if (entry.decoder) {
        if (entry.decoder.state !== 'closed') entry.decoder.close();
        entry.decoder = null;
        entry.decoderConfigured = false;
      }
      if (entry.objectUrl) {
        // Don't leave the img pointing at a revoked blob (it would render as a broken image if the
        // caller shows the surface again before the next stream's first frame).
        URL.revokeObjectURL(entry.objectUrl);
        entry.objectUrl = null;
        img.removeAttribute('src');
        img.style.display = 'none';
      }
      // NOTE: deliberately no DisconnectDeviceRequest here — the caller owns the connection lifecycle.
    }

    open();
    return { close: close };
  }

  var api = {
    openLiveDeviceStream: openLiveDeviceStream,
    h264NalHeaders: h264NalHeaders,
    h264Codec: h264Codec,
    pickStreamMode: pickStreamMode,
    frameImageMime: frameImageMime,
  };

  if (typeof module !== 'undefined' && module.exports) module.exports = api; // bun test / CommonJS
  if (typeof window !== 'undefined') window.TbLiveDeviceStream = api;        // browser classic script
})();
