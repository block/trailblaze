// The colour building blocks a tool composes into its own pixel checks. Images are drawn in the test.

import { describe, expect, test } from "bun:test";

import { colorCounts, deltaE2000, parseHex, percent, rgbHex, rgbToLab, topColorShares, type Lab } from "./color.js";
import { screenImageFromRgba } from "./screen-image.js";

/** A one-row image of [colors], left to right. */
function strip(colors: number[], lossless = true) {
  const data = new Uint8Array(colors.length * 4);
  colors.forEach((rgb, i) => data.set([(rgb >> 16) & 0xff, (rgb >> 8) & 0xff, rgb & 0xff, 255], i * 4));
  return screenImageFromRgba(colors.length, 1, data, lossless);
}

describe("hex colours", () => {
  test("parse #RRGGBB with or without the #, in either case", () => {
    expect(parseHex("#006AFF")).toBe(0x006aff);
    expect(parseHex("006aff")).toBe(0x006aff);
    expect(parseHex(" #000000 ")).toBe(0);
  });

  test("refuse anything that isn't six hex digits", () => {
    for (const bad of ["#06F", "#006AFF00", "blue", ""]) expect(parseHex(bad)).toBeNull();
  });

  test("print as upper-case #RRGGBB", () => {
    expect(rgbHex(0x006aff)).toBe("#006AFF");
    expect(rgbHex(0)).toBe("#000000");
  });
});

describe("percent", () => {
  test("one decimal from 1%, two below it so a small share stays readable", () => {
    expect(percent(0.9893)).toBe("98.9%");
    expect(percent(0.0034)).toBe("0.34%");
    expect(percent(0)).toBe("0.0%");
  });
});

describe("counting colours", () => {
  test("counts each colour's pixels", () => {
    const counts = colorCounts(strip([0x000000, 0x000000, 0xffffff]));
    expect(counts.get(0x000000)).toBe(2);
    expect(counts.get(0xffffff)).toBe(1);
  });

  test("top colours are the most common first, with their shares", () => {
    expect(topColorShares(strip([0xffffff, 0x000000, 0x000000, 0x000000]), 1)).toEqual([{ hex: "#000000", share: 0.75 }]);
  });

  test("on a lossy read, compression noise doesn't split one colour into several", () => {
    const noisy = strip([0x000000, 0x020101, 0x010200, 0xffffff], false);
    expect(topColorShares(noisy, 2)).toEqual([
      { hex: "#000000", share: 0.75 },
      { hex: "#FFFFFF", share: 0.25 },
    ]);
  });
});

describe("colour difference", () => {
  test("black against white is 100, and a colour against itself is 0", () => {
    expect(deltaE2000(rgbToLab(0x000000), rgbToLab(0xffffff))).toBeCloseTo(100, 4);
    expect(deltaE2000(rgbToLab(0x006aff), rgbToLab(0x006aff))).toBe(0);
  });
});

describe("deltaE2000 matches the published reference pairs", () => {
  // Reference pairs from Sharma, Wu and Dalal, "The CIEDE2000 Color-Difference Formula" (2005).
  const pairs: Array<[Lab, Lab, number]> = [
    [[50, 2.6772, -79.7751], [50, 0, -82.7485], 2.0425],
    [[50, 0, 0], [50, -1, 2], 2.3669],
    [[50, 2.5, 0], [73, 25, -18], 27.1492],
    [[60.2574, -34.0099, 36.2677], [60.4626, -34.1751, 39.4387], 1.2644],
    [[22.7233, 20.0904, -46.694], [23.0331, 14.973, -42.5619], 2.0373],
  ];
  for (const [a, b, expected] of pairs) {
    test(`${a} vs ${b} = ${expected}`, () => {
      expect(deltaE2000(a, b)).toBeCloseTo(expected, 4);
      expect(deltaE2000(b, a)).toBeCloseTo(expected, 4);
    });
  }
});
