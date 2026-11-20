import { expect, test } from "bun:test";

import { memoryKeys } from "./memory-keys.js";

test("memoryKeys.<trailmap>.<key> is the flat key name", () => {
  // Typed by the generated surface; the SDK alone has no trailmaps, so index it loosely here.
  const byTrailmap = memoryKeys as unknown as Record<string, Record<string, string>>;
  expect(byTrailmap.myapp.appearance).toBe("appearance");
  expect(byTrailmap.accounts.account_email).toBe("account_email");
});
