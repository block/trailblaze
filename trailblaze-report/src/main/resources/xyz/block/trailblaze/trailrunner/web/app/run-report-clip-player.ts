// ---- Recording fallback player -----------------------------------------------------------------
// Plays a report's embedded recording where the page's host forbids <video> from loading it.
//
// CI artifact hosts can serve the report with
// `Content-Security-Policy: default-src * …; img-src * data:`. `media-src` falls back to `*`, which
// matches network URLs only, so a <video> may load neither the `blob:` object URL the viewer mints
// for an embedded clip nor the `data:` URI it came from. Every video surface errors, and the viewer
// (correctly, for a genuinely broken file) drops the session's recording. The same file opened
// locally, or from any host without that header, plays natively.
//
// The policy governs what a media element FETCHES, not what it is fed: `srcObject` takes a
// MediaStream and fetches nothing. So when a clip <video> errors, this decodes the clip's bytes
// in-page with WebCodecs, paints each frame onto a canvas, and feeds that canvas's captured stream
// to the SAME element. The element keeps its classes, layout and handlers; its media API
// (currentTime, duration, paused, play/pause, playbackRate, the media events) is re-pointed at the
// decoder's clock, so no viewer surface knows which path is playing. Native playback stays the
// only path wherever it works: nothing here runs until an element has already failed.
//
// When this path can't play the clip either (no WebCodecs, an undecodable file), the element's
// original `error` is re-raised and the viewer's no-recording fallback runs as before.

// The EBML (WebM/Matroska) element ids the demuxer reads. Everything else is skipped by size.
const EBML_ID = {
  Segment: 0x18538067,
  Info: 0x1549a966,
  TimecodeScale: 0x2ad7b1,
  Duration: 0x4489,
  Tracks: 0x1654ae6b,
  TrackEntry: 0xae,
  TrackNumber: 0xd7,
  TrackType: 0x83,
  CodecID: 0x86,
  Video: 0xe0,
  PixelWidth: 0xb0,
  PixelHeight: 0xba,
  Cluster: 0x1f43b675,
  Timecode: 0xe7,
  SimpleBlock: 0xa3,
  BlockGroup: 0xa0,
  Block: 0xa1,
  ReferenceBlock: 0xfb,
};
// Children a Cluster of unknown size may hold: the first id outside this set ends it.
const CLUSTER_CHILDREN = new Set([EBML_ID.Timecode, EBML_ID.SimpleBlock, EBML_ID.BlockGroup, 0xa7, 0xab, 0xec, 0xbf]);

interface ClipFrame {
  /** Presentation time, seconds from the start of the file. */
  t: number;
  key: boolean;
  data: Uint8Array;
}

interface WebmClip {
  /** The WebCodecs codec string for the video track. */
  codec: string;
  width: number;
  height: number;
  /** Seconds; the container's own Duration when it declares one, as a native element reports. */
  duration: number;
  /** Video frames in decode order, each with its presentation time. */
  frames: ClipFrame[];
}

// An EBML variable-length integer at `pos`: its value and byte length. `unknown` is the all-ones
// size a live muxer writes for an element whose length it didn't know yet.
function readVint(bytes: Uint8Array, pos: number, keepMarker: boolean): { value: number; length: number; unknown: boolean } | null {
  const first = bytes[pos];
  if (first === undefined || first === 0) return null;
  let length = 1;
  while (!(first & (0x80 >> (length - 1)))) length++;
  if (pos + length > bytes.length) return null;
  let value = keepMarker ? first : first & (0xff >> length);
  let allOnes = value === (0xff >> length);
  for (let i = 1; i < length; i++) {
    value = value * 256 + bytes[pos + i];
    if (bytes[pos + i] !== 0xff) allOnes = false;
  }
  return { value, length, unknown: !keepMarker && allOnes };
}

function readUint(bytes: Uint8Array, start: number, end: number): number {
  let value = 0;
  for (let i = start; i < end; i++) value = value * 256 + bytes[i];
  return value;
}

