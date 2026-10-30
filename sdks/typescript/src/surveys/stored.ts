// What surveys found in a session, stored beside the session's own records, so a later run reuses
// it instead of surveying the session again.
//
// One file per session, `surveys.json`, holding one entry per survey: the survey's version, its
// findings and errors there, and the labels it gave the run. An entry is current while the survey's
// version and the session's files are unchanged, it applied to the session, and it recorded no
// error; a run that needs a survey it was not asked for reuses a current entry rather than
// surveying the session again. Each entry is sealed with a key only this machine holds, so results
// that arrive inside a session from elsewhere (a downloaded zip) are never reused: findings a
// renderer acts on, such as a screenshot URL it fetches, come only from surveys run here.

import { createHash, createHmac, randomBytes, randomUUID, timingSafeEqual } from "node:crypto";
import { closeSync, constants, fstatSync, linkSync, lstatSync, mkdirSync, openSync, readdirSync, readFileSync, readSync, renameSync, rmSync, utimesSync, writeFileSync } from "node:fs";
import { homedir } from "node:os";
import { dirname, join, relative } from "node:path";

import type { SessionResult, SurveyReport } from "./runner.js";
import type { Finding, LabelRecord, SessionRecord } from "./types.js";

/** The file inside a session directory. Its name does not start with a hex digit, so no log reader takes it for a log record. */
export const STORED_SURVEYS_FILE = "surveys.json";
export const STORED_SURVEYS_FORMAT = 1;

export interface StoredSurveys {
  format: number;
  /** Survey id → what it found in this session. */
  surveys: Record<string, StoredSurvey>;
}

export interface StoredSurvey {
  /** Absent for a survey built in code, whose entry is then never current. */
  version?: string;
  generatedAt: string;
  /** What the session's files were when it was surveyed: see [sessionInputs]. */
  inputs?: string;
  findings: Finding[];
  /** Run-level labels: this survey's labels that this session's findings back. */
  labels: LabelRecord[];
  /** The survey did not apply to this session (its platform, app, or target). */
  skipped?: boolean;
  /** What the find phase reported failing here. Label phases rerun every time, so their failures are the report's alone. */
  errors?: string[];
  /** Proves this machine wrote the entry, for this session and survey: see [sealOf]. */
  seal?: string;
}

/**
 * The session's stored survey results, or undefined when it has none or the file is unreadable. A
 * `surveys.json` that is not a plain file (a symlink unpacked from a zip, pointing at another
 * session's) is not this session's, and counts as none.
 */
export function readStoredSurveys(sessionDir: string): StoredSurveys | undefined {
  const file = join(sessionDir, STORED_SURVEYS_FILE);
  try {
    if (!lstatSync(file).isFile()) return undefined;
    const parsed = JSON.parse(readFileSync(file, "utf8")) as StoredSurveys;
    const surveys = parsed?.surveys as unknown;
    const isMap = typeof surveys === "object" && surveys !== null && !Array.isArray(surveys);
    return parsed?.format === STORED_SURVEYS_FORMAT && isMap ? parsed : undefined;
  } catch {
    return undefined;
  }
}

/**
 * The stored entry for `survey` in session `session` when it is current at `version` over files
 * that are `inputs`: sealed here, same version, same files, no errors, and the survey applied. A
 * file added since (screen text extracted afterwards) may change what the survey finds, so the
 * session is surveyed again. A skipped entry is not current, since the survey may apply once a run
 * loads the targets it is scoped to. A malformed entry is not current either.
 */
export function currentEntry(
  stored: StoredSurveys | undefined,
  session: string,
  survey: string,
  version: string | undefined,
  inputs: string | undefined,
): StoredSurvey | undefined {
  if (inputs === undefined) return undefined;
  const entry = stored?.surveys[survey] as StoredSurvey | undefined;
  if (typeof entry !== "object" || entry === null || !Array.isArray(entry.findings) || !entry.findings.every(isFinding)) return undefined;
  if (!sealHolds(entry, session, survey)) return undefined;
  if (version === undefined || entry.version !== version || entry.inputs !== inputs) return undefined;
  if (entry.skipped || (entry.errors?.length ?? 0) > 0) return undefined;
  return entry;
}

