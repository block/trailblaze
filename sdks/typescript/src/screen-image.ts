// `ctx.screen.image()`: the pixels of one rectangle of the screen, or the whole screen, for a
// tool's own visual checks ("the theme is dark", "the sticker is on the card", "the frame changed").
//
// Backed by the Kotlin `readScreenImage` tool, which runs wherever the agent runs (the host JVM, or
// the device itself), crops a fresh driver screenshot and returns raw RGBA as base64. The base64 is
// decoded here in plain TypeScript because the on-device QuickJS runtime has neither `atob` nor
// `Buffer`.

import type { TrailblazeToolMethods } from "./client.js";
import type { ReadScreenImageResult } from "./generated/built-in-tool-results.js";
import type { Bounds } from "./generated/selectors.js";

/** One pixel. Channels are 0–255. */
export interface Rgba {
  r: number;
  g: number;
  b: number;
  a: number;
}

/** Decides whether one pixel counts. Takes channels, not an object, so scanning allocates nothing. */
export type PixelPredicate = (r: number, g: number, b: number, a: number) => boolean;

/** A rectangle of an image's own pixels. `right` and `bottom` are exclusive. */
export interface PixelBox {
  left: number;
  top: number;
  right: number;
  bottom: number;
}

export interface ScreenImage {
  readonly width: number;
  readonly height: number;
  /** RGBA, row-major, 4 bytes per pixel. */
  readonly data: Uint8Array;
  /**
   * True when the screenshot kept exact colours (PNG, lossless WebP). False on the drivers' default
   * lossy WebP, where a colour can drift by a few levels: match with a tolerance.
   */
  readonly lossless: boolean;
  pixel(x: number, y: number): Rgba;
  /** How many pixels [match] accepts. */
  count(match: PixelPredicate): number;
  /** The smallest box holding every pixel [match] accepts, or null when it accepts none. */
  boundingBox(match: PixelPredicate): PixelBox | null;
  /** True when [other] has the same size and every byte matches: "the frame did not change". */
  equals(other: ScreenImage): boolean;
}

export interface ScreenImageOptions {
  /**
   * Rectangle to read, in the driver's coordinates: the same space as `MatchDescriptor.bounds`, so
   * an element's bounds pass straight through. Omit for the whole screen.
   */
  bounds?: Bounds;
  /**
   * Downscale so the longer side is at most this many pixels. Default 512, which leaves
   * element-sized reads untouched and keeps whole-screen reads quick to decode on a device.
   */
  maxDimension?: number;
}

/** `ctx.screen`: reads of what is on the device's screen right now. */
export interface TrailblazeScreen {
  /**
   * Captures the screen and returns the pixels of [options.bounds], or of the whole screen.
   *
   *   const [[button]] = await ctx.tools.findSelectorMatches({ selectors: [chargeButton] });
   *   const img = await ctx.screen.image({ bounds: button.bounds! });
   *   const dark = img.count((r, g, b) => r <= 96 && g <= 96 && b <= 96) / (img.width * img.height);
   *
   * Throws when the bounds are off screen or the whole capture is black (a secure window, or a
   * failed capture), so a check never passes on pixels that were not there.
   */
  image(options?: ScreenImageOptions): Promise<ScreenImage>;
}

export function createScreen(tools: TrailblazeToolMethods): TrailblazeScreen {
  return {
    async image(options: ScreenImageOptions = {}) {
      const args: { bounds?: Bounds; maxDimension?: number } = {};
      if (options.bounds !== undefined) args.bounds = options.bounds;
      if (options.maxDimension !== undefined) args.maxDimension = options.maxDimension;
      return screenImageFromResult(await tools.readScreenImage(args));
    },
  };
}

/** Wraps a `readScreenImage` result. Exported for tests that build images without a device. */
export function screenImageFromResult(result: ReadScreenImageResult): ScreenImage {
  const data = decodeBase64(result.data);
  return screenImageFromRgba(result.width, result.height, data, result.lossless);
}

/** A [ScreenImage] over RGBA bytes already in hand. */
export function screenImageFromRgba(
  width: number,
  height: number,
  data: Uint8Array,
  lossless: boolean,
): ScreenImage {
  if (data.length !== width * height * 4) {
    throw new Error(`screen image: ${data.length} bytes for ${width}x${height} pixels`);
  }
  const count = (match: PixelPredicate) => {
    let n = 0;
    for (let i = 0; i < data.length; i += 4) {
      if (match(data[i], data[i + 1], data[i + 2], data[i + 3])) n++;
    }
    return n;
  };
  const image: ScreenImage = {
    width,
    height,
    data,
    lossless,
    pixel(x, y) {
      if (x < 0 || y < 0 || x >= width || y >= height || !Number.isInteger(x) || !Number.isInteger(y)) {
        throw new RangeError(`pixel(${x}, ${y}) is outside the ${width}x${height} image`);
      }
      const i = (y * width + x) * 4;
      return { r: data[i], g: data[i + 1], b: data[i + 2], a: data[i + 3] };
    },
    count,
    boundingBox(match) {
      let left = width;
      let top = height;
      let right = -1;
      let bottom = -1;
      for (let y = 0, i = 0; y < height; y++) {
        for (let x = 0; x < width; x++, i += 4) {
          if (!match(data[i], data[i + 1], data[i + 2], data[i + 3])) continue;
          if (x < left) left = x;
          if (x > right) right = x;
          if (y < top) top = y;
          bottom = y;
        }
      }
      return right < 0 ? null : { left, top, right: right + 1, bottom: bottom + 1 };
    },
    equals(other) {
      if (other.width !== width || other.height !== height) return false;
      const theirs = other.data;
      for (let i = 0; i < data.length; i++) if (data[i] !== theirs[i]) return false;
      return true;
    },
  };
  return image;
}

const B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
const B64_LOOKUP = (() => {
  const table = new Uint8Array(128);
  for (let i = 0; i < B64.length; i++) table[B64.charCodeAt(i)] = i;
  return table;
})();

/** Standard padded base64 to bytes, in plain TS so it runs where `atob` and `Buffer` don't exist. */
export function decodeBase64(s: string): Uint8Array {
  if (s.length % 4 !== 0) throw new Error(`base64 length ${s.length} is not a multiple of 4`);
  const pad = s.endsWith("==") ? 2 : s.endsWith("=") ? 1 : 0;
  const out = new Uint8Array((s.length / 4) * 3 - pad);
  let o = 0;
  for (let i = 0; i < s.length; i += 4) {
    const n =
      (B64_LOOKUP[s.charCodeAt(i)] << 18) |
      (B64_LOOKUP[s.charCodeAt(i + 1)] << 12) |
      (B64_LOOKUP[s.charCodeAt(i + 2)] << 6) |
      B64_LOOKUP[s.charCodeAt(i + 3)];
    if (o < out.length) out[o++] = (n >> 16) & 0xff;
    if (o < out.length) out[o++] = (n >> 8) & 0xff;
    if (o < out.length) out[o++] = n & 0xff;
  }
  return out;
}