function readFloat(bytes: Uint8Array, start: number, end: number): number {
  const view = new DataView(bytes.buffer, bytes.byteOffset + start, end - start);
  return end - start === 4 ? view.getFloat32(0) : end - start === 8 ? view.getFloat64(0) : NaN;
}

// The VP9 profile from a keyframe's uncompressed header (frame_marker, then profile low/high bits),
// so the codec string names what the stream actually is.
function vp9Codec(firstFrame: Uint8Array | undefined): string {
  const b = firstFrame ? firstFrame[0] : 0;
  const profile = ((b >> 5) & 1) | (((b >> 4) & 1) << 1);
  return `vp09.0${profile}.10.08`;
}

/**
 * Demux a WebM file's first video track into timed frames. Pure; returns null for anything it
 * can't read (not WebM, no video track, laced blocks), and the caller treats null as unplayable.
 */
function parseWebm(bytes: Uint8Array): WebmClip | null {
  let timecodeScale = 1_000_000;
  let durationUnits: number | null = null;
  let videoTrack: number | null = null;
  let codecId = '';
  let width = 0;
  let height = 0;
  const frames: ClipFrame[] = [];
  let clusterTimecode = 0;

  const readTrackEntry = (start: number, end: number) => {
    let number = 0, type = 0, codec = '', w = 0, h = 0;
    walk(start, end, (id, s, e) => {
      if (id === EBML_ID.TrackNumber) number = readUint(bytes, s, e);
      else if (id === EBML_ID.TrackType) type = readUint(bytes, s, e);
      else if (id === EBML_ID.CodecID) codec = String.fromCharCode(...bytes.subarray(s, e)).replace(/\0+$/, '');
      else if (id === EBML_ID.Video) {
        walk(s, e, (vid, vs, ve) => {
          if (vid === EBML_ID.PixelWidth) w = readUint(bytes, vs, ve);
          else if (vid === EBML_ID.PixelHeight) h = readUint(bytes, vs, ve);
        });
      }
    });
    if (type === 1 && videoTrack == null) { videoTrack = number; codecId = codec; width = w; height = h; }
  };

  // A Block/SimpleBlock payload: track vint, int16 timecode relative to the cluster, flags, frame.
  let laced = false;
  const readBlock = (s: number, e: number, simple: boolean, keyHint: boolean) => {
    const track = readVint(bytes, s, false);
    if (!track || track.value !== videoTrack) return;
    const at = s + track.length;
    const rel = (bytes[at] << 24 >> 16) | bytes[at + 1];
    const flags = bytes[at + 2];
    if (flags & 0x06) { laced = true; return; }
    const units = clusterTimecode + rel;
    frames.push({
      t: (units * timecodeScale) / 1e9,
      key: simple ? !!(flags & 0x80) : keyHint,
      data: bytes.subarray(at + 3, e),
    });
  };

  // Visit the children of [start, end). A child of unknown size runs to `end`, except a Cluster,
  // which ends at the first id that can't be its child.
  function walk(start: number, end: number, visit: (id: number, s: number, e: number) => void): void {
    let pos = start;
    while (pos < end) {
      const id = readVint(bytes, pos, true);
      if (!id) return;
      const size = readVint(bytes, pos + id.length, false);
      if (!size) return;
      const s = pos + id.length + size.length;
      let e = size.unknown ? end : Math.min(end, s + size.value);
      if (size.unknown && id.value === EBML_ID.Cluster) {
        let p = s;
        while (p < end) {
          const cid = readVint(bytes, p, true);
          const csize = cid && readVint(bytes, p + cid.length, false);
          if (!cid || !csize || !CLUSTER_CHILDREN.has(cid.value)) break;
          p += cid.length + csize.length + csize.value;
        }
        e = Math.min(p, end);
      }
      visit(id.value, s, e);
      pos = e;
    }
  }

  walk(0, bytes.length, (id, s, e) => {
    if (id !== EBML_ID.Segment) return;
    walk(s, e, (sid, ss, se) => {
      if (sid === EBML_ID.Info) {
        walk(ss, se, (iid, is, ie) => {
          if (iid === EBML_ID.TimecodeScale) timecodeScale = readUint(bytes, is, ie);
          else if (iid === EBML_ID.Duration) durationUnits = readFloat(bytes, is, ie);
        });
      } else if (sid === EBML_ID.Tracks) {
        walk(ss, se, (tid, ts, te) => { if (tid === EBML_ID.TrackEntry) readTrackEntry(ts, te); });
      } else if (sid === EBML_ID.Cluster) {
        walk(ss, se, (cid, cs, ce) => {
          if (cid === EBML_ID.Timecode) clusterTimecode = readUint(bytes, cs, ce);
          else if (cid === EBML_ID.SimpleBlock) readBlock(cs, ce, true, false);
          else if (cid === EBML_ID.BlockGroup) {
            let block: [number, number] | null = null;
            let referenced = false;
            walk(cs, ce, (bid, bs, be) => {
              if (bid === EBML_ID.Block) block = [bs, be];
              else if (bid === EBML_ID.ReferenceBlock) referenced = true;
            });
            if (block) readBlock(block[0], block[1], false, !referenced);
          }
        });
      }
    });
  });

  if (videoTrack == null || laced || !frames.length || !width || !height) return null;
  const codec = codecId === 'V_VP9' ? vp9Codec(frames[0].data) : codecId === 'V_VP8' ? 'vp8' : '';
  if (!codec || !frames[0].key) return null;
  frames.sort((a, b) => a.t - b.t);
  const declared = durationUnits != null && Number.isFinite(durationUnits) ? (durationUnits * timecodeScale) / 1e9 : null;
  const last = frames[frames.length - 1].t;
  const duration = declared != null && declared > last ? declared : last + (frames.length > 1 ? (last - frames[0].t) / (frames.length - 1) : 0.04);
  return { codec, width, height, duration, frames };
}

