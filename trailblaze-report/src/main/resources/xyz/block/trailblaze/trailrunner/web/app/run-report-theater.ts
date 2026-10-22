// The Theater tab: a run played back as one story across its devices. The script is on the left,
// the device on air sits large on a program monitor, the others on a camera wall, and one
// transport drives a single clock every recording (or, without one, the run's screenshots) follows.
//
// The viewer mounts this into an empty container after each render and disposes it before the
// next (see run-report-tab-host.ts), so everything here is plain DOM that dispose can fully undo.
// Time inside the view is story time (ms from the run's first timestamp); the host speaks epoch ms.
import { videoClipRate, videoClipScale } from './run-report-trail-replay';
import { DEFAULT_SCREEN, deviceFrame, frameKindFor, type FrameKind } from './run-report-device-frames';
import type { ReportTabHost, ReportTabMount } from './run-report-tab-host';
import {
  acts, BASE, browserHost, buildTheaterStory, cameraCuts, chapterIndexAt, chapterStep, clock, deviceActions,
  besideInset, deviceTitle, fit, frameAt, screenshotOutlasts, handoffLayout, labelParts, lapseWindows, LAPSE_RATE, loopClock, loopSpan, markAt,
  recordingAt, sameSnapshot, scenes, scriptLines, settledMs, SPEEDS, snapshotAt, span, stepClock, stepCount,
  stepNumber, syncVideo, yamlLineState, yamlReached, yamlSeekTimes, yamlSegments,
  type Box, type Snapshot, type TheaterAction, type TheaterDevice, type TheaterMark, type TheaterStory, type ScriptLine,
} from './run-report-theater-model';

/** The Theater has something to tell when the run logged at least one step. */
export function theaterAvailable(session: SessionPayload): boolean {
  return (session.trace || []).some((row) => row.objective);
}

