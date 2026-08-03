// Behavior tests for the shared sprite-metadata contract (run-report-sprites.ts): raw
// video_sprites.txt content in, parsed geometry + acceptance verdict out.
//
// Cross-language behavioral contract: the same fixture drives the Kotlin side
// (SpriteMetadataParityFixturesTest, exercising SpriteSheetMetadata in :trailblaze-capture, used
// by the legacy WasmReport), so a semantic drift in either implementation fails that side's
// suite. To change a rule, update both implementations AND the fixture in the same change.
//
// Run: `bun test run-report-sprites.test.ts` from this directory.
import { describe, expect, test } from "bun:test";

const sprites = require("./run-report-sprites.ts") as {
  parseSpriteMetadata: (text: string) => SpriteMetaForTest | null;
  resolvedFrameMap: (meta: SpriteMetaForTest) => number[];
  spriteCell: (physical: number, columns: number, rows: number) => { sheet: number; column: number; row: number };
  spriteSheetRows: (meta: SpriteMetaForTest, sheetIndex: number) => number;
  spriteRejectionReason: (
    meta: SpriteMetaForTest,
    stepScreenshotCount: number,
    supportsMultiSheet?: boolean,
  ) => string | null;
  MIN_USEFUL_UNIQUE_FRAMES: number;
  MIN_ALIASING_TOTAL_FRAMES: number;
  RESTAMPED_SINGLE_FRAME_DOMINANCE_FRAC: number;
};

interface SpriteMetaForTest {
  fps: number;
  frames: number;
  height: number;
  columns: number;
  rows: number;
  uniqueFrames: number | null;
  sheets: number;
  frameMap: number[] | null;
  restamped: boolean;
  frameWidth: number | null;
}

interface FixtureCase {
  name: string;
  txt: string;
  stepScreenshotCount: number;
  parsed: {
    fps: number;
    frames: number;
    height: number;
    columns: number;
    rows: number;
    uniqueFrames: number | null;
    sheets: number;
    restamped: boolean;
    frameWidth: number | null;
    frameMapExplicit: boolean;
    frameMapLength: number;
    frameMapSamples: Array<{ logical: number; physical: number }>;
    sheetRowsSamples?: Array<{ sheet: number; rows: number }>;
  } | null;
  rejection: string | null;
  /** Absent → same as `rejection`; explicit null → accepted when multi-sheet capable. */
  rejectionMultiSheetCapable?: string | null;
}

describe("cross-language sprite-metadata parity fixtures", () => {
  const fixtures = require("./sprite-metadata-parity-fixtures.json") as {
    constants: {
      MIN_USEFUL_UNIQUE_FRAMES: number;
      MIN_ALIASING_TOTAL_FRAMES: number;
      RESTAMPED_SINGLE_FRAME_DOMINANCE_FRAC: number;
    };
    cases: FixtureCase[];
  };

  test("fixture file has cases", () => {
    expect(fixtures.cases.length).toBeGreaterThan(0);
  });

  test("acceptance constants match the fixture", () => {
    expect(sprites.MIN_USEFUL_UNIQUE_FRAMES).toBe(fixtures.constants.MIN_USEFUL_UNIQUE_FRAMES);
    expect(sprites.MIN_ALIASING_TOTAL_FRAMES).toBe(fixtures.constants.MIN_ALIASING_TOTAL_FRAMES);
    expect(sprites.RESTAMPED_SINGLE_FRAME_DOMINANCE_FRAC).toBe(
      fixtures.constants.RESTAMPED_SINGLE_FRAME_DOMINANCE_FRAC,
    );
  });

  for (const c of fixtures.cases) {
    test(c.name, () => {
      const meta = sprites.parseSpriteMetadata(c.txt);
      if (c.parsed === null) {
        expect(meta).toBeNull();
        return;
      }
      expect(meta).not.toBeNull();
      const m = meta!;
      expect(m.fps).toBe(c.parsed.fps);
      expect(m.frames).toBe(c.parsed.frames);
      expect(m.height).toBe(c.parsed.height);
      expect(m.columns).toBe(c.parsed.columns);
      expect(m.rows).toBe(c.parsed.rows);
      expect(m.uniqueFrames).toBe(c.parsed.uniqueFrames);
      expect(m.sheets).toBe(c.parsed.sheets);
      expect(m.restamped).toBe(c.parsed.restamped);
      expect(m.frameWidth).toBe(c.parsed.frameWidth);
      expect(m.frameMap !== null).toBe(c.parsed.frameMapExplicit);

      const resolved = sprites.resolvedFrameMap(m);
      expect(resolved.length).toBe(c.parsed.frameMapLength);
      for (const s of c.parsed.frameMapSamples) {
        expect(resolved[s.logical]).toBe(s.physical);
      }
      for (const s of c.parsed.sheetRowsSamples ?? []) {
        expect(sprites.spriteSheetRows(m, s.sheet)).toBe(s.rows);
      }

      expect(sprites.spriteRejectionReason(m, c.stepScreenshotCount)).toBe(c.rejection);
      const capableVerdict = "rejectionMultiSheetCapable" in c ? (c.rejectionMultiSheetCapable ?? null) : c.rejection;
      expect(sprites.spriteRejectionReason(m, c.stepScreenshotCount, true)).toBe(capableVerdict);
    });
  }
});

describe("spriteCell", () => {
  test("fills row-major within a sheet and splits sheets every columns*rows frames", () => {
    // 2×3 grid, 6 frames per sheet: frame 1 is the second cell of the TOP row (a transposed read
    // puts it a row down), and frame 6 opens sheet 1 at the top-left again.
    expect(sprites.spriteCell(0, 2, 3)).toEqual({ sheet: 0, column: 0, row: 0 });
    expect(sprites.spriteCell(1, 2, 3)).toEqual({ sheet: 0, column: 1, row: 0 });
    expect(sprites.spriteCell(5, 2, 3)).toEqual({ sheet: 0, column: 1, row: 2 });
    expect(sprites.spriteCell(6, 2, 3)).toEqual({ sheet: 1, column: 0, row: 0 });
    expect(sprites.spriteCell(7, 2, 3)).toEqual({ sheet: 1, column: 1, row: 0 });
    // Legacy single-column sheet: every frame in column 0, row = index.
    expect(sprites.spriteCell(4, 1, 9)).toEqual({ sheet: 0, column: 0, row: 4 });
  });
});