/** Index of the frame on screen at `t` seconds: the last one whose time has come (0 before it). */
function frameIndexAt(frames: ClipFrame[], t: number): number {
  let lo = 0;
  let hi = frames.length - 1;
  while (lo < hi) {
    const mid = (lo + hi + 1) >> 1;
    if (frames[mid].t <= t) lo = mid; else hi = mid - 1;
  }
  return lo;
}

/** The keyframe a decode must start from to reach frame `index`. */
function keyframeBefore(frames: ClipFrame[], index: number): number {
  for (let i = index; i > 0; i--) if (frames[i].key) return i;
  return 0;
}

// ---- The element side (browser only) ------------------------------------------------------------

// Every media event a native element can fire. Once an element is driven from here its own events
// describe the canvas stream (an endless live source), not the recording, so they are swallowed
// before any viewer handler sees them and the player's own events stand in.
const MEDIA_EVENTS = ['loadstart', 'progress', 'suspend', 'abort', 'error', 'emptied', 'stalled', 'loadedmetadata',
  'loadeddata', 'canplay', 'canplaythrough', 'playing', 'waiting', 'seeking', 'seeked', 'ended', 'durationchange',
  'timeupdate', 'play', 'pause', 'ratechange', 'resize'];

const clipBytes = new Map<string, Uint8Array>();
const parsedClips = new Map<string, WebmClip | null>();
const ownEvents = new WeakSet<Event>();
const adopted = new WeakSet<HTMLMediaElement>();
// Elements whose takeover is under way (the decoder check is async), so a second error for the
// same element — or the same error seen by both listeners — doesn't start a second one.
const adopting = new WeakSet<HTMLMediaElement>();
// Elements whose play() the browser refused because their load had failed, before the takeover
// arrived: a surface plays right after setting src, and a large clip takes long enough to hand
// over that the reader's first press lands in between.
const refusedPlays = new WeakSet<HTMLMediaElement>();