/** Whether a stored finding has what a finding must: a summary and its evidence, each a record. */
function isFinding(f: unknown): boolean {
  const o = f as Partial<Finding> | null;
  return typeof o === "object" && o !== null && typeof o.summary === "string" && Array.isArray(o.evidence) && o.evidence.every(isRecord) &&
    (o.data === undefined || (typeof o.data === "object" && o.data !== null));
}

function isRecord(r: unknown): boolean {
  const o = r as Partial<SessionRecord> | null;
  return typeof o === "object" && o !== null && typeof o.kind === "string" && typeof o.summary === "string" && typeof o.file === "string";
}

const SEAL_KEY_BYTES = 32;

/**
 * The key entries are sealed with, in the Trailblaze state directory; made on first use when
 * [create]. A key of any other length (a file left empty when its write failed) is no key: sealing
 * with it would let anyone forge a seal. It is written whole beside the key and moved into place,
 * so no run ever reads part of one.
 */
function sealKey(create: boolean): Buffer | undefined {
  const file = join(process.env.TRAILBLAZE_HOME || join(homedir(), ".trailblaze"), "survey-results.key");
  const read = () => {
    try {
      const key = readFileSync(file);
      return key.length === SEAL_KEY_BYTES ? key : undefined;
    } catch {
      return undefined;
    }
  };
  const existing = read();
  if (existing || !create) return existing;
  mkdirSync(dirname(file), { recursive: true });
  const tmp = `${file}.${randomUUID()}.tmp`;
  try {
    writeFileSync(tmp, randomBytes(SEAL_KEY_BYTES), { flag: "wx", mode: 0o600 });
    try {
      linkSync(tmp, file); // Only where there is no key file at all, so a run that made one first keeps it.
    } catch (e) {
      if ((e as NodeJS.ErrnoException).code !== "EEXIST") throw e;
      if (!read()) renameSync(tmp, file); // A broken key is replaced.
    }
  } finally {
    rmSync(tmp, { force: true });
  }
  const key = read();
  if (!key) throw new Error(`Could not make the key survey results are sealed with: ${file}`);
  return key;
}

/** The seal over [entry] as stored for [session] and [survey], its own seal left out. */
function sealOf(key: Buffer, entry: StoredSurvey, session: string, survey: string): string {
  const { seal: _, ...sealed } = entry;
  return createHmac("sha256", key).update(`${session}\n${survey}\n${JSON.stringify(sealed)}`).digest("hex");
}

function sealHolds(entry: StoredSurvey, session: string, survey: string): boolean {
  const key = sealKey(false);
  if (key === undefined || typeof entry.seal !== "string") return false;
  const want = Buffer.from(sealOf(key, entry, session, survey));
  const got = Buffer.from(entry.seal);
  return want.length === got.length && timingSafeEqual(want, got);
}

/**
 * What a survey could read in the session directory, as a hash of every file's path, size,
 * modification time and change time. The change time is the operating system's own: setting a
 * file's modification time back after editing it moves it on. The stored results themselves are
 * left out, so storing them changes nothing. Undefined when the session holds a link: what it
 * points at can change without the session changing, so nothing vouches for it.
 */
/** What storing writes at a session's root: the results, and their temporary and lock files. */
const OWN_FILE = /^surveys\.json(?:\.lock(?:\.[\w-]+(?:\.stale)?)?|\.[\w-]+\.tmp)?$/;

export function sessionInputs(dir: string): string | undefined {
  let linked = false;
  const lines: string[] = [];
  const walk = (at: string) => {
    let names: string[];
    try {
      names = readdirSync(at);
    } catch {
      return;
    }
    for (const name of names) {
      const path = join(at, name);
      let stat;
      try {
        stat = lstatSync(path);
      } catch {
        continue;
      }
      if (at === dir && !stat.isDirectory() && OWN_FILE.test(name)) continue;
      if (stat.isSymbolicLink()) linked = true;
      else if (stat.isDirectory()) walk(path);
      else lines.push(`${relative(dir, path)}\t${stat.size}\t${stat.mtimeMs}\t${stat.ctimeMs}`);
    }
  };
  walk(dir);
  if (linked) return undefined;
  return createHash("sha256").update(lines.sort().join("\n")).digest("hex");
}

