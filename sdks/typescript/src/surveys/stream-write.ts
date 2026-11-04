// Writes derived streams into session directories, as `events/<name>.ndjson`, so the report's
// timeline shows them like any captured stream. `stream.ts` says what a derived stream is.
//
// `events/derived.json` lists every stream written here, with the version of the code that wrote it,
// what the session's files were, and a hash of what it wrote. A listed stream is rewritten when any
// of them no longer matches (so a file another run replaced, or a write cut short, is redone), and is never
// read back as captured data: a session's surveys and derivations always see the session as it was
// captured (see `derivedStreamNames`), and writing one changes nothing a stored survey result
// depends on. A stream file the manifest does not list was captured, and is never overwritten.
//
// The streams passed in are the whole set: a listed stream that is not among them is removed, so the
// report never shows rows from a stream nobody declares any more. Every derived file can be
// rebuilt, so removing one costs only the next write.

import { createHash, randomUUID } from "node:crypto";
import { closeSync, constants, existsSync, fstatSync, lstatSync, mkdirSync, openSync, readSync, renameSync, rmdirSync, rmSync, writeFileSync } from "node:fs";
import { join } from "node:path";

import { DERIVED_LOCK, DERIVED_MANIFEST, readDerivedManifest, type DerivedManifest } from "./derived-files.js";
import { SurveySession } from "./session.js";
import { sessionInputs, withLock } from "./stored.js";
import { envelopeOf, sourcesOf, type EventStreamDefinition } from "./stream.js";
import type { SessionSummary } from "./types.js";

export interface StreamWriteOptions {
  /** Only write into sessions this accepts; the rest are left as they are. */
  where?: (session: SessionSummary) => boolean;
}

export interface StreamWriteResult {
  /** The session as loaded, when it was. */
  session?: SurveySession;
  /** The session was not one `where` accepts, so nothing was written. */
  excluded: boolean;
  /**
   * The session has not ended, so nothing was written: its capture may still be running, and a
   * producer that has not written its first line yet would append to a derived file of its name.
   */
  unfinished: boolean;
  /** Streams written or rewritten. */
  written: string[];
  /** Streams already current: same code, same session files. */
  current: string[];
  /** Streams that derived no rows here, so the session has no file for them. */
  empty: string[];
  /** Streams written here before that are no longer declared, now deleted. */
  removed: string[];
  errors: Array<{ stream: string; message: string }>;
}

/**
 * Replaces [path] with [text] through a temporary file, so a reader never sees half of it. A fresh
 * name created exclusively, so nothing already in the session (a symlink unpacked from a zip) is
 * written through; removed when the write fails, so no partial file is left behind.
 */
function writeAtomic(path: string, text: string, guard?: () => void): void {
  const tmp = `${path}.${randomUUID()}.tmp`;
  try {
    writeFileSync(tmp, text, { flag: "wx" });
    guard?.();
    renameSync(tmp, path);
  } finally {
    rmSync(tmp, { force: true });
  }
}

const digest = (text: string) => createHash("sha256").update(text).digest("hex");

/** The hash of a regular file's bytes, read in chunks; undefined for a link, a pipe or a device, which never match. */
function digestOf(file: string): string | undefined {
  let fd: number | undefined;
  try {
    if (!lstatSync(file).isFile()) return undefined;
    fd = openSync(file, constants.O_RDONLY | constants.O_NOFOLLOW | constants.O_NONBLOCK);
    if (!fstatSync(fd).isFile()) return undefined;
    const hash = createHash("sha256");
    const chunk = Buffer.alloc(1 << 20);
    for (let n; (n = readSync(fd, chunk, 0, chunk.length, null)) > 0; ) hash.update(chunk.subarray(0, n));
    return hash.digest("hex");
  } catch {
    return undefined;
  } finally {
    if (fd !== undefined) closeSync(fd);
  }
}

/** This run's lock was taken over: whatever it changes now may undo what the run holding it has done since. */
class LostLock extends Error {}

const message = (e: unknown) => (e instanceof Error ? e.message : String(e));

/** The longest failure a session's manifest records for one stream, so a huge message never bloats it. */
const ERROR_MAX = 500;
const clampError = (text: string) => (text.length > ERROR_MAX ? `${text.slice(0, ERROR_MAX - 1)}…` : text);

function isLink(path: string): boolean {
  try {
    return lstatSync(path).isSymbolicLink();
  } catch {
    return false;
  }
}

/**
 * Writes every stream of [streams] into the session at [dir], skipping the ones already current. A
 * stream that derives no rows leaves no file, so the report adds no empty lane. Throws when the
 * session cannot be loaded.
 */
/** Saving over a manifest that cannot be read would drop the files it owns, which would then read as captured for good. */
function unreadable(result: StreamWriteResult): StreamWriteResult {
  result.errors.push({ stream: DERIVED_MANIFEST, message: `${DERIVED_MANIFEST} cannot be read, so no derived stream is written; fix or delete it` });
  return result;
}

