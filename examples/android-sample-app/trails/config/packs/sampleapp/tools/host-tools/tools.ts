// Host-only TS tool — same author surface as the on-device-compatible flavor in
// `../quickjs-tools/typed.ts`. Identical import, identical `trailblaze.tool(...)` shape, same
// registration semantics. The ONLY thing that makes this host-only is what's inside the
// handler bodies — `node:fs` and `node:os` aren't available in QuickJS, so this file can't
// run on-device.
//
// Authors don't pick "the host SDK" vs "the on-device SDK" — there's only one SDK
// (`@trailblaze/tools`). They pick which target they're capable of running on by what they
// reach for inside their handlers. Pure JS + SDK calls → on-device-compatible. Reach for
// `node:*` modules → host-only.

import { trailblaze } from "@trailblaze/tools";
import * as fs from "node:fs/promises";
import * as path from "node:path";
import * as os from "node:os";

trailblaze.tool(
  "sampleApp_writeArtifact",
  {
    description:
      "Writes a string to a file under the OS temp directory and returns the absolute path. " +
      "Host-only — uses node:fs/promises and node:os. Use this pattern for trail-artifact tools " +
      "(screenshots, logs, generated test data) where the daemon's working directory is the " +
      "right place to land output.",
    inputSchema: {
      relativePath: {
        type: "string",
        description:
          "Relative path under the OS temp directory (e.g. 'trailblaze-demo/run-1.txt'). " +
          "Parent directories are created on demand.",
      },
      contents: { type: "string", description: "UTF-8 contents to write." },
    },
  },
  async (args) => {
    const relativePath = String((args as { relativePath?: unknown }).relativePath ?? "");
    const contents = String((args as { contents?: unknown }).contents ?? "");
    if (!relativePath) {
      return {
        content: [{ type: "text", text: "Error: relativePath is required" }],
        isError: true,
      };
    }
    // Containment check via `path.relative` rather than `startsWith(tmpRoot + path.sep)` —
    // the latter is fragile across platforms (Windows separators, case-insensitive filesystems,
    // unnormalized symlink targets). The relative-path approach normalizes both sides through
    // `path.resolve` and treats anything that climbs out (`..`) or names an absolute path as
    // an escape attempt.
    const tmpRoot = path.resolve(os.tmpdir());
    const resolved = path.resolve(tmpRoot, relativePath);
    const relativeToTmp = path.relative(tmpRoot, resolved);
    if (relativeToTmp.startsWith("..") || path.isAbsolute(relativeToTmp)) {
      return {
        content: [{ type: "text", text: `Error: refusing to write outside tmpdir: ${resolved}` }],
        isError: true,
      };
    }
    await fs.mkdir(path.dirname(resolved), { recursive: true });
    await fs.writeFile(resolved, contents, "utf8");
    return {
      content: [{ type: "text", text: resolved }],
      isError: false,
    };
  },
);

trailblaze.tool(
  "sampleApp_readArtifact",
  {
    description:
      "Reads a UTF-8 file. Companion to sampleApp_writeArtifact for round-trip testing of " +
      "the host-only file-I/O surface.",
    inputSchema: {
      absolutePath: { type: "string", description: "Absolute path to read." },
    },
  },
  async (args) => {
    const absolutePath = String((args as { absolutePath?: unknown }).absolutePath ?? "");
    if (!absolutePath) {
      return {
        content: [{ type: "text", text: "Error: absolutePath is required" }],
        isError: true,
      };
    }
    try {
      const contents = await fs.readFile(absolutePath, "utf8");
      return {
        content: [{ type: "text", text: contents }],
        isError: false,
      };
    } catch (e) {
      const message = e instanceof Error ? e.message : String(e);
      return {
        content: [{ type: "text", text: `Error: ${message}` }],
        isError: true,
      };
    }
  },
);