/**
 * Writes what `report` found into each of its finished sessions' `surveys.json`, one entry per
 * survey the run included. Entries for surveys the run did not include are kept, unless their labels
 * cite findings this run changed. A session still
 * running is not written: its findings are what it had so far, and a later run would reuse them as
 * current. Nor is one nothing vouches for: loaded before the run, or holding a link (see
 * `SessionResult.inputs`). Returns the files written.
 *
 * A session that cannot be written (its directory read-only, its lock held too long) does not stop
 * the others. With [failed], each such session is added to it; without, they are thrown together
 * once every other session is written.
 */
export function writeStoredSurveys(report: SurveyReport, failed?: Array<{ path: string; message: string }>): string[] {
  const written: string[] = [];
  const failures: Array<{ path: string; message: string }> = [];
  for (const s of report.sessions) {
    if (s.session.endedAtMs === undefined || s.inputs === null) continue;
    const file = join(s.session.path, STORED_SURVEYS_FILE);
    try {
      writeLocked(file, () => merged(report, s));
      written.push(file);
    } catch (e) {
      failures.push({ path: s.session.path, message: e instanceof Error ? e.message : String(e) });
    }
  }
  if (failed) failed.push(...failures);
  else if (failures.length > 0) throw new Error(`Could not store survey results: ${failures.map((f) => `${f.path}: ${f.message}`).join("; ")}`);
  return written;
}

/** The session's `surveys.json` with this run's entries merged in, as the text to write. */
function merged(report: SurveyReport, s: SessionResult): string {
  const stored = readStoredSurveys(s.session.path) ?? { format: STORED_SURVEYS_FORMAT, surveys: {} };
  const before = { ...stored.surveys };
  // What the session's files were when it loaded, so findings are never stored against files that
  // arrived while it was being surveyed: a later run sees the difference and surveys it again.
  const inputs = s.inputs ?? sessionInputs(s.session.path);
  if (inputs === undefined) throw new Error("it holds a link, so its stored results could not be kept current");
  const key = sealKey(true)!;
  for (const row of report.surveys) {
    const entry: StoredSurvey = {
      generatedAt: report.generatedAt,
      inputs,
      findings: s.findings.filter((f) => f.survey === row.id),
      labels: report.labels.filter((l) => l.survey === row.id && l.runs.includes(s.session.id)).map((l) => ({
        ...l,
        runs: [s.session.id],
        findings: l.findings.filter((f) => f.session === s.session.id),
      })),
    };
    if (row.version !== undefined) entry.version = row.version;
    if (s.skipped.includes(row.id)) entry.skipped = true;
    const errors = s.errors.filter((e) => e.survey === row.id).map((e) => e.message);
    if (errors.length > 0) entry.errors = errors;
    entry.seal = sealOf(key, entry, s.session.id, row.id);
    stored.surveys[row.id] = entry;
  }
  // A label kept from an earlier run cites findings by their position. Where this run changed a
  // survey's findings here, those positions no longer hold, so an entry citing them goes, and so
  // does any entry citing that one's findings; a later run that needs it surveys the session again.
  const rewritten = new Set(report.surveys.map((r) => r.id));
  const changed = new Set([...rewritten].filter((id) => JSON.stringify(before[id]?.findings) !== JSON.stringify(stored.surveys[id]!.findings)));
  for (let dropped = true; dropped; ) {
    dropped = false;
    for (const [id, entry] of Object.entries(stored.surveys)) {
      if (rewritten.has(id) || !citesAny(entry, changed)) continue;
      delete stored.surveys[id];
      changed.add(id);
      dropped = true;
    }
  }
  return JSON.stringify(stored, null, 2) + "\n";
}

function citesAny(entry: StoredSurvey | undefined, surveys: ReadonlySet<string>): boolean {
  const labels = Array.isArray(entry?.labels) ? entry.labels : [];
  return labels.some((l) => Array.isArray(l?.findings) && l.findings.some((f) => surveys.has(f?.survey)));
}

/** A lock older than this is from a run that died holding it. */
const STALE_LOCK_MS = 30_000;

/**
 * Replaces [file] with [content]'s text while holding `<file>.lock`, so two runs writing one session
 * keep both: each reads, merges and replaces in turn. Through a temporary file, so a reader never
 * sees half of one; a fresh name created exclusively, so nothing already in the session (a symlink
 * unpacked from a zip) is written through.
 */
