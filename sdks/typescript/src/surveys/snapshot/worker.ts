// One snapshot worker: reads the sessions it is handed and writes each one's snapshot, so a batch
// is built on as many cores as the CLI starts workers.

import { writeFileSync } from "node:fs";
import { join } from "node:path";
import { gzipSync } from "node:zlib";

import { SurveySession } from "../session.js";
import { snapshotOf } from "./write.js";

/** A session to write, and the file number it was given. */
export interface SnapshotTask {
  i: number;
  dir: string;
  out: string;
}

/** What the CLI puts in the index for it, or why it could not be written. */
export type SnapshotResult =
  | { i: number; file: string; bytes: number; summary: ReturnType<typeof snapshotOf>["summary"] }
  | { i: number; error: string };

/** Writes one session's snapshot as `<i>.json.gz` under `out`. */
export function writeSnapshot({ i, dir, out }: SnapshotTask): SnapshotResult {
  const snapshot = snapshotOf(SurveySession.load(dir));
  // Numbered rather than named by session id: an id is a trail name and can be long, and the
  // index is what maps one to the other.
  const file = `${i}.json.gz`;
  const bytes = gzipSync(JSON.stringify(snapshot));
  writeFileSync(join(out, file), bytes);
  return { i, file, bytes: bytes.length, summary: snapshot.summary };
}

// Typed here because the surveys tsconfig does not carry the Bun types.
declare const Bun: { isMainThread: boolean };

// Inside a worker thread, the CLI posts one task at a time and waits for its result.
if (!Bun.isMainThread) {
  const port = globalThis as unknown as {
    onmessage: (event: { data: SnapshotTask }) => void;
    postMessage: (result: SnapshotResult) => void;
  };
  port.onmessage = (event) => {
    let result: SnapshotResult;
    try {
      result = writeSnapshot(event.data);
    } catch (e) {
      result = { i: event.data.i, error: `${event.data.dir}: ${e instanceof Error ? e.stack ?? e.message : String(e)}` };
    }
    port.postMessage(result);
  };
}
