// Which files under a session's `events/` were derived by an event stream (`stream.ts`), not captured.
// Read by the session loader and the stored-results hash, so neither ever treats a derived stream as
// part of the session as it was captured; written by `stream-write.ts`.

import { lstatSync, readFileSync } from "node:fs";
import { join } from "node:path";

import { STREAM_NAME } from "./stream.js";

/** Lists the streams written into `events/`. Not `.ndjson`, so no stream reader takes it for one. */
export const DERIVED_MANIFEST = "events/derived.json";
export const DERIVED_MANIFEST_FORMAT = 1;
/** Far past any real manifest (a few hundred bytes a stream); a bigger one is not read into memory. */
export const DERIVED_MANIFEST_MAX_BYTES = 1024 * 1024;

export interface DerivedManifest {
  format: number;
  /**
   * Stream name → the code that wrote it, the session files it was derived from, how many rows it
   * has, a hash of its file, its lane's title, the streams and logs its rows came from, and how
   * long deriving it took (`ms`); or, for a stream that failed when last written, why (`error`), with no rows.
   */
  streams: Record<string, { version?: string; inputs?: string; rows: number; sha?: string; title?: string; lane?: { fold?: string }; derivedFrom?: string[]; ms?: number; error?: string }>;
  /** The file is there but could not be read as a manifest, so which files it owns is unknown. Never saved. */
  unreadable?: true;
}

export function readDerivedManifest(dir: string): DerivedManifest {
  const file = join(dir, DERIVED_MANIFEST);
  let present = false;
  try {
    const stat = lstatSync(file);
    present = true;
    if (stat.isFile() && stat.size <= DERIVED_MANIFEST_MAX_BYTES) {
      const parsed = JSON.parse(readFileSync(file, "utf8")) as DerivedManifest;
      if (parsed?.format === DERIVED_MANIFEST_FORMAT && parsed.streams && typeof parsed.streams === "object" && !Array.isArray(parsed.streams)) {
        // Only names a stream could have: any other key would point a path outside `events/`, so it is dropped, never deleted or written.
        const streams = Object.fromEntries(Object.entries(parsed.streams).filter(([name]) => STREAM_NAME.test(name)));
        return { format: DERIVED_MANIFEST_FORMAT, streams };
      }
    }
  } catch {
    // Absent, or unreadable: below.
  }
  // Read as nothing derived; `unreadable` keeps a writer from saving over what it might list.
  return { format: DERIVED_MANIFEST_FORMAT, streams: {}, ...(present ? { unreadable: true as const } : {}) };
}

/** Why a session whose manifest cannot be read is refused: which of its streams were captured is unknown. */
export function unreadableManifestMessage(dir: string): string {
  return `${join(dir, DERIVED_MANIFEST)} cannot be read, so which event streams were derived is unknown; fix or delete it`;
}

/** The streams under `<dir>/events/` that were derived, not captured. Throws when the manifest is there but cannot be read. */
export function derivedStreamNames(dir: string): Set<string> {
  const manifest = readDerivedManifest(dir);
  if (manifest.unreadable) throw new Error(unreadableManifestMessage(dir));
  return new Set(Object.keys(manifest.streams));
}

/** Held while a run writes derived streams into a session (`stream-write.ts`), so two runs take turns. */
export const DERIVED_LOCK = `${DERIVED_MANIFEST}.lock`;

/** The files under `events/` that writing derived streams holds, besides the streams: the lock, its takers' tokens, and the temporary copies of the manifest and of each stream. */
export const DERIVED_OWN_FILE = /^(?:derived\.json(?:\.lock(?:\.[\w-]+(?:\.stale)?)?|\.[\w-]+\.tmp)|[^/]+\.ndjson\.[0-9a-f-]{36}\.tmp)$/;

/** Session-relative paths, `/`-separated, a derivation wrote: the manifest and every stream it lists. Undefined when the manifest cannot be read. */
export function derivedFiles(dir: string): Set<string> | undefined {
  const manifest = readDerivedManifest(dir);
  if (manifest.unreadable) return undefined;
  return new Set([DERIVED_MANIFEST, ...Object.keys(manifest.streams).map((n) => `events/${n}.ndjson`)]);
}
