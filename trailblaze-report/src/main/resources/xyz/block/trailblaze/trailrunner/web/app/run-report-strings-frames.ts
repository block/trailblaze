// Stills taken from a run's recording for the Strings tab, standing in for the screenshot a capture
// doesn't have. The recording is already in the page, so a still costs no report bytes: it is taken
// here, when the tab needs it, and kept for the page's life.
//
// One detached <video> per recording loads the clip once and seeks to each capture's time in turn;
// each still is the frame on screen after that seek, drawn onto a canvas and read back as a JPEG
// data URL. Not an object URL: a CI artifact host that forces the fallback player below serves the
// report with `img-src * data:`, which admits data: images and blocks blob: ones. The element is
// armed for the in-page fallback player (watchClipElement), so a host that won't let media load
// still yields frames wherever that player can decode the clip; that player paints a detached
// element's frames only onto its own canvas, so the still is read from there (clipFrameSource).

import { clipFrameSource, watchClipElement } from './run-report-clip-player';
import { videoClipTimeAt } from './run-report-trail-replay';

/**
 * A still to take: `key` names it for the caller, `atMs` is when, on the recording's clock, and
 * `width`/`height` the capture's screen size, when known. An Android recording keeps the orientation
 * the session started in, so after a rotation its frame is the wrong way round for the capture's
 * boxes; such a frame is answered as none.
 */
interface FrameRequest {
  key: string;
  atMs: number;
  width?: number;
  height?: number;
}

interface FrameClip {
  url: string;
  startMs: number;
  endMs: number;
}

/** How long one load or seek may take before its frame is given up on. */
const FRAME_STEP_TIMEOUT_MS = 8000;

/**
 * Take a still for each request from `clip`, in order, handing each to `onFrame` as it lands: its
 * data URL, or null when the recording doesn't cover that time, can't be decoded, or won't let its
 * pixels be read (a clip linked from another site that doesn't allow it). Every request is
 * answered exactly once, and the promise never rejects.
 */
async function grabClipFrames(
  clip: FrameClip,
  requests: FrameRequest[],
  onFrameOnce: (key: string, src: string | null) => void,
  timeoutMs: number = FRAME_STEP_TIMEOUT_MS,
): Promise<void> {
  if (!requests.length) return;
  const answered = new Set<string>();
  const onFrame = (key: string, src: string | null) => {
    if (answered.has(key)) return;
    answered.add(key);
    onFrameOnce(key, src);
  };
  if (typeof document === 'undefined') { for (const r of requests) onFrame(r.key, null); return; }
  const el = document.createElement('video');
  watchClipElement(el);
  el.muted = true;
  el.preload = 'auto';
  el.setAttribute('playsinline', '');
  // A linked clip's pixels are only readable when its host allows it, and only if asked for up
  // front; an embedded clip is a blob: or data: URL, which is always readable.
  if (!/^(blob|data):/i.test(clip.url)) el.crossOrigin = 'anonymous';
  const next = (event: string): Promise<boolean> => new Promise((resolve) => {
    const done = (ok: boolean) => {
      el.removeEventListener(event, onEvent);
      el.removeEventListener('error', onError);
      clearTimeout(timer);
      resolve(ok);
    };
    const onEvent = () => done(true);
    const onError = () => done(false);
    const timer = setTimeout(() => done(false), timeoutMs);
    el.addEventListener(event, onEvent);
    el.addEventListener('error', onError);
  });
  let broken = false;
  try {
    // An `error` is fatal: the fallback player tears itself down and answers no later seek, so
    // every frame after it would only wait out its timeout.
    el.addEventListener('error', () => { broken = true; });
    // The header is enough: every still is taken after a seek, which lands only once its frame is
    // decoded. The fallback player announces nothing more than this for a detached element.
    const loaded = next('loadedmetadata');
    el.src = clip.url;
    if (!(await loaded)) { for (const r of requests) onFrame(r.key, null); return; }
    const duration = Number.isFinite(el.duration) && el.duration > 0 ? el.duration : null;
    for (const r of requests) {
      const t = videoClipTimeAt(clip, r.atMs, 0, duration);
      if (t == null || duration == null) { onFrame(r.key, null); continue; }
      // The last instant still has a frame; the duration itself is past it.
      const target = Math.min(t, Math.max(0, duration - 0.001));
      // Seek even to where the element already is: only a seek guarantees a decoded frame there.
      const seeked = next('seeked');
      el.currentTime = target;
      if (!(await seeked)) {
        onFrame(r.key, null);
        if (broken) break;
        continue;
      }
      onFrame(r.key, turnedFrom(r, el) ? null : stillOf(el));
    }
  } catch (e) {
    // Anything unexpected ends the batch; the frames not taken yet are answered as none below.
  } finally {
    for (const r of requests) onFrame(r.key, null);
    // Let go of the clip so the element's decoder is freed.
    try { el.removeAttribute('src'); el.load(); } catch (e) { /* nothing to release */ }
  }
}

/** Whether the recording's frame is landscape where the capture was portrait, or the reverse. */
function turnedFrom(r: FrameRequest, el: HTMLVideoElement): boolean {
  const w = r.width || 0, h = r.height || 0, vw = el.videoWidth || 0, vh = el.videoHeight || 0;
  if (!(w > 0 && h > 0 && vw > 0 && vh > 0) || w === h || vw === vh) return false;
  return (w > h) !== (vw > vh);
}

/** The frame on screen in `el`, as a JPEG data URL, or null when it has none or can't be read. */
function stillOf(el: HTMLVideoElement): string | null {
  const w = el.videoWidth, h = el.videoHeight;
  if (!(w > 0) || !(h > 0)) return null;
  const canvas = document.createElement('canvas');
  canvas.width = w;
  canvas.height = h;
  const g = canvas.getContext('2d');
  if (!g) return null;
  try {
    g.drawImage(clipFrameSource(el), 0, 0, w, h);
    const src = canvas.toDataURL('image/jpeg', 0.85);
    // A regex, not the plain prefix: a linked report is checked for carrying no `data:image/` text.
    return /^data:image\//.test(src) ? src : null;
  } catch (e) {
    // A tainted canvas: the clip came from a host that doesn't allow reading its pixels.
    return null;
  }
}

export { grabClipFrames, stillOf, FRAME_STEP_TIMEOUT_MS, type FrameClip, type FrameRequest };
