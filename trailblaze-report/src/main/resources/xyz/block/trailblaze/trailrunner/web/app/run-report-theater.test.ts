import { afterEach, describe, expect, test } from 'bun:test';
import { cleanMark, markPlacer, modalOpen, openingAt, swipeSvg } from './run-report-theater';
import type { TheaterMark } from './run-report-theater-model';

const swipe = (over: Record<string, unknown> = {}): TheaterMark =>
  ({ kind: 'swipe', t: 0, x: 10, y: 20, x2: 30, y2: 40, dw: 100, dh: 200, ok: true, ...over }) as TheaterMark;

describe('opening position', () => {
  test('an explicit navigation always wins, even when it equals where the reader left off', () => {
    expect(openingAt(true, 5000, 5000)).toBe(5000);
    expect(openingAt(true, 1000, 9000)).toBe(1000);
  });

  test('a plain re-render keeps the saved playhead over the route', () => {
    expect(openingAt(false, 1000, 9000)).toBe(9000);
    expect(openingAt(undefined, 1000, 9000)).toBe(9000);
  });

  test('with nothing saved the route is used, else the start', () => {
    expect(openingAt(false, 1000, undefined)).toBe(1000);
    expect(openingAt(true, null, 9000)).toBe(9000);
    expect(openingAt(undefined, null, undefined)).toBe(0);
  });
});

describe('marks drawn into markup', () => {
  test('a swipe with numeric coordinates keeps them, coerced to numbers', () => {
    const clean = cleanMark(swipe({ x: '10', y: 20.5 }))!;
    expect(clean.x).toBe(10);
    expect(clean.y).toBe(20.5);
    expect(swipeSvg(clean)).toContain('x1="10" y1="20.5" x2="30" y2="40"');
  });

  test('a coordinate that is not a finite number drops the mark', () => {
    for (const bad of ['1" onload="alert(1)', '', ' ', NaN, Infinity, null, undefined, true, {}]) {
      for (const key of ['x', 'y', 'x2', 'y2', 'dw', 'dh']) {
        expect(cleanMark(swipe({ [key]: bad }))).toBeNull();
      }
    }
  });

  test('a screen with no area drops the mark', () => {
    expect(cleanMark(swipe({ dw: 0 }))).toBeNull();
    expect(cleanMark(swipe({ dh: -5 }))).toBeNull();
  });

  test('a tap needs no end point', () => {
    const tap = { kind: 'tap', t: 0, x: 1, y: 2, dw: 3, dh: 4, ok: true } as TheaterMark;
    expect(cleanMark(tap)).toEqual(tap);
  });

  test('a check keeps the element it found when the box is four numbers with area, else just its point', () => {
    const check = (box: unknown) => ({ kind: 'check', t: 0, x: 50, y: 60, dw: 100, dh: 200, ok: true, box }) as unknown as TheaterMark;
    expect(cleanMark(check({ left: '40', top: 50, right: 60, bottom: 70 }))!.box).toEqual({ left: 40, top: 50, right: 60, bottom: 70 });
    for (const bad of [{ left: 40, top: 50, right: 40, bottom: 70 }, { left: '1" onload="x', top: 50, right: 60, bottom: 70 }, { left: 40, top: 50, right: 60 }]) {
      const clean = cleanMark(check(bad))!;
      expect(clean.box).toBeUndefined();
      expect([clean.x, clean.y]).toEqual([50, 60]);
    }
  });

  test('the markup can carry nothing but numbers', () => {
    const svg = swipeSvg(cleanMark(swipe({ x: '1e3' }))!);
    expect(svg).not.toMatch(/[<>"][^<>]*onload/i);
    expect(svg).toContain('x1="1000"');
  });
});

describe('marks on a screen of another shape', () => {
  test('a capture of the screen\'s own shape maps straight across', () => {
    const at = markPlacer({ dw: 1000, dh: 500 }, { w: 2000, h: 1000 });
    expect([at.left(250), at.top(250), at.width(500), at.height(100)]).toEqual(['25%', '50%', '50%', '20%']);
  });

  test('a narrower capture is centred with bars at its sides, and its marks with it', () => {
    // A 500x500 capture fitted into a 1000x500 screen fills the middle half.
    const at = markPlacer({ dw: 500, dh: 500 }, { w: 1000, h: 500 });
    expect([at.left(0), at.left(500), at.top(250), at.width(500)]).toEqual(['25%', '75%', '50%', '50%']);
  });

  test('a shorter capture is centred with bars above and below', () => {
    const at = markPlacer({ dw: 1000, dh: 250 }, { w: 1000, h: 500 });
    expect([at.top(0), at.top(250), at.height(250)]).toEqual(['25%', '75%', '50%']);
  });

  test('with no known screen shape the mark\'s own shape is used', () => {
    for (const screen of [undefined, null, { w: 0, h: 0 }]) {
      const at = markPlacer({ dw: 200, dh: 400 }, screen);
      expect([at.left(50), at.top(100)]).toEqual(['25%', '25%']);
    }
  });

  test('a swipe keeps its own shape inside the screen', () => {
    expect(swipeSvg(cleanMark(swipe())!)).toContain('preserveAspectRatio="xMidYMid meet"');
  });
});

describe('modal detection', () => {
  const original = (globalThis as { document?: unknown }).document;
  afterEach(() => { (globalThis as { document?: unknown }).document = original; });

  test('is open exactly while an aria-modal dialog is in the page', () => {
    let dialog: object | null = null;
    (globalThis as { document?: unknown }).document = { querySelector: (selector: string) => (selector.includes('aria-modal') ? dialog : null) };
    expect(modalOpen()).toBe(false);
    dialog = {};
    expect(modalOpen()).toBe(true);
  });

  test('is closed where there is no document', () => {
    (globalThis as { document?: unknown }).document = undefined;
    expect(modalOpen()).toBe(false);
  });
});
