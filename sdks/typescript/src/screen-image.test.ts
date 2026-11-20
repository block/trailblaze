// `ctx.screen.image()` and the `ScreenImage` helpers, on images drawn in the test. What the Kotlin
// `readScreenImage` tool captures and crops is covered on the Kotlin side.

import { describe, expect, test } from "bun:test";

import type { ReadScreenImageResult } from "./generated/built-in-tool-results.js";
import type { Bounds } from "./generated/selectors.js";
import {
  decodeBase64,
  screenImageFromResult,
  screenImageFromRgba,
  type PixelPredicate,
  type ScreenImage,
} from "./screen-image.js";
import { tool } from "./tool.js";

type Color = readonly [number, number, number];
const WHITE: Color = [255, 255, 255];
const BLACK: Color = [0, 0, 0];
const NEAR_BLACK: Color = [28, 28, 30];
const CYAN: Color = [0, 200, 220];

/** A drawable RGBA canvas. */
class Canvas {
  readonly data: Uint8Array;
  constructor(readonly width: number, readonly height: number, background: Color) {
    this.data = new Uint8Array(width * height * 4);
    this.fill({ left: 0, top: 0, right: width, bottom: height }, background);
  }
  fill(box: Bounds, [r, g, b]: Color): this {
    for (let y = box.top; y < box.bottom; y++) {
      for (let x = box.left; x < box.right; x++) {
        const i = (y * this.width + x) * 4;
        this.data.set([r, g, b, 255], i);
      }
    }
    return this;
  }
  /** Horizontal one-pixel strokes every third row: enough "ink" to read as text. */
  text(box: Bounds, color: Color): this {
    for (let y = box.top + 2; y < box.bottom - 2; y += 3) this.fill({ left: box.left + 2, top: y, right: box.right - 2, bottom: y + 1 }, color);
    return this;
  }
  image(): ScreenImage {
    return screenImageFromRgba(this.width, this.height, this.data, true);
  }
  /** What the Kotlin tool returns for [bounds] of this canvas, at 1:1 scale. */
  read(bounds?: Bounds): ReadScreenImageResult {
    const box = bounds ?? { left: 0, top: 0, right: this.width, bottom: this.height };
    const w = box.right - box.left;
    const h = box.bottom - box.top;
    const out = new Uint8Array(w * h * 4);
    for (let y = 0; y < h; y++) {
      const from = ((box.top + y) * this.width + box.left) * 4;
      out.set(this.data.subarray(from, from + w * 4), y * w * 4);
    }
    return {
      width: w,
      height: h,
      data: Buffer.from(out).toString("base64"),
      lossless: true,
      pixelRect: box,
      source: { encoding: "png", width: this.width, height: this.height, scaleX: 1, scaleY: 1 },
    };
  }
}

/** A client whose `readScreenImage` reads [canvas] and records each call's arguments. */
function canvasClient(canvas: Canvas, calls: unknown[] = []) {
  return {
    tools: {
      readScreenImage: async (args: { bounds?: Bounds }) => {
        calls.push(args);
        return canvas.read(args.bounds);
      },
    },
    host: {},
  } as never;
}

describe("decodeBase64", () => {
  test("matches the platform decoder at every padding length", () => {
    for (const bytes of [[], [1], [1, 2], [0, 106, 255], [0, 106, 255, 255, 1, 2, 3]]) {
      const encoded = Buffer.from(bytes).toString("base64");
      expect(Array.from(decodeBase64(encoded))).toEqual(bytes);
    }
  });

  test("decodes every byte value", () => {
    const all = Uint8Array.from({ length: 256 }, (_, i) => i);
    expect(decodeBase64(Buffer.from(all).toString("base64"))).toEqual(all);
  });
});

describe("ScreenImage", () => {
  const isCyan: PixelPredicate = (r, g, b) => r < 60 && g > 160 && b > 160;

  test("pixel reads RGBA and refuses coordinates outside the image", () => {
    const img = new Canvas(4, 3, WHITE).fill({ left: 2, top: 1, right: 3, bottom: 2 }, CYAN).image();
    expect(img.pixel(2, 1)).toEqual({ r: 0, g: 200, b: 220, a: 255 });
    expect(img.pixel(0, 0)).toEqual({ r: 255, g: 255, b: 255, a: 255 });
    expect(() => img.pixel(4, 0)).toThrow(RangeError);
    expect(() => img.pixel(0, -1)).toThrow(RangeError);
  });

  test("count and boundingBox find the pixels a predicate accepts", () => {
    const img = new Canvas(50, 40, WHITE).fill({ left: 10, top: 5, right: 20, bottom: 25 }, CYAN).image();
    expect(img.count(isCyan)).toBe(200);
    expect(img.boundingBox(isCyan)).toEqual({ left: 10, top: 5, right: 20, bottom: 25 });
  });

  test("boundingBox spans separate regions, and is null when nothing matches", () => {
    const img = new Canvas(50, 40, WHITE)
      .fill({ left: 2, top: 30, right: 4, bottom: 32 }, CYAN)
      .fill({ left: 40, top: 3, right: 41, bottom: 4 }, CYAN)
      .image();
    expect(img.boundingBox(isCyan)).toEqual({ left: 2, top: 3, right: 41, bottom: 32 });
    expect(new Canvas(5, 5, WHITE).image().boundingBox(isCyan)).toBeNull();
  });

  test("equals compares size and every byte", () => {
    const a = new Canvas(8, 8, WHITE).image();
    expect(a.equals(new Canvas(8, 8, WHITE).image())).toBe(true);
    expect(a.equals(new Canvas(8, 8, WHITE).fill({ left: 7, top: 7, right: 8, bottom: 8 }, [254, 255, 255]).image())).toBe(false);
    expect(a.equals(new Canvas(8, 4, WHITE).image())).toBe(false);
  });

  test("rejects a result whose bytes don't fill its size", () => {
    const result = new Canvas(2, 2, WHITE).read();
    expect(() => screenImageFromResult({ ...result, width: 3 })).toThrow("for 3x2 pixels");
  });
});