/** Tell the fallback which bytes an object URL minted for a clip holds, so it can decode them. */
function registerClipBytes(url: string, bytes: Uint8Array): void {
  clipBytes.set(url, bytes);
}

/**
 * Forget a clip's bytes and its parsed frames once its object URL is revoked; a viewer that loads
 * one archive after another would otherwise hold every earlier report's recordings.
 */
function unregisterClipBytes(url: string): void {
  clipBytes.delete(url);
  parsedClips.delete(url);
}

function bytesFor(url: string): Uint8Array | null {
  const known = clipBytes.get(url);
  if (known) return known;
  const comma = url.indexOf(',');
  if (url.slice(0, 11) !== 'data:video/' || comma < 0 || !/;base64$/.test(url.slice(0, comma))) return null;
  try {
    const raw = atob(url.slice(comma + 1));
    const bytes = new Uint8Array(raw.length);
    for (let i = 0; i < raw.length; i++) bytes[i] = raw.charCodeAt(i);
    return bytes;
  } catch (e) { return null; }
}

function clipFor(url: string): WebmClip | null {
  if (parsedClips.has(url)) return parsedClips.get(url);
  const bytes = bytesFor(url);
  let clip: WebmClip | null = null;
  try { clip = bytes ? parseWebm(bytes) : null; } catch (e) { clip = null; }
  parsedClips.set(url, clip);
  return clip;
}

function canFallBack(): boolean {
  return typeof (globalThis as any).VideoDecoder === 'function'
    && typeof (globalThis as any).EncodedVideoChunk === 'function'
    && typeof HTMLCanvasElement !== 'undefined'
    && typeof (HTMLCanvasElement.prototype as any).captureStream === 'function';
}

const nativeProto = typeof HTMLMediaElement !== 'undefined' ? HTMLMediaElement.prototype : null;
const nativeDescriptor = (name: string) => nativeProto && Object.getOwnPropertyDescriptor(nativeProto, name);

function fire(el: HTMLMediaElement, type: string): void {
  const event = new Event(type);
  ownEvents.add(event);
  el.dispatchEvent(event);
}

// Swallow the element's native media events: capture listeners on the target run before its
// non-capture ones, so this sees each event ahead of every viewer handler, whenever they were set.
function swallowNativeEvents(el: HTMLMediaElement): void {
  MEDIA_EVENTS.forEach((type) => el.addEventListener(type, (e) => {
    if (adopted.has(el) && !ownEvents.has(e)) e.stopImmediatePropagation();
  }, true));
}

/**
 * Drive `el` from `clip`: canvas + captured stream as its source, and its media API re-pointed at
 * a WebCodecs decode of the clip. Resolves false (having undone nothing visible) if the decoder
 * rejects the stream, so the caller can re-raise the element's error.
 */
