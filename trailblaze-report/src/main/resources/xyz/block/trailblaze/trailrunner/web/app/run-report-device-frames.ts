// Generic device enclosures for the Theater's stage: a notched phone, an Android phone, a tablet
// and a browser window, drawn here as simple vector shapes around a screen opening that is sized
// to the recording's own aspect. They stand for a kind of device, never a particular product.
//
// Every frame is laid out in design units on a 1000-wide canvas (the stage scales the whole frame
// with one transform), so `ratio` — height over width — is known before anything is measured.
// Colors are CSS custom properties the stylesheet maps onto the report's design tokens, which is
// what lets one drawing hold in both themes.

export type FrameKind = 'phone-notch' | 'phone' | 'tablet' | 'browser';

export interface FrameInfo {
  platform?: string | null;
  classifier?: string | null;
  /** The device's name in the trail, e.g. "seller"; read as a hint when nothing else says. */
  name?: string | null;
  screen?: { w: number; h: number } | null;
}

/** The enclosure that best stands for a device: by what the run says it is, else by its shape. */
export function frameKindFor(info: FrameInfo): FrameKind {
  const id = `${info.classifier || ''} ${info.platform || ''} ${info.name || ''}`.toLowerCase();
  const screen = info.screen && info.screen.w > 0 && info.screen.h > 0 ? info.screen : null;
  const aspect = screen ? Math.max(screen.w, screen.h) / Math.min(screen.w, screen.h) : 0;
  if (/\b(web|browser|chrome|desktop)\b/.test(id) || /^web/.test(info.classifier || '')) return 'browser';
  if (/ipad|tablet|kiosk/.test(id)) return 'tablet';
  // Phones run upright at about 2:1. A screen at 16:10 or squarer is a tablet either way up, and a
  // landscape 16:9 one is a tablet or a wide external display, which run that way; an upright 16:9 is an
  // older phone.
  const landscape = !!screen && screen.w > screen.h;
  const squat = aspect > 0 && (aspect < 1.7 || (landscape && aspect < 1.9));
  if (/ios|iphone/.test(id)) return squat ? 'tablet' : 'phone-notch';
  if (/android/.test(id)) return squat ? 'tablet' : 'phone';
  return squat ? 'tablet' : 'phone';
}

/** A screen of this kind's usual shape, for a device that never said how big its screen is. */
export const DEFAULT_SCREEN: Record<FrameKind, { w: number; h: number }> = {
  'phone-notch': { w: 402, h: 874 },
  phone: { w: 1080, h: 2400 },
  tablet: { w: 820, h: 1180 },
  browser: { w: 1440, h: 900 },
};

export const FRAME_WIDTH = 1000;
// A slim bar: a frame shown about 1000px wide keeps it near a real browser's size.
const CHROME_HEIGHT = 30;

interface Build {
  height: number;
  screen: { x: number; y: number; w: number; h: number; r: number };
  art: string;
}

const round = (value: number) => Math.round(value * 100) / 100;

/** A rounded rectangle as a closed path, so an enclosure and its opening can share one evenodd fill. */
function roundedRect(x: number, y: number, w: number, h: number, r: number): string {
  const radius = Math.min(r, w / 2, h / 2);
  return `M${round(x + radius)} ${round(y)}H${round(x + w - radius)}A${round(radius)} ${round(radius)} 0 0 1 ${round(x + w)} ${round(y + radius)}`
    + `V${round(y + h - radius)}A${round(radius)} ${round(radius)} 0 0 1 ${round(x + w - radius)} ${round(y + h)}`
    + `H${round(x + radius)}A${round(radius)} ${round(radius)} 0 0 1 ${round(x)} ${round(y + h - radius)}`
    + `V${round(y + radius)}A${round(radius)} ${round(radius)} 0 0 1 ${round(x + radius)} ${round(y)}Z`;
}

interface Spec {
  /** Margin outside the body, where side buttons stand out of it. */
  pad: number;
  bezel: number;
  radius: number;
  inner: number;
}

const SPECS: Record<Exclude<FrameKind, 'browser'>, Spec> = {
  'phone-notch': { pad: 12, bezel: 30, radius: 150, inner: 120 },
  phone: { pad: 12, bezel: 26, radius: 112, inner: 88 },
  tablet: { pad: 8, bezel: 54, radius: 78, inner: 24 },
};

/** The decoration (notch, camera, side buttons) in the device's own upright coordinates: `w` x `h`
 * is the body's canvas, `top` where the screen opening starts. A landscape device turns it. */
function decor(kind: Exclude<FrameKind, 'browser'>, w: number, h: number, spec: Spec): string {
  const cx = w / 2;
  const top = spec.pad + spec.bezel;
  const button = (x: number, y: number, bh: number, side: 'left' | 'right') =>
    `<rect class="devhwrim" x="${side === 'left' ? x - 7 : x}" y="${y}" width="7" height="${bh}" rx="3"/>`;
  if (kind === 'phone-notch') {
    const half = 168;
    const notch = `M${cx - half} ${top}H${cx + half}V${top + 22}A38 38 0 0 1 ${cx + half - 38} ${top + 60}H${cx - half + 38}A38 38 0 0 1 ${cx - half} ${top + 22}Z`;
    return `<path class="devhw" d="${notch}"/>`
      + `<rect class="devdark" x="${cx - 56}" y="${top + 17}" width="112" height="14" rx="7"/>`
      + `<circle class="devdark" cx="${cx + 92}" cy="${top + 24}" r="10"/>`
      + button(spec.pad, 190, 56, 'left') + button(spec.pad, 290, 104, 'left') + button(spec.pad, 420, 104, 'left')
      + button(w - spec.pad, 330, 170, 'right');
  }
  if (kind === 'phone') {
    return `<circle class="devdark" cx="${cx}" cy="${top + 36}" r="17"/>`
      + button(w - spec.pad, 300, 150, 'right') + button(w - spec.pad, 500, 70, 'right');
  }
  return `<circle class="devdark" cx="${cx}" cy="${spec.pad + spec.bezel / 2}" r="7"/>`;
}

