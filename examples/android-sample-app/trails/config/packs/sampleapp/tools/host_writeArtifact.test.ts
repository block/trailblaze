// Sample test for a host-only scripted tool. `host_writeArtifact` reads and writes the
// real filesystem via `node:fs/promises`, so the test runs the body against a per-test
// `sessionId` (which the tool sanitizes into a path segment under the OS tmpdir) and
// verifies the resulting file on disk. Host-only tools have an `(args, ctx)` signature —
// they don't accept a `client`, since they don't dispatch `client.tools.X()`. For the
// `client.tools.X()` dispatch + stub patterns, see the playwright-native pack's sibling
// `.test.ts` files.
//
// Run via:
//
//   ./trailblaze test sampleapp

import { afterAll, describe, expect, test } from "bun:test";
import { createMockContext } from "@trailblaze/scripting/testing";
import { rmSync } from "node:fs";
import { readFile, stat } from "node:fs/promises";
import { tmpdir } from "node:os";
import { resolve } from "node:path";

import { host_writeArtifact } from "./host_writeArtifact";

// Track every sanitized sessionId this file touched so `afterAll` can rm the
// per-session subtrees the tool created under `tmpdir()/trailblaze-artifacts/`. The
// tool computes its target path from `tmpdir()` + the (sanitized) sessionId, so each
// test picks a unique sessionId and the array is the cleanup list — no separate
// scratch dir needed.
const usedSessionIds: string[] = [];

afterAll(() => {
  // Best-effort cleanup. `force: true` keeps the test green even if a prior failure
  // left only a partial tree.
  for (const sessionId of usedSessionIds) {
    rmSync(resolve(tmpdir(), "trailblaze-artifacts", sessionId), {
      force: true,
      recursive: true,
    });
  }
});

function uniqueSessionId(label: string): string {
  // Bun's `crypto.randomUUID()` would also work; the path-segment ASCII suffix here
  // keeps the test's sessionId readable in the resulting tmpdir path when a test fails.
  const id = `${label}-${Date.now()}-${Math.floor(Math.random() * 1e6)}`;
  usedSessionIds.push(id);
  return id;
}

describe("host_writeArtifact", () => {
  test("writes the contents to the per-session tmpdir and returns the byte count", async () => {
    const sessionId = uniqueSessionId("happy");
    const ctx = createMockContext({ platform: "android", sessionId });

    const result = await host_writeArtifact(
      { relativePath: "nested/output.txt", contents: "hello world" },
      ctx,
    );

    const expectedPath = resolve(
      tmpdir(),
      "trailblaze-artifacts",
      sessionId,
      "nested/output.txt",
    );
    expect(result).toBe(`Wrote 11 bytes to ${expectedPath}`);
    expect((await stat(expectedPath)).isFile()).toBe(true);
    expect(await readFile(expectedPath, "utf8")).toBe("hello world");
  });

  test("applies module defaults when relativePath and contents are omitted", async () => {
    const sessionId = uniqueSessionId("defaults");
    const ctx = createMockContext({ platform: "android", sessionId });

    // No `relativePath` → tool falls back to `trailblaze-artifact.txt`. No `contents`
    // → falls back to the empty string, so the byte count is 0.
    const result = await host_writeArtifact({}, ctx);

    const expectedPath = resolve(
      tmpdir(),
      "trailblaze-artifacts",
      sessionId,
      "trailblaze-artifact.txt",
    );
    expect(result).toBe(`Wrote 0 bytes to ${expectedPath}`);
    expect((await stat(expectedPath)).isFile()).toBe(true);
    expect(await readFile(expectedPath, "utf8")).toBe("");
  });

  test("falls back to the `no-session` segment when ctx has no sessionId", async () => {
    // The tool sanitizes a missing/empty sessionId into the literal `no-session` so
    // unauthenticated test runs still land somewhere predictable under the per-session
    // subtree.
    usedSessionIds.push("no-session");
    const ctx = createMockContext({ platform: "android", sessionId: "" });

    const result = await host_writeArtifact(
      { relativePath: "no-session-out.txt", contents: "x" },
      ctx,
    );

    expect(result).toContain(
      resolve(tmpdir(), "trailblaze-artifacts", "no-session", "no-session-out.txt"),
    );
  });

  test("rejects an absolute relativePath rather than escaping the per-session subtree", async () => {
    const ctx = createMockContext({ platform: "android", sessionId: uniqueSessionId("abs") });

    await expect(
      host_writeArtifact({ relativePath: "/etc/passwd", contents: "" }, ctx),
    ).rejects.toThrow(/relativePath must be a relative path/);
  });

  test("rejects `..` traversal even inside an otherwise relative path", async () => {
    const ctx = createMockContext({ platform: "android", sessionId: uniqueSessionId("dotdot") });

    await expect(
      host_writeArtifact(
        { relativePath: "nested/../../escape.txt", contents: "" },
        ctx,
      ),
    ).rejects.toThrow(/relativePath must be a relative path/);
  });

  test("sanitizes a sessionId with path-unsafe characters into a single segment", async () => {
    // A sessionId with `/` and `..` would otherwise escape the per-session subtree;
    // the tool's `sanitizeSessionId` collapses every non-safe char to `_`. The
    // ASCII-letter prefix on the input survives intact — that's the assertion handle.
    const ctx = createMockContext({
      platform: "android",
      sessionId: "weird/../session-id",
    });

    const result = await host_writeArtifact(
      { relativePath: "out.txt", contents: "x" },
      ctx,
    );

    // Parse the actual sanitized segment out of the result rather than hardcoding the
    // sanitizer's regex output — the assertion stays robust if `sanitizeSessionId`
    // ever changes the replacement char or blacklist.
    const match = result.match(/trailblaze-artifacts\/([^/]+)\//);
    expect(match).not.toBeNull();
    const sanitized = match![1];
    usedSessionIds.push(sanitized);
    // Two structural guarantees: every char in the result is path-safe, and the
    // ASCII prefix from the input survived untouched.
    expect(sanitized).toMatch(/^[A-Za-z0-9_-]+$/);
    expect(sanitized.startsWith("weird")).toBe(true);
    expect(sanitized.endsWith("session-id")).toBe(true);
  });
});
