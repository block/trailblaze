import { mkdir, writeFile } from "node:fs/promises";
import { dirname, isAbsolute, resolve } from "node:path";
import { tmpdir } from "node:os";

// Demonstrates a host-only inline tool — uses `node:fs/promises`, which only exists in
// the host subprocess runtime (Node/Bun). On-device QuickJS doesn't ship `node:fs`, so the
// tool is gated with `requiresHost: true` in the pack scripted-tool YAML.
//
// Registered as `host_writeArtifact` by `../packs/sampleapp/tools/host_writeArtifact.yaml`.
export async function host_writeArtifact(args, ctx) {
  const relativePath =
    typeof args.relativePath === "string" && args.relativePath.length > 0
      ? args.relativePath
      : "trailblaze-artifact.txt";

  if (isAbsolute(relativePath) || relativePath.includes("..")) {
    throw new Error(
      `host_writeArtifact relativePath must be a relative path without "..": ${relativePath}`,
    );
  }

  const contents = typeof args.contents === "string" ? args.contents : "";
  const sessionId = sanitizeSessionId(ctx?.sessionId);
  const baseDir = resolve(tmpdir(), "trailblaze-artifacts", sessionId);
  const target = resolve(baseDir, relativePath);

  await mkdir(dirname(target), { recursive: true });
  await writeFile(target, contents, "utf8");

  const byteLength = Buffer.byteLength(contents, "utf8");
  return `Wrote ${byteLength} bytes to ${target}`;
}

// Strip anything that isn't a safe path-segment char so a hostile or weird sessionId
// can't escape the per-session subtree (e.g. via `..`, `/`, or NULs).
function sanitizeSessionId(raw) {
  const fallback = "no-session";
  if (typeof raw !== "string" || raw.length === 0) return fallback;
  const cleaned = raw.replace(/[^A-Za-z0-9_-]/g, "_");
  return cleaned.length > 0 ? cleaned : fallback;
}
