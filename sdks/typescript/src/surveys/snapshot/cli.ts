#!/usr/bin/env bun
// Writes a snapshot of every session under the given paths, for a page to run surveys over.
//
//   bun snapshot/cli.ts --out <dir> [--extract-dir <dir>] [--built-at <time>] <sessions-dir|zip>...
//
// What lands in `--out`: `index.json` (every session's summary, and which file holds it) and one
// gzipped `<n>.json.gz` per session. A page fetches the index, then one session at a time.

import { mkdirSync, rmSync, writeFileSync } from "node:fs";
import { availableParallelism } from "node:os";
import { isAbsolute, join, relative, resolve } from "node:path";
import { parseArgs } from "node:util";

import { discoverSessions } from "../discover.js";
import { SNAPSHOT_FORMAT, type SnapshotIndex } from "./format.js";
import { type SnapshotResult, type SnapshotTask, writeSnapshot } from "./worker.js";

const HELP = `Usage: bun snapshot/cli.ts --out <dir> [options] <sessions-dir|zip>...

Options:
      --out <dir>          Where to write index.json and the snapshots (replaced)
      --extract-dir <dir>  Where session zips are unpacked (default: a temp dir)
      --built-at <time>    Record this as when the batch was built, in ms or ISO-8601. Without it
                           the index carries no time, so the same sessions index to the same bytes.
      --jobs <n>           Sessions written at once (default: the number of cores, at most 8).
                           The output is the same for any value.
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
      jobs: { type: "string" },
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
  const jobs = values.jobs === undefined ? Math.min(availableParallelism(), 8) : Number(values.jobs);
  if (!Number.isInteger(jobs) || jobs < 1) {
    process.stderr.write(`--jobs ${values.jobs} is not a positive whole number\n`);
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
  const tasks: SnapshotTask[] = dirs.map((dir, i) => ({ i, dir, out }));
  const results: SnapshotResult[] = new Array(tasks.length);
  let done = 0;
  const record = (result: SnapshotResult) => {
    results[result.i] = result;
    if (++done % 20 === 0) process.stderr.write(`  ${done}/${dirs.length}\n`);
  };
  if (jobs === 1) for (const task of tasks) record(writeSnapshot(task));
  else await inWorkers(tasks, Math.min(jobs, tasks.length), record);
  let total = 0;
  for (const result of results) {
    if ("error" in result) throw new Error(result.error);
    index.sessions.push({ file: result.file, bytes: result.bytes, summary: result.summary });
    total += result.bytes;
  }
  writeFileSync(join(out, "index.json"), JSON.stringify(index));
  process.stdout.write(`${out}\n  ${dirs.length} sessions, ${(total / 1e6).toFixed(1)} MB gzipped, in ${Math.round((Date.now() - started) / 1000)} s\n`);
  return 0;
}

// Bun's Web Worker, typed here because the surveys tsconfig carries neither the DOM nor the Bun
// types. Not `node:worker_threads`: under Bun those let the process exit while a worker is still
// busy, so a batch ended early with a zero exit status and no index.
declare const Worker: new (url: string) => {
  postMessage(task: SnapshotTask): void;
  onmessage: ((event: { data: SnapshotResult }) => void) | null;
  onerror: ((event: { message: string }) => void) | null;
  terminate(): void;
};

/**
 * Writes every task's snapshot on `jobs` worker threads, handing each the next session as it
 * finishes the last, so one large session does not hold up a fixed share of the batch.
 */
async function inWorkers(tasks: SnapshotTask[], jobs: number, record: (r: SnapshotResult) => void): Promise<void> {
  let next = 0;
  const workers = Array.from({ length: jobs }, () => new Worker(new URL("./worker.ts", import.meta.url).href));
  try {
    await Promise.all(workers.map((worker) => new Promise<void>((resolveWorker, rejectWorker) => {
      const feed = () => {
        if (next < tasks.length) worker.postMessage(tasks[next++]!);
        else resolveWorker();
      };
      worker.onmessage = (event) => {
        // The first session that cannot be read fails the batch now, not after the rest are written.
        if ("error" in event.data) return rejectWorker(new Error(event.data.error));
        record(event.data);
        feed();
      };
      worker.onerror = (event) => rejectWorker(new Error(event.message));
      feed();
    })));
  } finally {
    for (const worker of workers) worker.terminate();
  }
}

if (import.meta.main) {
  main(process.argv.slice(2)).then((c) => process.exit(c)).catch((e) => {
    process.stderr.write(`${e instanceof Error ? e.stack ?? e.message : String(e)}\n`);
    process.exit(1);
  });
}