function writeLocked(file: string, content: () => string): void {
  const lock = `${file}.lock`;
  const deadline = Date.now() + STALE_LOCK_MS * 2;
  for (;;) {
    const token = acquire(lock, deadline);
    const tmp = `${file}.${randomUUID()}.tmp`;
    try {
      writeFileSync(tmp, content(), { flag: "wx" });
      // Another run that judged the lock stale may have taken it meanwhile, and be merging too.
      // Replacing the file now could lose its entries, so start over instead.
      if (holderOf(lock) === token) {
        renameSync(tmp, file);
        return;
      }
    } finally {
      rmSync(tmp, { force: true });
      release(lock, token);
    }
    if (Date.now() > deadline) throw new Error(`Could not hold ${lock}: another survey run kept taking it over.`);
  }
}

/**
 * Takes [lock] and returns the token it holds, which only its holder knows. The lock is created
 * whole, with its token already in it, as a hard link to a file this run wrote, so no run ever sees
 * it empty. Waits while another run holds it, and takes over one left stale.
 */
function acquire(lock: string, deadline: number): string {
  const token = randomUUID();
  const mine = `${lock}.${token}`;
  writeFileSync(mine, token, { flag: "wx" });
  try {
    for (;;) {
      try {
        // The lock's age is its holder's lease, and a link keeps the file's time; so the lease
        // starts now, not when this run began waiting.
        const now = new Date();
        utimesSync(mine, now, now);
        linkSync(mine, lock);
        return token;
      } catch (e) {
        if ((e as NodeJS.ErrnoException).code !== "EEXIST") throw e;
      }
      if (Date.now() > deadline) {
        throw new Error(`Timed out waiting for ${lock}. Delete it if no survey run is writing this session.`);
      }
      takeOverIfStale(lock);
      Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 25);
    }
  } finally {
    rmSync(mine, { force: true });
  }
}

/**
 * Removes [lock] if it is stale. It is moved aside rather than deleted, and only removed if what
 * moved is the lock judged stale: another run may have replaced it in between, and then what moved
 * is that run's live lock, which goes back. Should another run have taken the lock in that moment
 * too, the run whose lock moved no longer holds it, sees so before it writes, and starts over.
 */
function takeOverIfStale(lock: string): void {
  let seen: string;
  try {
    // A lock's time is set when it is taken, so one from the future (unpacked from a zip made
    // elsewhere) was never taken here, and is as stale as one long past.
    if (Math.abs(Date.now() - lstatSync(lock).mtimeMs) <= STALE_LOCK_MS) return;
    // A lock that is not a plain file holds no token; it reads as empty, and goes like any stale lock.
    seen = tokenIn(lock) ?? "";
  } catch {
    return; // Released meanwhile.
  }
  const aside = `${lock}.${randomUUID()}.stale`;
  try {
    renameSync(lock, aside);
  } catch {
    return; // Released or taken over meanwhile.
  }
  try {
    if ((tokenIn(aside) ?? "") !== seen) linkSync(aside, lock);
  } catch {
    // Taken by another run meanwhile: the run whose lock moved finds it no longer holds one.
  } finally {
    rmSync(aside, { force: true });
  }
}

function holderOf(lock: string): string | undefined {
  return tokenIn(lock);
}

/** A token is a UUID; nothing a run wrote is longer. */
const TOKEN_BYTES = 64;

/**
 * The token in [file], or undefined when it is gone or is not a plain file. A lock unpacked from a
 * zip may be a symlink to a device or a pipe, so it is opened without following links, refused
 * unless it is a regular file, and read only as far as a token goes.
 */
function tokenIn(file: string): string | undefined {
  let fd: number;
  try {
    fd = openSync(file, constants.O_RDONLY | (constants.O_NOFOLLOW ?? 0) | (constants.O_NONBLOCK ?? 0));
  } catch {
    return undefined;
  }
  try {
    if (!fstatSync(fd).isFile()) return undefined;
    const buf = Buffer.alloc(TOKEN_BYTES);
    return buf.subarray(0, readSync(fd, buf, 0, TOKEN_BYTES, 0)).toString("utf8");
  } catch {
    return undefined;
  } finally {
    closeSync(fd);
  }
}

/** Removes [lock] if [token] still holds it, never a lock another run has since taken. */
function release(lock: string, token: string): void {
  if (holderOf(lock) === token) rmSync(lock, { force: true });
}