function buildHandheld(kind: Exclude<FrameKind, 'browser'>, content: { w: number; h: number }): Build {
  const spec = SPECS[kind];
  const landscape = content.w > content.h;
  const edge = spec.pad + spec.bezel;
  const screenW = FRAME_WIDTH - 2 * edge;
  const screenH = round((screenW * content.h) / content.w);
  const height = round(screenH + 2 * edge);
  const body = roundedRect(spec.pad, spec.pad, FRAME_WIDTH - 2 * spec.pad, height - 2 * spec.pad, spec.radius);
  const opening = roundedRect(edge, edge, screenW, screenH, spec.inner);
  // The decoration is drawn upright and turned a quarter when the device lies on its side.
  const upright = decor(kind, landscape ? height : FRAME_WIDTH, landscape ? FRAME_WIDTH : height, spec);
  const art = `<path class="devbody" fill-rule="evenodd" d="${body}${opening}"/>`
    + `<path class="devrimline" d="${body}"/>`
    + (landscape ? `<g transform="translate(0 ${height}) rotate(-90)">${upright}</g>` : upright);
  return { height, screen: { x: edge, y: edge, w: screenW, h: screenH, r: spec.inner }, art };
}

function buildBrowser(content: { w: number; h: number }): Build {
  const screenH = round((FRAME_WIDTH * content.h) / content.w);
  const height = CHROME_HEIGHT + screenH;
  // The chrome is HTML (see `deviceFrame`), so the vector layer only draws the window's outline.
  const art = `<path class="devrimline" d="${roundedRect(1, 1, FRAME_WIDTH - 2, height - 2, 10)}"/>`;
  return { height, screen: { x: 0, y: CHROME_HEIGHT, w: FRAME_WIDTH, h: screenH, r: 0 }, art };
}

export interface DeviceFrameMarkup {
  html: string;
  /** Height over width: what the stage lays the frame out by. */
  ratio: number;
}

/** A frame's markup around an empty screen slot (`[data-story-screen]`) the stage fills with the
 * recording. `url` is the browser window's address, kept in `[data-story-url]` for the stage to update. */
export function deviceFrame(kind: FrameKind, screen: { w: number; h: number }, options: { url?: string } = {}): DeviceFrameMarkup {
  const content = screen.w > 0 && screen.h > 0 ? screen : DEFAULT_SCREEN[kind];
  const build = kind === 'browser' ? buildBrowser(content) : buildHandheld(kind, content);
  const s = build.screen;
  const chrome = kind === 'browser'
    ? `<div class="devchrome"><span class="devlights"><i></i><i></i><i></i></span><span class="devurl" data-story-url>${escapeText(options.url || '')}</span></div>`
    : '';
  const html = `<div class="devframe" data-device="${kind}" style="aspect-ratio:${FRAME_WIDTH}/${build.height}">`
    + chrome
    + `<div class="devframescreen" style="left:${s.x}px;top:${s.y}px;width:${s.w}px;height:${s.h}px;border-radius:${kind === 'browser' ? '0 0 9px 9px' : `${s.r}px`}">`
    + `<div class="devframecontent" data-story-screen></div></div>`
    + `<svg class="devframeart" viewBox="0 0 ${FRAME_WIDTH} ${build.height}" preserveAspectRatio="none" aria-hidden="true">${build.art}</svg>`
    + `</div>`;
  return { html, ratio: build.height / FRAME_WIDTH };
}

function escapeText(text: string): string {
  return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

/** Stylesheet for the frames; `--story-hw` and friends are mapped to design tokens by the Theater's. */
export const DEVICE_FRAME_CSS = `
.devframe { position: relative; display: block; width: 100%; isolation: isolate; }
.devframescreen { position: absolute; overflow: hidden; background: var(--story-screen, #000); }
.devframecontent { position: absolute; inset: 0; transform-origin: 0 0; }
.devframeart { position: absolute; inset: 0; width: 100%; height: 100%; pointer-events: none; }
.devbody { fill: var(--story-hw); }
.devhw { fill: var(--story-hw); }
.devhwrim { fill: var(--story-hw-rim); }
.devdark { fill: var(--story-hw-deep); }
.devrimline { fill: none; stroke: var(--story-hw-rim); stroke-width: 3; }
.devframe[data-device="browser"] { overflow: hidden; border-radius: 10px; background: var(--story-hw); }
.devchrome { position: absolute; top: 0; right: 0; left: 0; display: flex; align-items: center; gap: 10px; height: ${CHROME_HEIGHT}px; padding: 0 10px; background: var(--story-hw); border-bottom: 1px solid var(--story-hw-rim); }
.devlights { display: flex; gap: 6px; flex: none; }
.devlights i { width: 9px; height: 9px; border-radius: 50%; background: var(--story-hw-rim); }
.devlights i:nth-child(1) { background: var(--fail); }
.devlights i:nth-child(2) { background: var(--amber); }
.devlights i:nth-child(3) { background: var(--pass); }
/* A fixed height: an address bar with no address yet is as tall as one with it. */
.devurl { flex: 1; min-width: 0; box-sizing: border-box; height: 20px; padding: 3px 10px; overflow: hidden; border-radius: 999px; background: var(--story-hw-deep); color: color-mix(in oklab, var(--story-ink) 62%, var(--story-hw)); font: 12px/14px ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; text-overflow: ellipsis; white-space: nowrap; }
`;