const esc = (text: unknown): string =>
  String(text ?? '').replace(/[&<>"']/g, (ch) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[ch]);

/** What the view keeps across remounts, in `host.state`. */
interface TheaterState {
  /** The playhead as epoch ms: a live run's origin moves back when an older row lands, so an offset
   * from it would shift. */
  atMs?: number;
  /** Playing when last disposed; resumed only by an in-place redraw. */
  playing?: boolean;
  speed?: number;
  lapse?: boolean;
  pinned?: string;
  mode?: 'steps' | 'yaml';
  layout?: 'stage' | 'grid';
}

const INSET_GAP = 16;
/** Ripples only read at speeds a person can follow. */
const TAP_MAX_RATE = 4;
/** A frame this late skips its ripples rather than firing them in a burst. */
const LATE_FRAME_S = 0.25;
const STEP_KEYS = new Set(['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown']);
const KIND_LABEL: Record<FrameKind, string> = { 'phone-notch': 'Phone', phone: 'Phone', tablet: 'Tablet', browser: 'Browser' };
const OUTCOME_LABEL = { passed: 'Passed', failed: 'Failed', healed: 'Self-healed', unknown: '' } as const;

const ROBOT = '<svg class="storyrobot" viewBox="0 0 16 16" aria-hidden="true"><path fill-rule="evenodd" d="M8 .5a1 1 0 1 1 0 2a1 1 0 1 1 0-2zM7.4 2.5h1.2V4H7.4zM5 4h6a3 3 0 0 1 3 3v3.5a3 3 0 0 1-3 3H5a3 3 0 0 1-3-3V7a3 3 0 0 1 3-3zM5.75 7.25a1.25 1.25 0 1 0 0 2.5a1.25 1.25 0 1 0 0-2.5zM10.25 7.25a1.25 1.25 0 1 0 0 2.5a1.25 1.25 0 1 0 0-2.5zM6.5 11h3a.5 .5 0 0 1 0 1h-3a.5 .5 0 0 1 0-1zM.75 7.25H2v3H.75zM14 7.25h1.25v3H14z"/></svg>';
const PLAY_ICON = '<svg data-icon="play" viewBox="0 0 16 16" aria-hidden="true"><path d="M4 2.5v11l9-5.5z"/></svg><svg data-icon="pause" viewBox="0 0 16 16" aria-hidden="true"><path d="M3.5 2.5h3v11h-3zM9.5 2.5h3v11h-3z"/></svg>';

const labelHtml = (text: string): string =>
  labelParts(text).map((part) => (typeof part === 'string' ? esc(part) : `<code class="storyvar">{{${esc(part.variable)}}}</code>`)).join('');

const pct = (value: number, of: number): string => `${(value / of) * 100}%`;

/**
 * Places a mark on a screen of the given shape. The screen shows its picture fitted inside it
 * (`object-fit: contain`), so a capture of another shape, such as a browser resized mid-run,
 * sits letterboxed and its marks must be scaled and offset with it. With no known shape the
 * screen is taken to be the mark's own.
 */
export function markPlacer(mark: { dw: number; dh: number }, screen?: { w: number; h: number } | null) {
  const w = screen && screen.w > 0 && screen.h > 0 ? screen.w : mark.dw;
  const h = screen && screen.w > 0 && screen.h > 0 ? screen.h : mark.dh;
  const scale = Math.min(w / mark.dw, h / mark.dh);
  const ox = (w - mark.dw * scale) / 2, oy = (h - mark.dh * scale) / 2;
  return {
    left: (x: number) => pct(ox + x * scale, w),
    top: (y: number) => pct(oy + y * scale, h),
    width: (dx: number) => pct(dx * scale, w),
    height: (dy: number) => pct(dy * scale, h),
  };
}

/** Where the view opens: a navigation's moment always wins; otherwise the saved playhead, then the route's. */
export function openingAt(seek: boolean | undefined, routeAt: number | null, saved: number | undefined): number {
  if (seek && routeAt != null) return routeAt;
  return saved ?? routeAt ?? 0;
}

/**
 * Whether a modal dialog (the LLM transcript, the inspector, a zoomed screenshot) is open over the
 * report. A tab's window-level shortcuts must stand down while one is, as the viewer's own do.
 */
export function modalOpen(): boolean {
  return typeof document !== 'undefined' && !!document.querySelector('[aria-modal="true"]');
}

const finite = (value: unknown): number | null => {
  if (typeof value === 'string' ? value.trim() === '' : typeof value !== 'number') return null;
  const n = Number(value);
  return Number.isFinite(n) ? n : null;
};

/**
 * The mark with every coordinate a finite number, or null when any is not. A session's trace is
 * untrusted input and these values are written into markup, so nothing else may reach it.
 */
export function cleanMark(mark: TheaterMark): TheaterMark | null {
  const x = finite(mark.x), y = finite(mark.y), dw = finite(mark.dw), dh = finite(mark.dh);
  if (x === null || y === null || dw === null || dh === null || dw <= 0 || dh <= 0) return null;
  const clean: TheaterMark = { kind: mark.kind, t: mark.t, x, y, dw, dh, ok: mark.ok };
  if (mark.kind === 'check' && mark.box) {
    const left = finite(mark.box.left), top = finite(mark.box.top), right = finite(mark.box.right), bottom = finite(mark.box.bottom);
    // A box that isn't four numbers with area falls back to the point.
    if (left !== null && top !== null && right !== null && bottom !== null && right > left && bottom > top) clean.box = { left, top, right, bottom };
  }
  if (mark.kind === 'swipe') {
    const x2 = finite(mark.x2), y2 = finite(mark.y2);
    if (x2 === null || y2 === null) return null;
    clean.x2 = x2;
    clean.y2 = y2;
  }
  return clean;
}

/** A swipe's line and start dot, drawn in its screen's own pixels. Takes only a mark `cleanMark` passed. */
export function swipeSvg(mark: TheaterMark): string {
  const unit = Math.max(mark.dw, mark.dh);
  return `<svg viewBox="0 0 ${mark.dw} ${mark.dh}" preserveAspectRatio="xMidYMid meet" width="100%" height="100%" style="position:absolute;inset:0;overflow:visible"><line pathLength="1" x1="${mark.x}" y1="${mark.y}" x2="${mark.x2}" y2="${mark.y2}" stroke-width="${(unit * 0.012).toFixed(1)}"/><circle cx="${mark.x}" cy="${mark.y}" r="${(unit * 0.014).toFixed(1)}"/></svg>`;
}
const clamp = (value: number, low: number, high: number): number => Math.max(low, Math.min(high, value));

interface Rig {
  device: TheaterDevice;
  slot: number;
  kind: FrameKind;
  ratio: number;
  el: HTMLElement;
  screen: HTMLElement;
  nodes: HTMLElement[];
  /** The element playing the recording `clip` names; a different recording gets a fresh one. */
  video?: HTMLVideoElement;
  clip?: number;
  /** Recordings that would not load or decode: only those fall back to screenshots. */
  failed: Set<number>;
  /** A `play()` still settling, and one the browser refused: not retried until the reader presses
   * play again or the clip changes, as the Timeline player does. */
  playPending?: boolean;
  playRefused?: boolean;
  /** Takes the listeners off `video` when it is replaced. */
  unwire: Array<() => void>;
  img: HTMLImageElement;
  taps: HTMLElement;
  urlEl: HTMLElement | null;
  /** The screenshot on the slideshow, so a frame is only set once. */
  shown?: string;
  url?: string;
  marked?: TheaterAction;
}

interface Layout {
  zone: Box;
  inset: Box;
  /** Where the inset's corner starts, measured from the stage. */
  insetCorner: { x: number; y: number };
  wells: Record<string, Box>;
}

const NO_BOX: Box = { x: 0, y: 0, w: 0, h: 0 };

export function mountTheater(container: HTMLElement, host: ReportTabHost): ReportTabMount {
  const session = host.session;
  const story = buildTheaterStory({
    trace: session.trace || [],
    meta: session.meta || {},
    recordingYaml: session.recordingYaml,
    originalYaml: session.originalYaml,
    clips: host.clips.map((clip) => ({ startMs: clip.startMs, endMs: clip.endMs, device: clip.device })),
    hasShot: (file) => host.shotSrc(file) !== null,
  });
  if (!story.chapters.length) {
    container.innerHTML = '<div class="empty">This run has no trail steps to play back.</div>';
    return { dispose() { container.innerHTML = ''; } };
  }

  const state = host.state as TheaterState;
  const reduced = host.reducedMotion;
  const chapters = story.chapters;
  const duration = Math.max(1, story.endMs);
  const multi = story.devices.length > 1;
  const cuts = cameraCuts(chapters);
  const windows = lapseWindows(story);
  const byDevice = deviceActions(story);
  const taps = chapters.flatMap((chapter) => chapter.actions).filter((action) => action.mark?.kind === 'tap').sort((a, b) => a.mark!.t - b.mark!.t);
  const deviceNames = story.devices.map((device) => device.name);

  let speed = (SPEEDS as readonly number[]).includes(state.speed as number) ? state.speed! : 1;
  let lapse = state.lapse !== false;
  let pinned = multi && state.pinned && deviceNames.includes(state.pinned) ? state.pinned : undefined;
  let mode: 'steps' | 'yaml' = story.yaml && state.mode === 'yaml' ? 'yaml' : 'steps';
  let layoutChoice: 'stage' | 'grid' = state.layout === 'grid' ? 'grid' : 'stage';
  const isGrid = () => multi && layoutChoice === 'grid';

  // Where to open: an explicit navigation's moment, else where the reader left off.
  const routeAt = host.initialEpochMs != null ? host.initialEpochMs - story.originMs : null;
  const savedAt = typeof state.atMs === 'number' ? state.atMs - story.originMs : undefined;
  const clk = { t: clamp(openingAt(host.seek, routeAt, savedAt), 0, duration), playing: false, rate: speed };
  // Coming back to the tab resumes the saved playhead; the address names it again so Copy link does.
  if (!host.seek && savedAt !== undefined && story.originMs + clk.t !== host.initialEpochMs) host.setEpochMs(story.originMs + clk.t);
  /** The script line under the pointer: the clock holds while it's set. A hovered step loops. */
  let preview: { t: number; rate: number; loop?: [number, number] } | undefined;
  let reseek = false;
  let snap: Snapshot | undefined;

  // ---- Listener and frame bookkeeping, so dispose can undo everything ----------------------------
  const cleanups: Array<() => void> = [];
  const on = (target: EventTarget, type: string, handler: (event: any) => void, options?: AddEventListenerOptions | boolean) => {
    target.addEventListener(type, handler, options);
    cleanups.push(() => target.removeEventListener(type, handler, options));
  };
  const pending = new Set<number>();
  const later = (fn: () => void) => {
    const id = requestAnimationFrame(() => { pending.delete(id); fn(); });
    pending.add(id);
  };
  let tickId = 0;
  let disposed = false;

  // ---- Markup ---------------------------------------------------------------------------------------
  const meta = session.meta || {};
  const platform = [meta.platform, meta.deviceType].filter(Boolean).join(' ');
  const outcomeLabel = OUTCOME_LABEL[story.outcome] || meta.status || '';
  const total = stepCount(chapters);
  container.innerHTML = `
<div class="storyshell"${reduced ? ' data-reduced' : ''}>
  <div class="storytoolbar">
    <div class="storyfacts">
      <span class="storytitle" title="${esc(story.title)}">${esc(story.title)}</span>
      ${outcomeLabel ? `<span class="storystatus" data-outcome="${esc(story.outcome)}">${esc(outcomeLabel)}</span>` : ''}
      ${platform ? `<span class="storyfact">${esc(platform)}</span>` : ''}
      ${multi ? `<span class="storyfact">${story.devices.length} devices</span>` : ''}
      <span class="storyfact">${total} ${total === 1 ? 'step' : 'steps'}</span>
      <span class="storyfact">${esc(meta.duration || clock(story.endMs))}</span>
    </div>
    ${multi ? `<div class="storysegmented" role="group" aria-label="Theater layout"><button type="button" data-story-layout="stage" aria-pressed="${!isGrid()}">Stage</button><button type="button" data-story-layout="grid" aria-pressed="${isGrid()}">Grid</button></div>` : ''}
    <button type="button" class="storyopen" data-story-open title="Open this moment in the Summary">Open this moment in Summary</button>
  </div>
  <div class="storydirector"${multi ? '' : ' data-single'}>
    <aside class="storyrail" aria-label="Script">
      <div class="storyrailhead">
        <span class="storyeyebrow">Script</span>
        <span class="storyrailmeta" data-story-railmeta></span>
        ${story.yaml ? '<div class="storysegmented" role="group" aria-label="Script view"><button type="button" data-story-mode="steps">Steps</button><button type="button" data-story-mode="yaml">YAML</button></div>' : ''}
      </div>
      <div class="storyrailscroll" data-story-scroll></div>
      <button type="button" class="storynowpill" data-story-pill tabindex="-1" aria-hidden="true">Jump to now</button>
    </aside>
    <div class="storymain">
      <div class="storystage" data-story-stage></div>
      <div class="storytransport">
        <div class="storytransportbar">
          <button type="button" class="storyplay" data-story-play aria-label="Play">${PLAY_ICON}</button>
          <span class="storytime"><b data-story-time>0:00</b><span>/ ${clock(duration)}</span></span>
          <div class="storysegmented" role="group" aria-label="Playback speed">${SPEEDS.map((value) => `<button type="button" data-story-speed="${value}" aria-pressed="${value === speed}">${value}×</button>`).join('')}</div>
          <button type="button" class="storyswitch" role="switch" data-story-lapse aria-checked="${lapse}"><span class="storyswitchtrack" aria-hidden="true"></span>Time-lapse the waiting</button>
          <span class="storykeys" aria-hidden="true">${esc(`Space play · ←→ ↑↓ step · A auto${multi ? ` · 1-${Math.min(9, story.devices.length)} pin` : ''}`)}</span>
        </div>
        <div class="storyscrub" role="slider" tabindex="0" data-story-slider aria-label="Story time" aria-valuemin="0" aria-valuemax="${Math.floor(duration / 1000)}" aria-valuenow="0">
          <div class="storyscrubtrack" data-story-track></div>
        </div>
      </div>
    </div>
  </div>
  <div class="storysr" role="status" aria-live="polite" data-story-announce></div>
</div>`;

  const q = <T extends HTMLElement = HTMLElement>(selector: string): T => container.querySelector<T>(selector)!;
  const shell = q('.storyshell');
  const stage = q('[data-story-stage]');
  const scroller = q('[data-story-scroll]');
  const railMeta = q('[data-story-railmeta]');
  const pill = q('[data-story-pill]');
  const playButton = q('[data-story-play]');
  const timeEl = q('[data-story-time]');
  const slider = q('[data-story-slider]');
  const track = q('[data-story-track]');
  const announceEl = q('[data-story-announce]');
  const announce = (text: string) => { announceEl.textContent = text; };

  const slotOf = (name: string) => Math.max(0, deviceNames.indexOf(name));
  const deviceTag = (name: string): string => {
    if (!multi) return '';
    const slot = slotOf(name);
    return `<span class="storydevice"><span class="storydeviceindex" data-story-device="${slot % 6}" aria-hidden="true">${String.fromCharCode(65 + slot)}</span>${esc(deviceTitle(name))}</span>`;
  };
  const stepNo = (index: number): string =>
    chapters[index].selfHeal ? `<span class="storychapterno" data-heal>${ROBOT}</span>` : `<span class="storychapterno">${esc(stepNumber(chapters, index))}</span>`;

  // ---- Devices: one frame each, laid over the program and the wall ---------------------------------
  function newVideo(): HTMLVideoElement {
    const video = document.createElement('video');
    video.muted = true;
    video.playsInline = true;
    video.preload = 'metadata';
    return video;
  }

  const rigs: Rig[] = story.devices.map((device, slot) => {
    const kind = frameKindFor({ platform: device.platform, classifier: device.classifier, name: device.name, screen: device.screen });
    const nodes: HTMLElement[] = [];
    const video = device.recordings.length ? newVideo() : undefined;
    if (video) nodes.push(video);
    const img = document.createElement('img');
    img.className = 'storyshotimg';
    img.alt = '';
    nodes.push(img);
    const none = document.createElement('span');
    none.className = 'storynorecording';
    none.textContent = 'No recording';
    const waiting = document.createElement('span');
    waiting.className = 'storywaiting';
    const tapLayer = document.createElement('div');
    tapLayer.className = 'storytaps';
    nodes.push(none, waiting, tapLayer);
    const el = document.createElement('div');
    el.className = 'storyframe';
    el.style.width = `${BASE}px`;
    el.style.visibility = 'hidden';
    el.dataset.role = 'wall';
    const rig: Rig = { device, slot, kind, ratio: 1, el, screen: el, nodes, video, failed: new Set(), unwire: [], img, taps: tapLayer, urlEl: null };
    mountFrame(rig);
    return rig;
  });
  const rigByName = new Map(rigs.map((rig) => [rig.device.name, rig]));

  function mountFrame(rig: Rig): void {
    const frame = deviceFrame(rig.kind, rig.device.screen ?? DEFAULT_SCREEN[rig.kind], { url: rig.url });
    rig.el.innerHTML = frame.html;
    rig.screen = rig.el.querySelector<HTMLElement>('[data-story-screen]')!;
    rig.screen.append(...rig.nodes);
    rig.urlEl = rig.el.querySelector<HTMLElement>('[data-story-url]');
    rig.ratio = frame.ratio;
  }

  const hardwareLabel = (rig: Rig): string => {
    const raw = rig.device.classifier || rig.device.platform;
    if (!raw) return KIND_LABEL[rig.kind];
    return raw.split(/[-_\s]+/).filter(Boolean).map((word, index) => (index > 0 ? word.toLowerCase() : word.toLowerCase() === 'ios' ? 'iOS' : word.slice(0, 1).toUpperCase() + word.slice(1))).join(' ');
  };

  /** A device whose screen no step measured takes its shape from its first picture. */
  const adopt = (rig: Rig, w: number, h: number) => {
    if (rig.device.screen || !(w > 0 && h > 0)) return;
    rig.device.screen = { w, h };
    rig.kind = frameKindFor({ platform: rig.device.platform, classifier: rig.device.classifier, name: rig.device.name, screen: rig.device.screen });
    mountFrame(rig);
    // A device named only by its shape is relabelled now that its shape is known.
    const label = hardwareLabel(rig);
    container.querySelectorAll<HTMLElement>('[data-story-hardware]').forEach((el) => {
      if (el.dataset.storyHardware === rig.device.name && el.textContent !== label) el.textContent = label;
    });
    applySnapshot();
    later(measure);
  };

  function wireVideo(rig: Rig, video: HTMLVideoElement): void {
    const wait = (on_: boolean) => rig.screen.toggleAttribute('data-waiting', on_);
    const listen = (type: string, handler: () => void) => {
      video.addEventListener(type, handler);
      rig.unwire.push(() => video.removeEventListener(type, handler));
    };
    listen('loadedmetadata', () => { adopt(rig, video.videoWidth, video.videoHeight); syncVideos(); });
    // Only the recording that failed gives way to screenshots; the device's later ones still play.
    listen('error', () => {
      if (rig.clip !== undefined) rig.failed.add(rig.clip);
      wait(false);
      paintScreens(preview?.t ?? clk.t);
    });
    listen('waiting', () => wait(true));
    for (const type of ['playing', 'canplay', 'seeked', 'loadeddata']) listen(type, () => wait(false));
  }

  /** Stops a recording and lets go of its decoder and file. */
  function releaseVideo(video: HTMLVideoElement): void {
    video.pause();
    video.removeAttribute('src');
    video.load();
    video.remove();
  }

  /**
   * Points the device's player at another recording. The element is replaced rather than re-sourced:
   * the CSP fallback adopts an element for good, binding its media methods to the clip it took over,
   * so a new `src` on it would keep drawing the old file.
   */
  function loadClip(rig: Rig, index: number, url: string): void {
    let video = rig.video!;
    if (rig.clip !== undefined) {
      const old = video;
      video = newVideo();
      for (const undo of rig.unwire.splice(0)) undo();
      const at = rig.nodes.indexOf(old);
      if (at >= 0) rig.nodes[at] = video;
      if (old.parentNode) old.parentNode.replaceChild(video, old);
      releaseVideo(old);
      rig.video = video;
      rig.screen.removeAttribute('data-waiting');
      wireVideo(rig, video);
    }
    rig.clip = index;
    rig.playPending = false;
    rig.playRefused = false;
    video.src = url;
  }

  for (const rig of rigs) {
    if (rig.video) wireVideo(rig, rig.video);
    on(rig.img, 'load', () => adopt(rig, rig.img.naturalWidth, rig.img.naturalHeight));
    // No step measured this screen: read its shape off its first picture now, rather than waiting
    // for a still or a recording to come on, so the frame and its label are right from the start.
    if (rig.device.screen) continue;
    const still = rig.device.frames.map((frame) => host.shotSrc(frame.file)).find(Boolean);
    const clip = rig.device.recordings.map((recording) => host.clips[recording.clip]?.url).find(Boolean);
    if (still) {
      const probe = new Image();
      on(probe, 'load', () => adopt(rig, probe.naturalWidth, probe.naturalHeight));
      probe.src = still;
    } else if (clip) {
      // A device seen only on video: its first recording's metadata says how big the screen is.
      const probe = newVideo();
      const done = () => { probe.removeAttribute('src'); probe.load(); };
      on(probe, 'loadedmetadata', () => { adopt(rig, probe.videoWidth, probe.videoHeight); done(); });
      cleanups.push(done);
      probe.src = clip;
    }
  }

  // ---- The stage ------------------------------------------------------------------------------------
  let layout: Layout | undefined;
  let settled = false;
  let kept: Snapshot['inset'];
  let nodeSize = { w: 0, h: 0 };
  let zoneEl: HTMLElement | null = null;
  let insetEl: HTMLElement | null = null;
  let insetWellEl: HTMLElement | null = null;
  let framesEl!: HTMLElement;
  let linksEl: SVGElement | null = null;
  let nodeEl: HTMLElement | null = null;
  let wellEls = new Map<string, HTMLElement>();

  function buildStage(): void {
    const grid = isGrid();
    stage.toggleAttribute('data-grid', grid);
    const cell = (rig: Rig) => `<div class="storygridcell" data-story-cell="${esc(rig.device.name)}" data-story-device="${slotOf(rig.device.name) % 6}">
      <span class="storycammeta">${deviceTag(rig.device.name)}<span class="storycamhardware" data-story-hardware="${esc(rig.device.name)}">${esc(hardwareLabel(rig))}</span></span><span class="storygridwell" data-story-well="${esc(rig.device.name)}"></span></div>`;
    const columns = Math.ceil(Math.sqrt(story.devices.length));
    const program = `<section class="storyprogram" aria-label="${grid ? 'Devices' : 'Program'}">
      <div class="storyprogramhead">
        ${grid ? '<span class="storyeyebrow">Devices</span>' : '<span class="storycamera" data-story-camera></span>'}
        <span class="storybadges">
          <span class="storybadge" data-tone="heal" data-story-badge="heal" hidden><span class="storychapterno" data-heal>${ROBOT}</span>Self-Heal with LLM</span>
          <span class="storybadge" data-story-badge="preview" hidden>Preview</span>
          <span class="storybadge" data-story-badge="pinned" hidden>Pinned</span>
          <span class="storybadge" data-tone="lapse" data-story-badge="lapse" hidden>Time-lapse ${LAPSE_RATE}×</span>
        </span>
      </div>
      ${grid
        ? `<div class="storyzone storygrid" style="grid-template-columns:repeat(${columns},minmax(0,1fr));grid-template-rows:repeat(${Math.ceil(story.devices.length / columns)},minmax(0,1fr))">${rigs.map(cell).join('')}</div>`
        : '<div class="storyzone" data-story-zone></div>'}
      ${grid ? '' : '<div class="storyinset" data-story-inset aria-hidden="true"><span class="storyinsetlabel" data-story-insetlabel></span><div class="storyinsetwell" data-story-insetwell></div></div>'}
    </section>`;
    const wall = multi && !grid ? `<section class="storywall" aria-label="Devices">
      <div class="storywallhead"><span class="storyeyebrow">Devices</span><button type="button" class="storyauto" data-story-auto aria-pressed="${!pinned}">Auto</button></div>
      <div class="storywallgrid" style="--storycams:${story.devices.length}">${rigs.map((rig, index) => `<button type="button" class="storycam" data-story-pin="${esc(rig.device.name)}" aria-pressed="false" aria-label="Pin device ${String.fromCharCode(65 + index)}, ${esc(deviceTitle(rig.device.name))}">
        <span class="storycamwell" data-story-well="${esc(rig.device.name)}"></span>
        <span class="storycammeta">${deviceTag(rig.device.name)}<span class="storycamhardware" data-story-hardware="${esc(rig.device.name)}">${esc(hardwareLabel(rig))}</span><span class="storycamstate" data-story-camstate></span></span></button>`).join('')}</div>
    </section>` : '';
    const links = multi && !grid
      ? '<svg class="storylinks" data-story-links aria-hidden="true"></svg><div class="storyhandoffnode" data-story-node aria-hidden="true"><span class="storyeyebrow">Handoff</span><code class="storyvar" data-story-var></code></div>'
      : '';
    stage.innerHTML = `${program}${wall}${links}<div class="storyframes" aria-hidden="true"></div>`;
    zoneEl = stage.querySelector<HTMLElement>('[data-story-zone]');
    insetEl = stage.querySelector<HTMLElement>('[data-story-inset]');
    insetWellEl = stage.querySelector<HTMLElement>('[data-story-insetwell]');
    linksEl = stage.querySelector<SVGElement>('[data-story-links]');
    nodeEl = stage.querySelector<HTMLElement>('[data-story-node]');
    framesEl = stage.querySelector<HTMLElement>('.storyframes')!;
    wellEls = new Map([...stage.querySelectorAll<HTMLElement>('[data-story-well]')].map((well) => [well.dataset.storyWell!, well]));
    framesEl.append(...rigs.map((rig) => rig.el));
    for (const rig of rigs) rig.el.style.visibility = 'hidden';
    layout = undefined;
    kept = undefined;
    nodeSize = { w: 0, h: 0 };
  }

  const measure = () => {
    if (disposed) return;
    const origin = stage.getBoundingClientRect();
    const box = (node?: Element | null): Box => {
      const rect = node?.getBoundingClientRect();
      return rect ? { x: rect.left - origin.left, y: rect.top - origin.top, w: rect.width, h: rect.height } : NO_BOX;
    };
    layout = {
      zone: box(zoneEl),
      inset: box(insetWellEl),
      insetCorner: box(insetWellEl?.parentElement),
      wells: Object.fromEntries(deviceNames.map((name) => [name, box(wellEls.get(name))])),
    };
    settled = false;
    framesEl.removeAttribute('data-settled');
    place();
    later(() => later(() => {
      settled = true;
      framesEl.toggleAttribute('data-settled', true);
    }));
  };

  /** Where each frame goes: the program's zone, the inset, or its cell on the wall. */
  function place(): void {
    if (!layout || !snap) return;
    const grid = isGrid();
    const insetDevice = !grid && snap.inset?.reason === 'meanwhile' ? snap.inset.device : undefined;
    const handoff = !grid && snap.inset?.reason === 'handoff' ? snap.inset : undefined;
    const program = rigByName.get(snap.onAir);
    const far = kept && rigByName.get(kept.device);
    const links = layout && kept && far && nodeSize.w > 0 && layout.wells[kept.device]
      ? handoffLayout(layout.zone, nodeSize, layout.wells[kept.device], { program: program?.ratio ?? 1, cam: far.ratio }, kept.from === kept.device)
      : undefined;
    for (const rig of rigs) {
      const name = rig.device.name;
      const well = layout.wells[name] ?? NO_BOX;
      const spot = grid
        ? fit(well, rig.ratio)
        : name === snap.onAir
          ? fit(handoff && links ? links.program : insetDevice ? besideInset(layout.zone, layout.insetCorner, rig.ratio, INSET_GAP) : layout.zone, rig.ratio)
          : name === insetDevice
            ? fit(layout.inset, rig.ratio)
            : fit(well, rig.ratio, 6);
      rig.el.style.transform = `translate(${spot.x.toFixed(2)}px, ${spot.y.toFixed(2)}px) scale(${spot.s.toFixed(5)})`;
      rig.el.style.setProperty('--storyscale', String(spot.s));
      rig.el.style.visibility = '';
      rig.el.dataset.role = grid ? (name === snap.onAir ? 'program' : 'wall') : name === snap.onAir ? 'program' : name === insetDevice ? 'inset' : 'wall';
    }
    if (linksEl && nodeEl) {
      const shown = !!(handoff && links);
      linksEl.toggleAttribute('data-show', shown);
      nodeEl.toggleAttribute('data-show', shown);
      nodeEl.setAttribute('aria-hidden', String(!shown));
      if (links) {
        linksEl.innerHTML = links.links.map((d) => `<path d="${esc(d)}"/>`).join('');
        nodeEl.style.transform = `translate(${links.node.x.toFixed(2)}px, ${links.node.y.toFixed(2)}px)`;
      }
    }
  }

  // ---- The script rail -----------------------------------------------------------------------------
  const chapterEls: HTMLElement[] = [];
  const actionHosts: HTMLElement[] = [];
  const handoffEls: Array<{ el: HTMLElement; chapter: number }> = [];
  let yamlEls: HTMLElement[] = [];
  const rendered = new Map<number, ScriptLine[]>();

  const setAttr = (el: Element, name: string, value: string | undefined) => {
    if (value === undefined) { if (el.hasAttribute(name)) el.removeAttribute(name); } else if (el.getAttribute(name) !== value) el.setAttribute(name, value);
  };

  function buildSteps(): string {
    const sceneStarts = new Map<number, number>();
    if (multi) scenes(chapters).forEach((scene, index) => sceneStarts.set(scene.chapters[0], index));
    const list = acts(story);
    const actStarts = new Map<number, number>();
    if (list.length > 1) list.forEach((act, index) => actStarts.set(act.chapters[0], index));
    const items = chapters.map((item) => {
      const parts: string[] = [];
      const act = actStarts.get(item.index);
      if (act !== undefined) parts.push(`<li class="storyact" aria-hidden="true">${esc(list[act].title)}</li>`);
      for (const handoff of story.handoffs.filter((candidate) => candidate.toChapter === item.index)) {
        parts.push(`<li class="storyhandoff" data-story-handoff="${item.index}"><button type="button" data-story-seek="${esc(handoff.consumedMs)}" data-story-preview="${esc(handoff.consumedMs)}">
          <span class="storyhandoffroute">${deviceTag(handoff.from)}<span aria-label="to">→</span>${deviceTag(handoff.to)}</span>
          <span class="storyhandoffvalue">Handoff <code class="storyvar">{{${esc(handoff.variable)}}}</code>${item.index - handoff.fromChapter > 1 ? `<span>from ${esc(stepNumber(chapters, handoff.fromChapter))}</span>` : ''}</span></button></li>`);
      }
      if (sceneStarts.has(item.index)) {
        const rig = rigByName.get(item.device);
        parts.push(`<li class="storyscene"><button type="button" data-story-seek="${esc(item.startMs)}" data-story-preview="${esc(item.startMs)}">${deviceTag(item.device)}${rig ? `<span class="storyscenehardware" data-story-hardware="${esc(rig.device.name)}">${esc(hardwareLabel(rig))}</span>` : ''}<time>${clock(item.startMs)}</time></button></li>`);
      }
      const title = item.selfHeal
        ? `<span class="storychaptertext storyhealtitle">Self-Heal with LLM${item.outcome === 'failed' ? '<span class="storyfailed">Failed</span>' : ''}</span>`
        : `<span class="storychaptertext">${item.kind === 'verify' ? '<span class="storycheck">Check</span>' : ''}${labelHtml(item.headline)}${item.outcome === 'failed' ? '<span class="storyfailed">Failed</span>' : ''}</span>`;
      parts.push(`<li class="storychapter" data-story-chapter="${item.index}" data-outcome="${esc(item.outcome)}"${item.selfHeal ? ' data-heal' : ''}>
        <button type="button" data-story-seek="${esc(settledMs(item))}" data-story-preview="${esc(item.startMs)}" data-story-loop="${esc(item.startMs)},${esc(item.endMs)}">${stepNo(item.index)}${title}</button>
        <ol class="storyactions" data-story-actions></ol></li>`);
      return parts.join('');
    });
    return `<ol class="storysteps">${items.join('')}</ol>`;
  }

  function buildYaml(): string {
    const lines = story.yaml!.text.split(/\r?\n/);
    const seeks = yamlSeekTimes(story);
    return `<ol class="storyyaml" aria-label="${story.yaml!.source === 'recording' ? 'Recorded trail YAML' : 'Trail YAML'}">${lines.map((text, index) => {
      const seek = seeks.get(index + 1);
      const indent = /^\s*(?:-\s+)?/.exec(text)![0].length;
      const runs = yamlSegments(text).map((segment) => {
        const cls = [segment.type && `syntax-${segment.type}`, segment.variable && 'storyyamlvar'].filter(Boolean).join(' ');
        return cls ? `<span class="${cls}">${esc(segment.text)}</span>` : esc(segment.text);
      }).join('') || ' ';
      return `<li class="storyyamlline" data-state="rest"${seek === undefined ? '' : ` data-seek data-story-seek="${esc(seek)}" data-story-preview="${esc(seek)}"`}><span class="storyyamlno" aria-hidden="true">${index + 1}</span><code${indent ? ` style="padding-left:${indent}ch;text-indent:-${indent}ch"` : ''}>${runs}</code></li>`;
    }).join('')}</ol>`;
  }

  function buildRail(): void {
    rendered.clear();
    scroller.dataset.mode = mode;
    railMeta.textContent = mode === 'yaml' ? (story.yaml!.source === 'recording' ? 'As recorded' : 'As authored') : `${total} ${total === 1 ? 'step' : 'steps'}`;
    scroller.innerHTML = mode === 'yaml' ? buildYaml() : buildSteps();
    container.querySelectorAll<HTMLElement>('[data-story-mode]').forEach((button) => button.setAttribute('aria-pressed', String(button.dataset.storyMode === mode)));
    chapterEls.length = 0;
    actionHosts.length = 0;
    handoffEls.length = 0;
    scroller.querySelectorAll<HTMLElement>('[data-story-chapter]').forEach((el) => {
      const index = Number(el.dataset.storyChapter);
      chapterEls[index] = el;
      actionHosts[index] = el.querySelector<HTMLElement>('[data-story-actions]')!;
    });
    scroller.querySelectorAll<HTMLElement>('[data-story-handoff]').forEach((el) => handoffEls.push({ el, chapter: Number(el.dataset.storyHandoff) }));
    yamlEls = [...scroller.querySelectorAll<HTMLElement>('.storyyamlline')];
  }

  function updateRail(): void {
    if (!snap) return;
    if (mode === 'yaml') {
      const current = chapters[snap.chapter];
      const reached = yamlReached(current, snap.cue);
      const nowLine = snap.cue?.yaml?.from ?? current?.yaml?.from;
      yamlEls.forEach((el, index) => {
        const line = index + 1;
        setAttr(el, 'data-state', yamlLineState(line, current?.yaml, snap!.cue?.yaml, reached));
        setAttr(el, 'data-now', line === nowLine ? '' : undefined);
      });
      return;
    }
    const playhead = snap.action?.t ?? chapters[snap.chapter]?.startMs;
    chapters.forEach((chapter, index) => {
      const el = chapterEls[index];
      if (!el) return;
      const state = index === snap!.chapter ? 'now' : index < snap!.chapter ? 'past' : undefined;
      setAttr(el, 'data-state', state);
      setAttr(el, 'data-now', state === 'now' && !snap!.action ? '' : undefined);
      setAttr(el.firstElementChild!, 'aria-current', state === 'now' ? 'step' : undefined);
      const wanted = index === snap!.chapter || chapter.selfHeal === true;
      if (wanted && !rendered.has(index)) {
        const lines = scriptLines(chapter);
        rendered.set(index, lines);
        actionHosts[index].innerHTML = lines.map((line) => {
          const meta = line.count > 1 ? `×${line.count} · ${span(line.endMs - line.t)}` : line.endMs - line.t >= 2000 ? span(line.endMs - line.t) : '';
          return `<li${line.ok ? '' : ' data-ok="false"'}><button type="button" data-story-seek="${esc(line.at)}" data-story-preview="${esc(line.at)}"${line.detail ? ` title="${esc(line.detail)}"` : ''}><span class="storyactiontext">${labelHtml(line.label)}</span><span class="storyactionmeta">${esc(meta)}</span></button></li>`;
        }).join('');
      } else if (!wanted && rendered.has(index)) {
        actionHosts[index].innerHTML = '';
        rendered.delete(index);
      }
      const lines = rendered.get(index);
      if (lines) {
        [...actionHosts[index].children].forEach((li, k) => {
          const line = lines[k];
          const now = !!snap!.action && line.actions.includes(snap!.action);
          const past = !now && playhead !== undefined && line.t < playhead;
          setAttr(li, 'data-state', now ? 'now' : past ? 'past' : undefined);
          setAttr(li, 'data-now', now ? '' : undefined);
        });
      }
    });
    for (const { el, chapter } of handoffEls) setAttr(el, 'data-live', chapter === snap.chapter ? '' : undefined);
  }

  // Following the playing line, until the reader scrolls away; an explicit seek brings it back.
  let following = true;
  let away = false;
  let instant = true;
  let followKey = '';
  const nowEl = () => scroller.querySelector<HTMLElement>('[data-now]');
  const nowVisible = () => {
    const line = nowEl()?.getBoundingClientRect();
    const view = scroller.getBoundingClientRect();
    return !line || (line.top >= view.top && line.bottom <= view.bottom);
  };
  const paintPill = () => {
    const show = !following && away;
    pill.toggleAttribute('data-show', show);
    pill.tabIndex = show ? 0 : -1;
    pill.setAttribute('aria-hidden', String(!show));
  };
  const followScroll = (smooth: boolean) => {
    const target = nowEl();
    if (!target) return;
    const view = scroller.getBoundingClientRect();
    const line = target.getBoundingClientRect();
    const top = Math.max(0, scroller.scrollTop + line.top - view.top - view.height * 0.3);
    if (Math.abs(top - scroller.scrollTop) < 4) return;
    scroller.scrollTo({ top, behavior: smooth && !reduced ? 'smooth' : 'auto' });
  };
  /** Only a new playing line or a seek moves the list; other updates (a hover preview) must not
   * pull it out from under the pointer. */
  const followTick = (force = false) => {
    if (!snap) return;
    const key = mode === 'yaml' ? `yaml:${snap.chapter}:${snap.cue?.t ?? ''}` : `${snap.chapter}:${snap.action?.t ?? ''}`;
    if (key === followKey && !force) return;
    followKey = key;
    if (following) {
      followScroll(!instant);
      instant = false;
      away = false;
    } else {
      away = !nowVisible();
    }
    paintPill();
  };
  const resumeFollow = () => { following = true; instant = true; followKey = ''; };
  for (const type of ['wheel', 'touchstart', 'keydown']) on(scroller, type, () => { following = false; }, { passive: true });
  on(scroller, 'scroll', () => { away = !nowVisible(); paintPill(); }, { passive: true });
  on(pill, 'click', () => { following = true; instant = false; away = false; followScroll(true); paintPill(); });

  // Hovering a line previews its moment on the stage once the pointer actually moves over it, so a
  // list scrolling under a resting pointer doesn't take the stage over.
  let entered: Element | null = null;
  const previewTarget = (event: Event) => (event.target instanceof Element ? event.target.closest('[data-story-preview]') : null);
  on(scroller, 'pointerover', (event: PointerEvent) => { entered = event.pointerType === 'touch' ? null : previewTarget(event); });
  on(scroller, 'pointermove', (event: PointerEvent) => {
    const el = previewTarget(event) as HTMLElement | null;
    if (!el || entered !== el) return;
    entered = null;
    const loop = el.dataset.storyLoop?.split(',').map(Number);
    previewAt(Number(el.dataset.storyPreview), loop && loop.length === 2 ? [loop[0], loop[1]] : undefined);
  });
  on(scroller, 'pointerleave', () => { entered = null; previewAt(undefined); });
  on(scroller, 'click', (event: MouseEvent) => {
    const el = event.target instanceof Element ? event.target.closest<HTMLElement>('[data-story-seek]') : null;
    if (el) seekFrom(Number(el.dataset.storySeek));
  });

  // ---- The transport -------------------------------------------------------------------------------
  const tpct = (t: number) => `${(clamp(t, 0, duration) / duration) * 100}%`;
  const actList = acts(story);
  const scrubActions = chapters.flatMap((chapter) => chapter.actions.map((action) => ({ action, healed: action.healed === true || chapter.selfHeal === true })));
  track.innerHTML = `
    ${actList.length > 1 ? `<div class="storyscrubacts" aria-hidden="true">${actList.map((act, index) => {
      const start = index === 0 ? 0 : chapters[act.chapters[0]].startMs;
      const end = index === actList.length - 1 ? duration : chapters[actList[index + 1].chapters[0]].startMs;
      return `<span style="left:${tpct(start)};width:${tpct(end - start)}">${esc(act.title)}</span>`;
    }).join('')}</div>` : ''}
    <div class="storyscrubbar" data-story-bar>
      ${chapters.filter((chapter) => chapter.selfHeal).map((chapter) => `<span class="storyscrubheal" style="left:${tpct(chapter.startMs)};width:${tpct(chapter.endMs - chapter.startMs)}"></span>`).join('')}
      ${scrubActions.map(({ action, healed }) => `<span class="storyscrubaction" data-story-device="${slotOf(action.device) % 6}"${healed ? ' data-healed' : ''}${action.ok ? '' : ' data-ok="false"'} style="left:${tpct(action.t)};width:${tpct(action.durMs)}"></span>`).join('')}
      ${chapters.slice(1).map((chapter) => `<span class="storyscrubtick" style="left:${tpct(chapter.startMs)}"></span>`).join('')}
      <div class="storyscrubunplayed" data-story-unplayed></div>
      <span class="storyscrubdim" data-story-dim="before" hidden></span><span class="storyscrubdim" data-story-dim="after" hidden></span>
    </div>
    <div class="storyscrubhead" data-story-head></div>
    <div class="storyscrubtip" data-story-tip hidden></div>`;
  const bar = q('[data-story-bar]');
  const head = q('[data-story-head]');
  const unplayed = q('[data-story-unplayed]');
  const tip = q('[data-story-tip]');
  const dimBefore = q('[data-story-dim="before"]');
  const dimAfter = q('[data-story-dim="after"]');

  const paintTransport = (t: number) => {
    const at = tpct(t);
    head.style.left = at;
    unplayed.style.left = at;
    const now = clock(t);
    if (timeEl.textContent !== now) timeEl.textContent = now;
    const seconds = String(Math.floor(t / 1000));
    if (slider.getAttribute('aria-valuenow') !== seconds) {
      slider.setAttribute('aria-valuenow', seconds);
      const index = chapterIndexAt(chapters, t);
      slider.setAttribute('aria-valuetext', `${now} of ${clock(duration)}, step ${stepNumber(chapters, index)}${chapters[index]?.selfHeal ? ', self-heal' : ''}`);
    }
  };
  /** A hovered step's stretch stands out; the rest of the bar dims. */
  const setFocus = (loop?: [number, number]) => {
    bar.toggleAttribute('data-focus', !!loop);
    dimBefore.hidden = dimAfter.hidden = !loop;
    if (loop) {
      dimBefore.style.left = '0';
      dimBefore.style.width = tpct(loop[0]);
      dimAfter.style.left = tpct(loop[1]);
      dimAfter.style.right = '0';
    }
  };
  const paintControls = () => {
    playButton.toggleAttribute('data-playing', clk.playing);
    playButton.setAttribute('aria-label', clk.playing ? 'Pause' : 'Play');
    container.querySelectorAll<HTMLElement>('[data-story-speed]').forEach((button) => button.setAttribute('aria-pressed', String(Number(button.dataset.storySpeed) === speed)));
    q('[data-story-lapse]').setAttribute('aria-checked', String(lapse));
    container.querySelectorAll<HTMLElement>('[data-story-layout]').forEach((button) => button.setAttribute('aria-pressed', String((button.dataset.storyLayout === 'grid') === isGrid())));
  };

  const timeAt = (event: PointerEvent) => {
    const box = track.getBoundingClientRect();
    return clamp(((event.clientX - box.left) / Math.max(1, box.width)) * duration, 0, duration);
  };
  let dragging = false;
  on(slider, 'pointerdown', (event: PointerEvent) => {
    if (event.button !== 0) return;
    dragging = true;
    try { slider.setPointerCapture(event.pointerId); } catch (_) { /* a synthetic pointer has nothing to capture */ }
    seekFrom(timeAt(event), false);
  });
  on(slider, 'pointermove', (event: PointerEvent) => {
    const t = timeAt(event);
    if (dragging) seekFrom(t, false);
    const tipChapter = chapters[chapterIndexAt(chapters, t)];
    const x = event.clientX - track.getBoundingClientRect().left;
    tip.hidden = false;
    tip.style.left = `clamp(7rem, ${x}px, calc(100% - 7rem))`;
    tip.innerHTML = `<span class="storyscrubtiphead"><b>${clock(t)}</b>${stepNo(tipChapter.index)}${deviceTag(tipChapter.device)}</span><span class="storyscrubtiptext">${labelHtml(tipChapter.headline)}</span>`;
  });
  const endDrag = () => { if (dragging) { dragging = false; commit(); } };
  on(slider, 'pointerup', endDrag);
  on(slider, 'pointercancel', endDrag);
  on(slider, 'pointerleave', () => { tip.hidden = true; });
  on(slider, 'keydown', (event: KeyboardEvent) => {
    const t = clk.t;
    const stepTo = (direction: -1 | 1) => settledMs(chapters[chapterStep(chapters, t, direction)]);
    const to: Record<string, number | undefined> = {
      ArrowLeft: t - 5000, ArrowRight: t + 5000, PageUp: stepTo(-1), PageDown: stepTo(1), Home: 0, End: duration,
    };
    const target = to[event.key];
    if (target === undefined) return;
    event.preventDefault();
    seekFrom(clamp(target, 0, duration));
  });

  // ---- Clock and recordings ------------------------------------------------------------------------
  /** Writes the playhead into the address; only for a discrete change, never per frame. */
  const commit = () => {
    const epoch = story.originMs + clk.t;
    state.atMs = story.originMs + clk.t;
    host.setEpochMs(epoch);
  };

  const refresh = () => {
    const next = snapshotAt(story, cuts, clk, { speed, pinned: isGrid() ? undefined : pinned, grid: isGrid() }, preview?.t);
    if (snap && sameSnapshot(snap, next)) return;
    snap = next;
    applySnapshot();
  };

  function applySnapshot(): void {
    if (!snap) return;
    const s = snap;
    const grid = isGrid();
    const onAir = rigByName.get(s.onAir);
    const camera = stage.querySelector<HTMLElement>('[data-story-camera]');
    if (camera && onAir) {
      const html = `${deviceTag(onAir.device.name)}<span>${esc(hardwareLabel(onAir))}</span>`;
      if (camera.innerHTML !== html) camera.innerHTML = html;
    }
    const badge = (name: string, show: boolean) => { const el = stage.querySelector<HTMLElement>(`[data-story-badge="${name}"]`); if (el) el.hidden = !show; };
    badge('heal', !!chapters[s.shown]?.selfHeal);
    badge('preview', s.previewing);
    badge('pinned', !!pinned && !grid);
    badge('lapse', s.lapsing);
    stage.querySelectorAll<HTMLElement>('[data-story-pin]').forEach((button) => {
      const name = button.dataset.storyPin!;
      button.setAttribute('aria-pressed', String(pinned === name));
      button.dataset.role = name === s.onAir ? 'program' : s.inset?.reason === 'meanwhile' && name === s.inset.device ? 'inset' : 'wall';
      const state_ = button.querySelector<HTMLElement>('[data-story-camstate]');
      if (state_) state_.textContent = pinned === name ? 'Pinned' : '';
    });
    stage.querySelector('[data-story-auto]')?.setAttribute('aria-pressed', String(!pinned));
    stage.querySelectorAll<HTMLElement>('[data-story-cell]').forEach((cell) => { cell.dataset.role = cell.dataset.storyCell === s.onAir ? 'program' : 'wall'; });
    if (insetEl) {
      const device = s.inset?.reason === 'meanwhile' && !grid ? s.inset.device : undefined;
      insetEl.toggleAttribute('data-show', !!device);
      insetEl.setAttribute('aria-hidden', String(!device));
      if (device) q('[data-story-insetlabel]').innerHTML = `${deviceTag(device)}<span>Meanwhile</span>`;
    }
    if (s.inset?.reason === 'handoff' && !grid && s.inset.variable !== kept?.variable) {
      // The box keeps its last handoff while it fades out.
      kept = s.inset;
      const chip = nodeEl?.querySelector<HTMLElement>('[data-story-var]');
      if (chip) chip.textContent = `{{${kept.variable}}}`;
      if (nodeEl) nodeSize = { w: nodeEl.offsetWidth, h: nodeEl.offsetHeight };
    } else if (s.inset?.reason === 'handoff' && !grid) {
      kept = s.inset;
    }
    paintControls();
    updateRail();
    followTick();
    place();
    paintScreens(preview?.t ?? clk.t);
    paintMarks(preview?.t ?? clk.t);
  }

  /** What each screen shows at `t`: its recording, else the screenshot taken last. */
  function paintScreens(t: number): void {
    for (const rig of rigs) {
      const frame = frameAt(rig.device, t);
      const recording = recordingAt(rig.device, t);
      const playing = rig.video && !(recording && rig.failed.has(recording.clip)) && !screenshotOutlasts(recording, frame, t);
      const source = playing ? 'video' : frame ? 'shots' : 'none';
      if (rig.screen.dataset.source !== source) rig.screen.dataset.source = source;
      const file = !playing && frame ? frame.file : undefined;
      if (file !== rig.shown) {
        rig.shown = file;
        const src = file ? host.shotSrc(file) : null;
        if (src) rig.img.src = src; else rig.img.removeAttribute('src');
      }
      if (rig.kind === 'browser' && rig.urlEl) {
        const url = browserHost(story, rig.device.name, t) ?? '';
        if (url !== rig.url) { rig.url = url; rig.urlEl.textContent = url; }
      }
    }
  }

  function syncVideos(): void {
    const view = preview ?? clk;
    const live = preview ? preview.loop !== undefined : clk.playing;
    for (const rig of rigs) {
      const recording = recordingAt(rig.device, view.t);
      const clip = recording && host.clips[recording.clip];
      if (!rig.video) continue;
      if (!recording || !clip || rig.failed.has(recording.clip)) {
        // Stills stand in here, so the clip loaded for another recording must not play on behind them.
        if (!rig.video.paused) rig.video.pause();
        continue;
      }
      // A rebound device swaps clips; the frame loop resumes syncing once the new one loads.
      if (rig.clip !== recording.clip) loadClip(rig, recording.clip, clip.url);
      const video = rig.video;
      if (video.readyState < 1) continue;
      const length = Number.isFinite(video.duration) ? video.duration : null;
      const scale = videoClipScale(clip, length);
      const command = syncVideo(video, ((view.t - recording.startMs) / 1000) * scale, live, view.rate, speed, reseek);
      if (command.seek !== undefined) video.currentTime = command.seek;
      if (command.play) {
        const rate = videoClipRate(clip, length, command.rate ?? speed);
        if (rate > 0 && Math.abs(video.playbackRate - rate) > 0.02) video.playbackRate = rate;
        if (video.paused && !rig.playPending && !rig.playRefused) {
          const played = video.play();
          if (played && played.then) {
            rig.playPending = true;
            // Autoplay refused: the clock still runs on the stills. A play a pause or a new load cut
            // short is retried on the next frame.
            played.then(() => { rig.playPending = false; }, (error: unknown) => {
              rig.playPending = false;
              if ((error as { name?: string } | null)?.name === 'NotAllowedError') rig.playRefused = true;
            });
          }
        }
      } else if (!video.paused) {
        video.pause();
      }
    }
    reseek = false;
  }

  /** The mark's element, or null when its coordinates aren't usable numbers. */
  const markNode = (raw: TheaterMark, screen: Rig['device']['screen']): HTMLElement | null => {
    const mark = cleanMark(raw);
    if (!mark) return null;
    const at = markPlacer(mark, screen);
    const node = document.createElement('span');
    node.className = 'storymark';
    if (mark.kind === 'swipe') {
      node.dataset.kind = 'swipe';
      node.innerHTML = swipeSvg(mark);
      return node;
    }
    if (mark.kind === 'check' && !mark.ok) node.dataset.ok = 'false';
    // A check that knows what it found outlines that element; otherwise it marks the point.
    if (mark.box) {
      node.dataset.kind = 'box';
      node.style.left = at.left(mark.box.left);
      node.style.top = at.top(mark.box.top);
      node.style.width = at.width(mark.box.right - mark.box.left);
      node.style.height = at.height(mark.box.bottom - mark.box.top);
      return node;
    }
    node.dataset.kind = mark.kind;
    node.style.left = at.left(mark.x);
    node.style.top = at.top(mark.y);
    return node;
  };

  /** Each screen keeps its latest tap, swipe or check on it, paused or playing. */
  function paintMarks(t: number): void {
    for (const rig of rigs) {
      const action = markAt(byDevice.get(rig.device.name) ?? [], t);
      if (rig.marked !== action) {
        rig.marked = action;
        rig.taps.querySelector('.storymark')?.remove();
        const node = action?.mark && markNode(action.mark, rig.device.screen);
        if (node) rig.taps.append(node);
      }
    }
  }

  const ripple = (action: TheaterAction) => {
    const rig = rigByName.get(action.device);
    const mark = action.mark && cleanMark(action.mark);
    if (!rig || !mark) return;
    const dot = document.createElement('span');
    dot.className = 'storytap';
    const at = markPlacer(mark, rig.device.screen);
    dot.style.left = at.left(mark.x);
    dot.style.top = at.top(mark.y);
    rig.taps.append(dot);
  };
  // One listener clears every ripple as it finishes.
  on(stage, 'animationend', (event: AnimationEvent) => {
    if (event.target instanceof Element && event.target.classList.contains('storytap')) event.target.remove();
  });

  function seek(t: number): void {
    clk.t = clamp(t, 0, duration);
    clk.rate = speed;
    state.atMs = story.originMs + clk.t;
    paintTransport(clk.t);
    refresh();
    syncVideos();
    paintScreens(clk.t);
    paintMarks(clk.t);
  }

  /** A deliberate move of the playhead: drops any preview, follows it in the script and, once
   * the move is over, writes it to the address. */
  function seekFrom(t: number, done = true): void {
    preview = undefined;
    setFocus(undefined);
    resumeFollow();
    seek(t);
    followTick(true);
    if (done) commit();
  }

  function previewAt(t: number | undefined, loop?: [number, number]): void {
    if (t === undefined && !preview) return;
    if (t !== undefined && preview?.t === t && preview.loop?.[0] === loop?.[0] && preview.loop?.[1] === loop?.[1]) return;
    // A step with no length, or a reader who asked for less motion, gets a still frame.
    const plays = loop && loop[1] > loop[0] && !reduced ? loop : undefined;
    preview = t === undefined ? undefined : { t, rate: speed, loop: plays };
    setFocus(loop);
    paintTransport(loop && t !== undefined ? t : clk.t);
    refresh();
    syncVideos();
    paintScreens(preview?.t ?? clk.t);
    paintMarks(preview?.t ?? clk.t);
  }

  function setPlaying(playing: boolean): void {
    if (playing && clk.t >= duration - 50) clk.t = 0;
    clk.playing = playing;
    clk.rate = speed;
    if (playing) for (const rig of rigs) rig.playRefused = false;
    refresh();
    paintControls();
    syncVideos();
    if (!playing) commit();
  }

  const chapterJump = (direction: -1 | 1) => {
    const target = chapterStep(chapters, clk.t, direction);
    const chapter = chapters[target];
    if (!chapter) return;
    seekFrom(settledMs(chapter));
    announce(`Step ${stepNumber(chapters, target)} of ${total}${chapter.selfHeal ? ', self-heal' : ''}: ${chapter.headline}`);
  };

  const pin = (name: string | undefined) => {
    pinned = name;
    state.pinned = name;
    snap = undefined;
    refresh();
    announce(name ? `Camera pinned to ${deviceTitle(name)}` : 'Automatic camera');
  };

  const setMode = (next: 'steps' | 'yaml') => {
    if (next === mode || (next === 'yaml' && !story.yaml)) return;
    mode = next;
    state.mode = next;
    buildRail();
    resumeFollow();
    updateRail();
    followTick(true);
  };

  const setLayout = (next: 'stage' | 'grid') => {
    if (next === layoutChoice || !multi) return;
    layoutChoice = next;
    state.layout = next;
    buildStage();
    snap = undefined;
    refresh();
    measure();
  };

  const setSpeed = (value: number) => {
    speed = value;
    state.speed = value;
    clk.rate = value;
    if (preview) preview.rate = value;
    paintControls();
  };

  // ---- Clicks and keys -----------------------------------------------------------------------------
  on(shell, 'click', (event: MouseEvent) => {
    const el = event.target instanceof Element ? event.target.closest<HTMLElement>('button') : null;
    if (!el) return;
    const d = el.dataset;
    if (d.storyPlay !== undefined) setPlaying(!clk.playing);
    else if (d.storySpeed) setSpeed(Number(d.storySpeed));
    else if (d.storyLapse !== undefined) { lapse = !lapse; state.lapse = lapse; paintControls(); }
    else if (d.storyPin) pin(pinned === d.storyPin ? undefined : d.storyPin);
    else if (d.storyAuto !== undefined) pin(undefined);
    else if (d.storyMode) setMode(d.storyMode as 'steps' | 'yaml');
    else if (d.storyLayout) setLayout(d.storyLayout as 'stage' | 'grid');
    else if (d.storyOpen !== undefined) host.openTab('summary', story.originMs + clk.t);
  });

  on(window, 'keydown', (event: KeyboardEvent) => {
    if (event.metaKey || event.ctrlKey || event.altKey || event.defaultPrevented || (event.repeat && !STEP_KEYS.has(event.key))) return;
    // A dialog over the report owns the keyboard.
    if (modalOpen()) return;
    const target = event.target instanceof Element ? event.target : null;
    if (target?.closest('input, textarea, select, [contenteditable]')) return;
    const onControl = target?.closest('button, a, [role=switch]');
    if (event.key === ' ') {
      if (onControl) return;
      event.preventDefault();
      setPlaying(!clk.playing);
    } else if (STEP_KEYS.has(event.key)) {
      // The scrubber's own arrows seek by seconds; it has no use for up and down.
      if ((event.key === 'ArrowLeft' || event.key === 'ArrowRight') && target?.closest('[role=slider]')) return;
      event.preventDefault();
      chapterJump(event.key === 'ArrowLeft' || event.key === 'ArrowUp' ? -1 : 1);
    } else if (event.key === 'a' || event.key === 'A') {
      event.preventDefault();
      // A pinned camera goes back to automatic; otherwise A toggles the time-lapse.
      if (pinned && !isGrid()) pin(undefined);
      else { lapse = !lapse; state.lapse = lapse; paintControls(); announce(lapse ? 'Time-lapse on' : 'Time-lapse off'); }
    } else if (/^[1-9]$/.test(event.key)) {
      const name = deviceNames[Number(event.key) - 1];
      if (name && multi && !isGrid()) { event.preventDefault(); pin(name); }
    }
  }, true);

  // ---- The frame loop ------------------------------------------------------------------------------
  let last = performance.now();
  let committedChapter = chapterIndexAt(chapters, clk.t);
  const tick = (time: number) => {
    if (disposed) return;
    const dt = Math.max(0, (time - last) / 1000);
    last = time;
    const looping = preview?.loop ? preview : undefined;
    if (looping) {
      const before = looping.t;
      const next = loopClock(looping.t, looping.rate, speed, lapse, windows, looping.loop!, dt);
      looping.t = next.t;
      looping.rate = next.rate;
      if (next.wrapped) reseek = true;
      if (!reduced && dt < LATE_FRAME_S && looping.rate <= TAP_MAX_RATE) {
        const [from, until] = loopSpan(before, next, looping.loop!);
        for (const action of taps) if (action.mark!.t >= from && action.mark!.t < until) ripple(action);
      }
      paintTransport(looping.t);
      refresh();
    } else if (clk.playing && !preview) {
      const before = clk.t;
      const next = stepClock(clk.t, clk.rate, speed, lapse, windows, dt);
      clk.rate = next.rate;
      clk.t = Math.min(duration, next.t);
      state.atMs = story.originMs + clk.t;
      if (!reduced && dt < LATE_FRAME_S && clk.rate <= TAP_MAX_RATE) {
        for (const action of taps) if (action.mark!.t > before && action.mark!.t <= clk.t) ripple(action);
      }
      const ended = clk.t >= duration;
      if (ended) clk.playing = false;
      paintTransport(clk.t);
      refresh();
      const chapter = chapterIndexAt(chapters, clk.t);
      if (ended || chapter !== committedChapter) { committedChapter = chapter; commit(); }
    }
    const shown = preview?.t ?? clk.t;
    syncVideos();
    paintScreens(shown);
    paintMarks(shown);
    tickId = requestAnimationFrame(tick);
  };

  // ---- Start ---------------------------------------------------------------------------------------
  buildRail();
  buildStage();
  const observer = new ResizeObserver(measure);
  observer.observe(stage);
  cleanups.push(() => observer.disconnect());
  paintTransport(clk.t);
  paintControls();
  refresh();
  followTick(true);
  measure();
  tickId = requestAnimationFrame(tick);
  // Data landing or a live update redraws the view under the reader; playback carries on through it.
  if (host.inPlace && state.playing) setPlaying(true);

  return {
    dispose() {
      disposed = true;
      cancelAnimationFrame(tickId);
      for (const id of pending) cancelAnimationFrame(id);
      pending.clear();
      for (const undo of cleanups.splice(0)) undo();
      state.atMs = story.originMs + clk.t;
      state.playing = clk.playing;
      // A recording that stays attached keeps decoding and holding its bytes.
      for (const rig of rigs) {
        for (const undo of rig.unwire.splice(0)) undo();
        if (rig.video) releaseVideo(rig.video);
      }
      container.innerHTML = '';
    },
  };
}
