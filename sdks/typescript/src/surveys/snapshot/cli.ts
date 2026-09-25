#!/usr/bin/env bun
// Writes a snapshot of every session under the given paths, for a page to run surveys over.
//
//   bun snapshot/cli.ts --out <dir> [--extract-dir <dir>] [--built-at <time>] <sessions-dir|zip>...
//
// What lands in `--out`: `index.json` (every session's summary, and which file holds it) and one
// gzipped `<n>.json.gz` per session. A page fetches the index, then one session at a time.

import { mkdirSync, rmSync, writeFileSync } from "node:fs";
import { isAbsolute, join, relative, resolve } from "node:path";
import { parseArgs } from "node:util";
import { gzipSync } from "node:zlib";

import { discoverSessions } from "../discover.js";
import { SurveySession } from "../session.js";
import { SNAPSHOT_FORMAT, type SnapshotIndex } from "./format.js";
import { snapshotOf } from "./write.js";

const HELP = `Usage: bun snapshot/cli.ts --out <dir> [options] <sessions-dir|zip>...

Options:
      --out <dir>          Where to write index.json and the snapshots (replaced)
      --extract-dir <dir>  Where session zips are unpacked (default: a temp dir)
      --built-at <time>    Record this as when the batch was built, in ms or ISO-8601. Without it
                           the index carries no time, so the same sessions index to the same bytes.
  -h, --help
`;

/** `--built-at` as ISO-8601, or why it is not a time. */
export function builtAtIso(stamp: string): string | Error {
  const trimmed = stamp.trim();
  const ms = /^\d+$/.test(trimmed) ? Number(trimmed) : Date.parse(trimmed);
  return Number.isNaN(ms) ? new Error(`--built-at ${stamp} is neither milliseconds nor a date`) : new Date(ms).toISOString();
}

/**
 * The first of `inputs` that replacing `out` would delete, if any. `--out` is removed before a
 * session is read, so an output at or above an input takes the sessions it was asked to convert.
 */
export function clobberedInput(out: string, inputs: string[]): string | undefined {
  return inputs.find((p) => {
    const rel = relative(resolve(out), resolve(p));
    return rel === "" || (!rel.startsWith("..") && !isAbsolute(rel));
  });
}

export async function main(argv: string[]): Promise<number> {
  const { values, positionals } = parseArgs({
    args: argv,
    allowPositionals: true,
    options: {
      out: { type: "string" },
      "extract-dir": { type: "string" },
      "built-at": { type: "string" },
      help: { type: "boolean", short: "h" },
    },
  });
  if (values.help || !values.out || positionals.length === 0) {
    process.stdout.write(HELP);
    return values.help ? 0 : 1;
  }
  const builtAt = values["built-at"] === undefined ? undefined : builtAtIso(values["built-at"]);
  if (builtAt instanceof Error) {
    process.stderr.write(`${builtAt.message}\n`);
    return 1;
  }
  const out = resolve(values.out);
  const extractDir = values["extract-dir"] === undefined ? undefined : resolve(values["extract-dir"]);
  const dirs = await discoverSessions(positionals.map((p) => resolve(p)), { extractDir });
  if (dirs.length === 0) {
    process.stderr.write(`No sessions under ${positionals.join(", ")}\n`);
    return 1;
  }
  const clobbered = clobberedInput(out, [...positionals, ...(extractDir ? [extractDir] : []), ...dirs]);
  if (clobbered !== undefined) {
    process.stderr.write(`--out ${out} would delete ${clobbered}, which it was asked to read. Write the snapshots somewhere else.\n`);
    return 1;
  }
  rmSync(out, { recursive: true, force: true });
  mkdirSync(out, { recursive: true });
  const index: SnapshotIndex = { format: SNAPSHOT_FORMAT, ...(builtAt ? { builtAt } : {}), sessions: [] };
  const started = Date.now();
  let total = 0;
  for (const [i, dir] of dirs.entries()) {
    const snapshot = snapshotOf(SurveySession.load(dir));
    // Numbered rather than named by session id: an id is a trail name and can be long, and the
    // index is what maps one to the other.
    const file = `${i}.json.gz`;
    const bytes = gzipSync(JSON.stringify(snapshot));
    writeFileSync(join(out, file), bytes);
    index.sessions.push({ file, bytes: bytes.length, summary: snapshot.summary });
    total += bytes.length;
    if ((i + 1) % 20 === 0) process.stderr.write(`  ${i + 1}/${dirs.length}\n`);
  }
  writeFileSync(join(out, "index.json"), JSON.stringify(index));
  process.stdout.write(`${out}\n  ${dirs.length} sessions, ${(total / 1e6).toFixed(1)} MB gzipped, in ${Math.round((Date.now() - started) / 1000)} s\n`);
  return 0;
}

if (import.meta.main) {
  main(process.argv.slice(2)).then((c) => process.exit(c)).catch((e) => {
    process.stderr.write(`${e instanceof Error ? e.stack ?? e.message : String(e)}\n`);
    process.exit(1);
  });
}