function adopt(el: HTMLMediaElement, clip: WebmClip): Promise<boolean> {
  const VideoDecoderCtor = (globalThis as any).VideoDecoder;
  const EncodedVideoChunkCtor = (globalThis as any).EncodedVideoChunk;
  const config = { codec: clip.codec, codedWidth: clip.width, codedHeight: clip.height, optimizeForLatency: true };
  const supported: Promise<boolean> = VideoDecoderCtor.isConfigSupported
    ? VideoDecoderCtor.isConfigSupported(config).then((r: any) => !!(r && r.supported), () => false)
    : Promise.resolve(true);
  return supported.then((ok) => {
    if (!ok) return false;
    // What a surface already asked of the element before its load failed: a surface seeks (and
    // may play or set a speed) right after rendering, well before the error arrives. A native
    // element with no media still records each of those, so they carry over rather than resetting.
    const nativeNumber = (name: string, fallback: number) => {
      const d = nativeDescriptor(name);
      const v = d && d.get ? Number(d.get.call(el)) : NaN;
      return Number.isFinite(v) ? v : fallback;
    };
    const startTime = Math.max(0, Math.min(clip.duration, nativeNumber('currentTime', 0)));
    const startRate = nativeNumber('playbackRate', 1) > 0 ? nativeNumber('playbackRate', 1) : 1;
    const pausedDescriptor = nativeDescriptor('paused');
    const startPlaying = refusedPlays.has(el)
      || !!(pausedDescriptor && pausedDescriptor.get && pausedDescriptor.get.call(el) === false);
    refusedPlays.delete(el);
    adopted.add(el);
    swallowNativeEvents(el);

    const canvas = document.createElement('canvas');
    canvas.width = clip.width;
    canvas.height = clip.height;
    const g = canvas.getContext('2d');
    const stream: MediaStream = (canvas as any).captureStream(0);
    const track: any = stream.getVideoTracks()[0];

    let decoder: any = null;
    let nextDecode = 0;        // next frame index to submit to the decoder
    let wanted = -1;           // frame index that should end up on the canvas
    let shown = -1;            // frame index currently on the canvas
    let firstPaint = false;
    let seekPending = false;
    let dead = false;
    let failed = false;

    let time = startTime;
    let paused = true;
    let ended = false;
    let rate = startRate;
    let anchorWall = 0;
    let anchorTime = 0;
    let tick: ReturnType<typeof setTimeout> | null = null;
    let lastTimeupdate = 0;

    const paint = (frame: any) => {
      const index = Number(frame.timestamp);
      if (index === wanted && g) {
        g.drawImage(frame, 0, 0, clip.width, clip.height);
        if (track && track.requestFrame) track.requestFrame();
        shown = index;
        if (!firstPaint) { firstPaint = true; fire(el, 'loadeddata'); fire(el, 'canplay'); fire(el, 'canplaythrough'); }
        if (seekPending) { seekPending = false; fire(el, 'timeupdate'); fire(el, 'seeked'); }
      }
      frame.close();
    };
    const closeDecoder = () => {
      if (decoder && decoder.state !== 'closed') { try { decoder.close(); } catch (e) { /* already closed */ } }
      decoder = null;
      nextDecode = 0;
      shown = -1;
    };
    // Free everything the player holds. Nothing can restart it afterwards.
    const teardown = () => {
      dead = true;
      stopClock();
      closeDecoder();
      stream.getTracks().forEach((t) => t.stop());
      const srcObject = nativeDescriptor('srcObject');
      if (srcObject && srcObject.set) srcObject.set.call(el, null);
    };
    // The clip parsed but won't decode (a corrupt or truncated frame). Nothing on the canvas can be
    // trusted, so give up and tell the viewer the recording is unplayable, as a native decode
    // error would, and it falls back to the step's screenshots.
    const fail = () => {
      if (dead) return;
      failed = true;
      teardown();
      fire(el, 'error');
    };
    const openDecoder = () => {
      if (decoder && decoder.state !== 'closed') { try { decoder.close(); } catch (e) { /* already closed */ } }
      decoder = new VideoDecoderCtor({ output: paint, error: fail });
      decoder.configure(config);
      nextDecode = 0;
    };
    // Put frame `index` on the canvas. Forward from the decoder's position it feeds the frames in
    // between; backward (or past a keyframe) it restarts at the keyframe before the target, since a
    // VP9 inter frame only decodes on top of every frame since its keyframe.
    const show = (index: number) => {
      if (dead) return;
      wanted = index;
      if (index === shown) { if (seekPending) { seekPending = false; fire(el, 'timeupdate'); fire(el, 'seeked'); } return; }
      const key = keyframeBefore(clip.frames, index);
      if (!decoder || decoder.state === 'closed' || index < nextDecode - 1 || key >= nextDecode) {
        openDecoder();
        nextDecode = key;
      } else if (index === nextDecode - 1) {
        return; // already submitted; its output is on the way
      }
      try {
        for (; nextDecode <= index; nextDecode++) {
          const frame = clip.frames[nextDecode];
          decoder.decode(new EncodedVideoChunkCtor({ type: frame.key ? 'key' : 'delta', timestamp: nextDecode, data: frame.data }));
        }
      } catch (e) {
        fail();
      }
    };
    const now = () => (typeof performance !== 'undefined' ? performance.now() : Date.now());
    const stopClock = () => { if (tick != null) { clearTimeout(tick); tick = null; } };
    // A timer rather than requestAnimationFrame: rAF stops in a hidden tab, and a native element
    // keeps its clock running there too.
    const runClock = () => {
      stopClock();
      tick = setTimeout(() => {
        tick = null;
        if (paused || dead) return;
        // A surface replaces its markup without calling load(), which leaves this element detached.
        // A native element pauses when removed from the document, so do the same, and free the
        // decoder too. Re-inserting the element and seeking or playing reopens it.
        if (!el.isConnected) { pause(); closeDecoder(); return; }
        let t = anchorTime + ((now() - anchorWall) * rate) / 1000;
        if (t >= clip.duration) {
          if ((el as HTMLMediaElement).loop) {
            anchorTime = 0; anchorWall = now(); t = 0;
          } else {
            time = clip.duration; paused = true; ended = true;
            show(clip.frames.length - 1);
            fire(el, 'timeupdate'); fire(el, 'pause'); fire(el, 'ended');
            return;
          }
        }
        time = t;
        show(frameIndexAt(clip.frames, t));
        if (now() - lastTimeupdate >= 250) { lastTimeupdate = now(); fire(el, 'timeupdate'); }
        runClock();
      }, 16);
    };

    const define = (name: string, get: () => any, set?: (v: any) => void) => {
      Object.defineProperty(el, name, { configurable: true, enumerable: true, get, set });
    };
    define('currentTime', () => time, (v) => {
      const t = Math.max(0, Math.min(clip.duration, Number(v) || 0));
      time = t; ended = false;
      anchorTime = t; anchorWall = now();
      seekPending = true;
      fire(el, 'seeking');
      show(frameIndexAt(clip.frames, t));
    });
    define('duration', () => clip.duration);
    define('paused', () => paused);
    define('ended', () => ended);
    define('seeking', () => seekPending);
    define('readyState', () => (firstPaint ? 4 : 1));
    // MEDIA_ERR_DECODE once decoding has failed, like the native element after a decode error.
    define('error', () => (failed ? { code: 3, message: 'recording could not be decoded' } : null));
    define('videoWidth', () => clip.width);
    define('videoHeight', () => clip.height);
    define('playbackRate', () => rate, (v) => {
      const r = Number(v);
      if (!(r > 0) || r === rate) return;
      anchorTime = time; anchorWall = now();
      rate = r;
      fire(el, 'ratechange');
    });
    const play = () => {
      if (dead) return Promise.resolve();
      if (paused) {
        if (ended || time >= clip.duration) { time = 0; ended = false; }
        paused = false;
        anchorTime = time; anchorWall = now();
        fire(el, 'play'); fire(el, 'playing');
        runClock();
      }
      return Promise.resolve();
    };
    const pause = () => {
      if (!paused) { paused = true; stopClock(); fire(el, 'timeupdate'); fire(el, 'pause'); }
    };
    // `removeAttribute('src')` + `load()` is how a surface frees a player it is done with.
    const load = () => {
      if (el.getAttribute('src')) return;
      teardown();
    };
    Object.defineProperty(el, 'play', { configurable: true, value: play });
    Object.defineProperty(el, 'pause', { configurable: true, value: pause });
    Object.defineProperty(el, 'load', { configurable: true, value: load });

    // The element keeps playing the canvas stream natively for as long as it lives, paused or not
    // from the reader's point of view — a paused native stream would freeze on whatever frame was
    // up, and seeking while paused has to repaint.
    el.muted = true;
    const srcObject = nativeDescriptor('srcObject');
    if (srcObject && srcObject.set) srcObject.set.call(el, stream);
    const nativePlay = nativeProto && nativeProto.play;
    if (nativePlay) { const p = nativePlay.call(el); if (p && p.catch) p.catch(() => { /* autoplay refused; the frame still shows */ }); }

    fire(el, 'durationchange');
    fire(el, 'loadedmetadata');
    // A detached element is only ever asked for metadata (the viewer's duration probe); decoding a
    // frame for it would be wasted work.
    if (el.isConnected) show(frameIndexAt(clip.frames, time));
    if (el.autoplay || startPlaying) play();
    return true;
  }, () => false);
}

