// Colour building blocks for a tool's own pixel checks over `ctx.screen.image()`: parse and print
// `#RRGGBB`, count a region's colours, and measure how different two colours look (CIEDE2000, the
// scale where about 2 is the smallest difference a person notices).
//
//   const img = await ctx.screen.image({ bounds });
//   const brand = rgbToLab(parseHex("#006AFF")!);
//   let near = 0;
//   for (const [rgb, n] of colorCounts(img)) if (deltaE2000(rgbToLab(rgb), brand) <= 5) near += n;
//   return `${percent(near / (img.width * img.height))} of the button is brand blue`;
//
// Colours are numbers, `0xRRGGBB`. Plain TypeScript with no imports at runtime, so it runs in the
// on-device QuickJS runtime as well as on the host.

import type { ScreenImage } from "./screen-image.js";

/** An sRGB colour as one number, `0xRRGGBB`. */
export type Rgb = number;

/** A CIE L*a*b* (D65) colour: lightness 0–100, then the green–red and blue–yellow axes. */
export type Lab = readonly [l: number, a: number, b: number];

/** One colour's share of an image. */
export interface ColorShare {
  hex: string;
  /** 0–1. */
  share: number;
}

/** `0.9893` → `98.9%`. Shares below 1% keep two decimals so a text floor stays readable. */
export function percent(share: number): string {
  const p = share * 100;
  return `${p >= 1 || p === 0 ? p.toFixed(1) : p.toFixed(2)}%`;
}

/**
 * The [limit] most common colours in [image] with their shares. On a lossy read, channels are
 * rounded to the nearest multiple of 0x11 first, so compression noise doesn't split one colour into
 * dozens; pure black and white survive the rounding exactly.
 */
export function topColorShares(image: ScreenImage, limit: number): ColorShare[] {
  const counts = colorCounts(image, image.lossless ? (c) => c : roundChannel);
  const total = image.width * image.height;
  return [...counts.entries()]
    .sort((a, b) => b[1] - a[1] || a[0] - b[0])
    .slice(0, limit)
    .map(([rgb, n]) => ({ hex: rgbHex(rgb), share: n / total }));
}

/** Pixel count per colour, keyed `0xRRGGBB` after [mapChannel]. Alpha is ignored: screens are opaque. */
export function colorCounts(image: ScreenImage, mapChannel: (c: number) => number = (c) => c): Map<Rgb, number> {
  const counts = new Map<Rgb, number>();
  const data = image.data;
  for (let i = 0; i < data.length; i += 4) {
    const key = (mapChannel(data[i]) << 16) | (mapChannel(data[i + 1]) << 8) | mapChannel(data[i + 2]);
    counts.set(key, (counts.get(key) ?? 0) + 1);
  }
  return counts;
}

const roundChannel = (c: number) => Math.round(c / 0x11) * 0x11;

/** `0xRRGGBB` to `#RRGGBB`. */
export function rgbHex(rgb: Rgb): string {
  return `#${rgb.toString(16).toUpperCase().padStart(6, "0")}`;
}

/** `#RRGGBB` (or `RRGGBB`) to `0xRRGGBB`, or null when it isn't six hex digits. */
export function parseHex(hex: string): Rgb | null {
  const m = /^#?([0-9a-fA-F]{6})$/.exec(String(hex ?? "").trim());
  return m ? parseInt(m[1], 16) : null;
}

/** CIE L*a*b* (D65) of an sRGB colour `0xRRGGBB`. */
export function rgbToLab(rgb: Rgb): Lab {
  const lin = (c: number) => {
    const s = c / 255;
    return s <= 0.04045 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
  };
  const r = lin((rgb >> 16) & 0xff);
  const g = lin((rgb >> 8) & 0xff);
  const b = lin(rgb & 0xff);
  const x = (0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / 0.95047;
  const y = 0.2126729 * r + 0.7151522 * g + 0.072175 * b;
  const z = (0.0193339 * r + 0.119192 * g + 0.9503041 * b) / 1.08883;
  const f = (t: number) => (t > 216 / 24389 ? Math.cbrt(t) : ((24389 / 27) * t + 16) / 116);
  const fx = f(x);
  const fy = f(y);
  const fz = f(z);
  return [116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz)];
}

/** CIEDE2000 colour difference between two L*a*b* colours. About 2 is the smallest a person notices. */
export function deltaE2000(lab1: Lab, lab2: Lab): number {
  const [l1, a1, b1] = lab1;
  const [l2, a2, b2] = lab2;
  const rad = Math.PI / 180;
  const pow25to7 = 6103515625; // 25^7
  const cBar = (Math.hypot(a1, b1) + Math.hypot(a2, b2)) / 2;
  const g = 0.5 * (1 - Math.sqrt(cBar ** 7 / (cBar ** 7 + pow25to7)));
  const a1p = (1 + g) * a1;
  const a2p = (1 + g) * a2;
  const c1p = Math.hypot(a1p, b1);
  const c2p = Math.hypot(a2p, b2);
  const hue = (b: number, a: number) => {
    if (a === 0 && b === 0) return 0;
    const h = Math.atan2(b, a) / rad;
    return h < 0 ? h + 360 : h;
  };
  const h1p = hue(b1, a1p);
  const h2p = hue(b2, a2p);

  const dLp = l2 - l1;
  const dCp = c2p - c1p;
  let dhp = 0;
  if (c1p * c2p !== 0) {
    dhp = h2p - h1p;
    if (dhp > 180) dhp -= 360;
    else if (dhp < -180) dhp += 360;
  }
  const dHp = 2 * Math.sqrt(c1p * c2p) * Math.sin((dhp / 2) * rad);

  const lBarP = (l1 + l2) / 2;
  const cBarP = (c1p + c2p) / 2;
  let hBarP = h1p + h2p;
  if (c1p * c2p !== 0) {
    if (Math.abs(h1p - h2p) <= 180) hBarP = (h1p + h2p) / 2;
    else hBarP = h1p + h2p < 360 ? (h1p + h2p + 360) / 2 : (h1p + h2p - 360) / 2;
  }
  const t =
    1 -
    0.17 * Math.cos((hBarP - 30) * rad) +
    0.24 * Math.cos(2 * hBarP * rad) +
    0.32 * Math.cos((3 * hBarP + 6) * rad) -
    0.2 * Math.cos((4 * hBarP - 63) * rad);
  const dTheta = 30 * Math.exp(-(((hBarP - 275) / 25) ** 2));
  const rc = 2 * Math.sqrt(cBarP ** 7 / (cBarP ** 7 + pow25to7));
  const lDev = (lBarP - 50) ** 2;
  const sl = 1 + (0.015 * lDev) / Math.sqrt(20 + lDev);
  const sc = 1 + 0.045 * cBarP;
  const sh = 1 + 0.015 * cBarP * t;
  const rt = -Math.sin(2 * dTheta * rad) * rc;
  const l = dLp / sl;
  const c = dCp / sc;
  const h = dHp / sh;
  return Math.sqrt(l * l + c * c + h * h + rt * c * h);
}
