// Host-only TS tools — same author surface as the on-device-compatible flavor in
// `../quickjs-tools/typed.ts`. Identical import (`@trailblaze/scripting`), identical
// `export const <toolName> = trailblaze.tool<Input>(handler)` shape, same registration
// semantics. The ONLY thing that makes these host-only is what's inside the handler
// bodies — `node:fs` and `node:os` aren't available in QuickJS, so this file can't run
// on-device.
//
// Authors don't pick "the host SDK" vs "the on-device SDK" — there's only one SDK
// (`@trailblaze/scripting`). They pick which target they're capable of running on by what
// they reach for inside their handlers. Pure JS + tool composition → on-device-compatible.
// Reach for `node:*` modules → host-only, which means declaring `runtime: subprocess` on the
// tool's descriptor so it dispatches to the host bun subprocess (the full SDK profile) where
// the Node APIs exist, rather than the in-process QuickJS path.
//
// SCOPE: this file is a documentation/reference example of the host-only author surface. Its
// `sampleApp_writeArtifact` / `sampleApp_readArtifact` exports are NOT wired into the sample-app
// trailmap (no YAML descriptor, not listed in `trailmap.yaml`'s `target.tools:`), so they aren't
// registered or dispatchable at runtime. The actually-wired host subprocess tool the sample app
// ships is the separate `../sampleapp_writeArtifact.js` (`runtime: subprocess`). Keep this file
// importing the one SDK and using the typed authoring shape so it stays an accurate example.

import { trailblaze } from "@trailblaze/scripting";
import * as fs from "node:fs/promises";
import * as path from "node:path";
import * as os from "node:os";

/**
 * Writes a string to a file under the OS temp directory and returns the absolute path.
 * Host-only — uses node:fs/promises and node:os. Use this pattern for trail-artifact tools
 * (screenshots, logs, generated test data) where the daemon's working directory is the
 * right place to land output.
 */
interface WriteArtifactInput {
  /**
   * Relative path under the OS temp directory (e.g. 'trailblaze-demo/run-1.txt').
   * Parent directories are created on demand.
   */
  relativePath: string;
  /** UTF-8 contents to write. */
  contents: string;
}

export const sampleApp_writeArtifact = trailblaze.tool<WriteArtifactInput>(
  async (input) => {
    const relativePath = String(input.relativePath ?? "");
    const contents = String(input.contents ?? "");
    if (!relativePath) {
      throw new Error("relativePath is required");
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
      throw new Error(`refusing to write outside tmpdir: ${resolved}`);
    }
    await fs.mkdir(path.dirname(resolved), { recursive: true });
    await fs.writeFile(resolved, contents, "utf8");
    return resolved;
  },
);

/**
 * Reads a UTF-8 file. Companion to sampleApp_writeArtifact for round-trip testing of
 * the host-only file-I/O surface.
 */
interface ReadArtifactInput {
  /** Absolute path to read. */
  absolutePath: string;
}

export const sampleApp_readArtifact = trailblaze.tool<ReadArtifactInput>(
  async (input) => {
    const absolutePath = String(input.absolutePath ?? "");
    if (!absolutePath) {
      throw new Error("absolutePath is required");
    }
    return await fs.readFile(absolutePath, "utf8");
  },
);