/**
 * Re-raise the element's error for the viewer: the fallback couldn't play this clip either, so
 * the session's recording is as unplayable as the native error said.
 */
function reraise(el: HTMLMediaElement): void {
  fire(el, 'error');
}

// Handle a clip <video>'s load failure: take it over when its bytes are ours and this browser can
// decode them, otherwise let the error through untouched.
function onMediaError(e: Event): void {
  const el = e.target as HTMLMediaElement;
  if (!el || el.tagName !== 'VIDEO' || ownEvents.has(e) || adopted.has(el)) return;
  if (adopting.has(el)) { e.stopImmediatePropagation(); return; }
  const url = el.getAttribute('src') || '';
  if (!url || !canFallBack()) return;
  const clip = clipFor(url);
  if (!clip) return;
  e.stopImmediatePropagation();
  adopting.add(el);
  adopt(el, clip).then((ok) => { adopting.delete(el); if (!ok) reraise(el); });
}

/**
 * Keep a play() the browser refuses for a clip the fallback can take over, so the takeover starts
 * playing instead of the press being lost: once a load fails the element rejects play() with
 * NotSupportedError and stays paused, so nothing else records that it was asked. A pause() before
 * the takeover withdraws it.
 */
function keepRefusedPlays(proto: any): void {
  const play = proto.play;
  const pause = proto.pause;
  if (typeof play !== 'function' || typeof pause !== 'function') return;
  proto.play = function (this: HTMLMediaElement) {
    const refused = play.call(this);
    if (!refused || typeof refused.catch !== 'function') return refused;
    return refused.catch((err: any) => {
      if (!err || err.name !== 'NotSupportedError') throw err;
      // The failure's error event runs before this rejection does, so a clip already parsed can be
      // taken over by now: the press goes to the takeover's own play().
      if (adopted.has(this)) return this.play();
      if (!canFallBack() || !clipFor(this.getAttribute('src') || '')) throw err;
      refusedPlays.add(this);
    });
  };
  proto.pause = function (this: HTMLMediaElement) {
    refusedPlays.delete(this);
    return pause.call(this);
  };
}

let installed = false;
/**
 * Arm the fallback for every <video> in the document. Media `error` doesn't bubble, but a
 * window-level CAPTURE listener still sees it first, ahead of the element's own handlers.
 */
function installClipFallback(): void {
  if (installed || typeof window === 'undefined' || typeof window.addEventListener !== 'function') return;
  installed = true;
  window.addEventListener('error', onMediaError, true);
  const media = (globalThis as any).HTMLMediaElement;
  if (media && media.prototype) keepRefusedPlays(media.prototype);
}

/**
 * Arm the fallback for a DETACHED element, which window-level listeners never see. Call before
 * assigning its handlers and src.
 */
function watchClipElement(el: HTMLMediaElement): void {
  if (el && typeof el.addEventListener === 'function') el.addEventListener('error', onMediaError, true);
}

export {
  parseWebm, frameIndexAt, keyframeBefore, registerClipBytes, unregisterClipBytes, installClipFallback, watchClipElement,
  keepRefusedPlays,
};
export type { WebmClip, ClipFrame };