describe("ctx.screen.image", () => {
  test("passes bounds and maxDimension to readScreenImage and decodes its result", async () => {
    const canvas = new Canvas(40, 30, WHITE).fill({ left: 10, top: 10, right: 20, bottom: 15 }, CYAN);
    const calls: unknown[] = [];
    let crop: ScreenImage | undefined;
    const def = tool(async (_input, ctx) => {
      crop = await ctx.screen.image({ bounds: { left: 10, top: 10, right: 20, bottom: 15 } });
      await ctx.screen.image({ maxDimension: 16 });
      await ctx.screen.image();
      return "ok";
    });
    await def({}, {} as never, canvasClient(canvas, calls));
    expect(calls).toEqual([{ bounds: { left: 10, top: 10, right: 20, bottom: 15 } }, { maxDimension: 16 }, {}]);
    expect(crop!.width).toBe(10);
    expect(crop!.height).toBe(5);
    expect(crop!.lossless).toBe(true);
    expect(crop!.pixel(0, 0)).toEqual({ r: 0, g: 200, b: 220, a: 255 });
  });
});

// A checkout theme check, positioned by the elements' own bounds rather than
// fixed screen fractions, written against this API the way a team tool would.
const Theme = { Light: "LIGHT", Dark: "DARK" } as const;
type Theme = (typeof Theme)[keyof typeof Theme];

// The tool, not the SDK, decides what counts as dark or light.
const isDark: PixelPredicate = (r, g, b) => r <= 96 && g <= 96 && b <= 96;
const isLight: PixelPredicate = (r, g, b) => r >= 160 && g >= 160 && b >= 160;
const share = (img: ScreenImage, match: PixelPredicate) => img.count(match) / (img.width * img.height);

async function assertTheme(
  screen: { image(options?: { bounds?: Bounds }): Promise<ScreenImage> },
  labels: Record<string, Bounds>,
  theme: Theme,
) {
  const whole = await screen.image();
  const background = share(whole, theme === Theme.Dark ? isDark : isLight);
  if (background < 0.6) {
    throw new Error(`expected ${theme}: only ${(background * 100).toFixed(0)}% of the screen matches`);
  }
  for (const [name, bounds] of Object.entries(labels)) {
    const crop = await screen.image({ bounds });
    const [text, surface] = theme === Theme.Dark
      ? [share(crop, isLight), share(crop, isDark)]
      : [share(crop, isDark), share(crop, isLight)];
    if (!(text >= 0.05 && surface >= 0.5)) {
      throw new Error(`no readable text at ${name} (text ${(text * 100).toFixed(0)}%, surface ${(surface * 100).toFixed(0)}%)`);
    }
  }
  return { theme, screenShare: +background.toFixed(3) };
}

describe("checkout theme check, positioned by element bounds", () => {
  // A tablet checkout whose Charge button sits at 73–79% of the screen's height: the band a check
  // pinned to fixed fractions (83–87%) would miss.
  const W = 400;
  const H = 600;
  const labels = {
    total: { left: 40, top: 60, right: 200, bottom: 90 },
    charge: { left: 40, top: Math.round(H * 0.73), right: 360, bottom: Math.round(H * 0.79) },
  };
  const checkout = (background: Color, ink: Color) =>
    new Canvas(W, H, background).text(labels.total, ink).text(labels.charge, ink);

  async function check(canvas: Canvas, theme: Theme) {
    let outcome: unknown;
    const def = tool(async (_input, ctx) => {
      outcome = await assertTheme(ctx.screen, labels, theme);
      return "ok";
    });
    await def({}, {} as never, canvasClient(canvas));
    return outcome;
  }

  test("passes a light checkout as LIGHT and rejects it as DARK", async () => {
    const light = checkout(WHITE, NEAR_BLACK);
    expect(await check(light, Theme.Light)).toEqual({ theme: Theme.Light, screenShare: expect.any(Number) });
    await expect(check(light, Theme.Dark)).rejects.toThrow("expected DARK");
  });

  test("passes a dark checkout as DARK and rejects it as LIGHT", async () => {
    const dark = checkout(NEAR_BLACK, WHITE);
    expect(await check(dark, Theme.Dark)).toEqual({ theme: Theme.Dark, screenShare: expect.any(Number) });
    await expect(check(dark, Theme.Light)).rejects.toThrow("expected LIGHT");
  });

  test("rejects an all-black capture: dark everywhere, but no text where the labels are", async () => {
    const black = new Canvas(W, H, BLACK);
    await expect(check(black, Theme.Dark)).rejects.toThrow("no readable text at total");
    await expect(check(black, Theme.Light)).rejects.toThrow("expected LIGHT");
  });
});
