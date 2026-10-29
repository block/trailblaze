import { describe, expect, test } from "bun:test";

import * as full from "./index.js";
import * as inProcess from "./in-process.js";

// The daemon bundles every tool without `runtime: subprocess` against `in-process.ts` in place of
// `@trailblaze/scripting`, so a value only `index.ts` exports fails that tool's bundle.
describe("in-process entry", () => {
  test("carries the host OS and architecture lists a tool can import", () => {
    expect(inProcess.TRAILBLAZE_HOST_OS).toEqual(full.TRAILBLAZE_HOST_OS);
    expect(inProcess.TRAILBLAZE_HOST_ARCH).toEqual(full.TRAILBLAZE_HOST_ARCH);
  });
});