export function writeDerivedStreams(dir: string, streams: ReadonlyArray<EventStreamDefinition>, options: StreamWriteOptions = {}): StreamWriteResult {
  const result: StreamWriteResult = { excluded: false, unfinished: false, written: [], current: [], empty: [], removed: [], errors: [] };
  const manifestFile = join(dir, DERIVED_MANIFEST);
  if (streams.length === 0 && !existsSync(manifestFile)) return result;
  // Hashed before loading, as `runSurveys` does: an event that lands after this is in no row, and
  // changes the next write's hash, so that write does not take the rows as current.
  const before = sessionInputs(dir);
  if (readDerivedManifest(dir).unreadable) return unreadable(result);
  const session = SurveySession.load(dir);
  result.session = session;
  if (options.where && !options.where(session.summary)) {
    result.excluded = true;
    return result;
  }
  if (session.summary.outcome === "UNKNOWN") {
    result.unfinished = true;
    return result;
  }
  const events = join(dir, "events");
  if (isLink(events)) {
    // Writing through it would change, or delete, files outside this session.
    result.errors.push({ stream: "events", message: `${events} is a link; derived streams are written only into the session's own directory` });
    return result;
  }
  // One run at a time: two that read the same manifest would each save over the other's entries,
  // leaving a stream file no manifest lists, which then reads as captured for good.
  const made = !existsSync(events);
  try {
    mkdirSync(events, { recursive: true });
    return withLock(join(dir, DERIVED_LOCK), (held) => {
      const manifest = readDerivedManifest(dir);
      if (manifest.unreadable) return unreadable(result);
      const fileOf = (name: string) => join(dir, "events", `${name}.ndjson`);
      const entryOf = (name: string) => (Object.prototype.hasOwnProperty.call(manifest.streams, name) ? manifest.streams[name] : undefined);
      // Before every change to a file here: a run whose lock was taken over (it ran past the lease,
      // or was suspended) stops, since the run now holding it may have pruned or rewritten that file.
      const own = () => {
        if (!held()) throw new LostLock(`lost ${DERIVED_LOCK} to another run; nothing more is written, run it again`);
      };
      const remove = (file: string) => {
        own();
        rmSync(file, { force: true });
      };
      const save = (m: DerivedManifest) => {
        if (Object.keys(m.streams).length === 0) return remove(manifestFile);
        writeAtomic(manifestFile, JSON.stringify(m, null, 2) + "\n", own);
      };
      let changed = false;

      const declared = new Set(streams.map((d) => d.name));
      for (const name of Object.keys(manifest.streams)) {
        if (declared.has(name)) continue;
        try {
          remove(fileOf(name));
          delete manifest.streams[name];
          result.removed.push(name);
          changed = true;
        } catch (e) {
          if (e instanceof LostLock) throw e;
          result.errors.push({ stream: name, message: `could not remove events/${name}.ndjson, which no stream declares: ${message(e)}` });
        }
      }

      for (const def of streams) {
        const name = def.name;
        const file = fileOf(name);
        const entry = entryOf(name);
        // Also the legacy `<name>.json.ndjson`, which the report reads as the same stream. The root
        // `network[.<device>].ndjson` needs no check: `eventStream()` refuses every name Trailblaze captures.
        const captured = entry ? undefined : [`events/${name}.ndjson`, `events/${name}.json.ndjson`].find((f) => existsSync(join(dir, f)));
        if (captured) {
          result.errors.push({ stream: name, message: `${captured} was captured, not derived; rename the stream` });
          continue;
        }
        // No version (code changed under this process) or no inputs (the session holds a link): nothing vouches for the old file.
        // A file written before the manifest recorded its sources is redone, so the report can name them.
        const current = entry && def.version !== undefined && before !== undefined && entry.version === def.version && entry.inputs === before
          && (entry.rows === 0 || Array.isArray(entry.derivedFrom));
        if (current && (entry.rows === 0 ? !existsSync(file) : entry.sha !== undefined && digestOf(file) === entry.sha)) {
          (entry.rows === 0 ? result.empty : result.current).push(name);
          continue;
        }
        try {
          const rows = def.of(session);
          let sha: string | undefined;
          if (rows.length === 0) {
            remove(file);
            result.empty.push(name);
          } else {
            // Claimed before the file appears, so an interrupted write leaves a derived stream to redo, not one that looks captured.
            if (!entry) {
              manifest.streams[name] = { rows: 0 };
              save(manifest);
            }
            const view = def.spec.row?.bind(def.spec);
            const text = rows.map((r) => JSON.stringify(envelopeOf(r, view))).join("\n") + "\n";
            writeAtomic(file, text, own);
            sha = digest(text);
            result.written.push(name);
          }
          manifest.streams[name] = {
            version: def.version,
            inputs: before,
            rows: rows.length,
            ...(sha ? { sha } : {}),
            ...(def.spec.title ? { title: def.spec.title } : {}),
            ...(def.spec.lane?.fold ? { lane: { fold: def.spec.lane.fold } } : {}),
            ...(rows.length ? { derivedFrom: sourcesOf(def, rows) } : {}),
          };
          changed = true;
        } catch (e) {
          if (e instanceof LostLock) throw e;
          result.errors.push({ stream: name, message: message(e) });
          // Recorded on the session, so whoever opens it later can tell a stream that failed from one with no rows. Kept listed, so a retry rewrites it.
          manifest.streams[name] = { rows: 0, error: clampError(message(e)) };
          changed = true;
          if (entry) {
            // The old rows would still show in the report as if they were current.
            try {
              remove(file);
            } catch (c) {
              if (c instanceof LostLock) throw c;
              result.errors.push({ stream: name, message: `could not remove the out-of-date events/${name}.ndjson: ${message(c)}` });
            }
          }
        }
      }

      if (changed) {
        try {
          save(manifest);
        } catch (e) {
          if (e instanceof LostLock) throw e;
          result.errors.push({ stream: DERIVED_MANIFEST, message: message(e) });
        }
      }
      return result;
    });
  } catch (e) {
    result.errors.push({ stream: DERIVED_LOCK, message: message(e) });
    return result;
  } finally {
    // Made only to hold the lock: a session that had no `events/` gets none from deriving nothing.
    if (made) {
      try {
        rmdirSync(events);
      } catch {
        // Not empty: a stream was written.
      }
    }
  }
}
