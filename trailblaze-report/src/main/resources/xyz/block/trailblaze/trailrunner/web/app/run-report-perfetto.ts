// ── Open in Perfetto ───────────────────────────────────────────────────────────────────────────
//
// The report's own Replay answers "what was every device doing at this instant". Perfetto answers
// everything after that — zoom to a millisecond, pivot the tool calls by name, SQL over the spans —
// and it would be a mistake to grow those into this page. So the export is built from what the
// page holds: one Perfetto process per device lane, threads for steps / tools / dispatches / LLM
// calls, one per event stream, the spans the session's own tracer recorded (`trace.json`: the
// agent loop, each tool, the driver's calls inside it, every HTTP request), and a heap counter
// when a lane carries app-memory readings — all on the clock the reader is looking at. It is
// handed to ui.perfetto.dev over postMessage — browser to browser, never through a server — which
// is also why it works from a report opened off disk.
//
// Format: Chrome Trace Event JSON (`traceEvents`), timestamps in MICROseconds. Perfetto's JSON
// importer nests `X` (complete) events on one thread by containment, and a pair that overlap
// without nesting is a malformed trace. The report's own threads are therefore flat sequences: rows
// that would nest (a composite's dispatches inside it) go on their own thread, and a row that runs
// past the next on its thread is cut at the next's start. The tracer's threads are the opposite —
// there an overlap IS nesting, so they keep the recorder's thread and are only trimmed where a
// child's separately-stamped end runs past its parent's.

import type { ReplayLaneFailure, ReplayMemorySeries, ReplayStep } from './run-report-trail-replay';

export const PERFETTO_ORIGIN = 'https://ui.perfetto.dev';

/**
 * One Chrome Trace event. `s` is an instant's scope: thread, process or global. `b`/`e` open and
 * close an async slice, matched by `id2.local` — local, so the slice is filed under its process; a
 * plain `id` is global and Perfetto shows those in a "Global Legacy Events" group of their own.
 */
export interface PerfettoTraceEvent {
  name: string;
  cat: string;
  ph: 'X' | 'i' | 'C' | 'M' | 'b' | 'e';
  /** Microseconds on the export clock. */
  ts: number;
  dur?: number;
  pid: number;
  tid: number;
  s?: 't' | 'p' | 'g';
  id2?: { local: string };
  args?: Record<string, unknown>;
}

/** A lane's app-memory readings, on the lane's own run clock. */
export interface PerfettoMemory {
  /**
   * Each reading with the heap limit in force AT it, when the device reported one — per reading and
   * not once for the lane, because a limit that moves mid-run (a restarted process granted a
   * different heap) would otherwise redraw the run's earlier ceilings as the last one.
   */
  samples: { atMs: number; usedKb: number; limitKb: number | null }[];
  /** Instants the app's process died, so the counter can drop to zero there. */
  deaths: number[];
}

/**
 * A lane's memory rail as the export's counter: the levels, the limit they were read against, and
 * the instants the app was found gone so the graph drops to zero across the downtime instead of
 * holding the last figure through it.
 *
 * A stop is drawn only where no reading at its instant OUTLIVED it. A restart is marked at the first
 * reading of the new process — nobody sampled the old one's end — so a live figure shares its
 * instant: a zero there would sit on top of that figure at the same timestamp, and which of the two
 * Perfetto draws is then a question about array order.
 *
 * Sharing an instant does not settle it either way, in either direction: capture clamps a boundary
 * reading's stamp to the last row it emitted, so a stop row can land in the millisecond of the
 * reading before it — and so can the restart's own reading, leaving the dead process's last reading
 * and the new one's first on the same instant. The fact that separates them is which readings the
 * stop ended, and every reading carries it as its own `stopAfterMs`.
 */
export function perfettoMemoryFrom(series: ReplayMemorySeries): PerfettoMemory {
  const survived = new Set(series.samples.filter((sample) => sample.stopAfterMs !== sample.atMs).map((sample) => sample.atMs));
  return {
    samples: series.samples.map((sample) => ({ atMs: sample.atMs, usedKb: sample.usedKb, limitKb: sample.limitKb })),
    deaths: series.stops.filter((atMs) => !survived.has(atMs)),
  };
}

/** One row of a session event stream: an instant on the epoch, a one-line label, and its payload. */
export interface PerfettoStreamRow {
  t: number | null;
  label: string;
  data?: unknown;
}

/** One session event stream (`events/<name>.ndjson`), which becomes its own thread in the process. */
export interface PerfettoStream {
  name: string;
  rows: PerfettoStreamRow[];
  /**
   * Row fields a lane's own counter already graphs, left out of this stream's generic size counter —
   * the rows still land as instants either way. Set to the fields the memory series was built from,
   * on the stream a lane's `memory` came from: those readings are already a track in MB, under the
   * limit, dropping to zero where the app was gone, and two tracks of one series in two units is a
   * reader asking which to believe. Every other figure the row carries — the device's free memory,
   * say — still graphs here, because nothing else graphs it.
   */
  graphed?: string[];
}

/** One device lane, as the trail projections hold it. */
export interface PerfettoLane {
  /** The lane's label — the device — which becomes the Perfetto process name. */
  name: string;
  /** Epoch ms the lane's run clock is zeroed on; null when the lane's rows carry no timestamps. */
  t0: number | null;
  /** The lane's steps, already on the EXPORT clock (the Replay timeline's, aligned or not). */
  steps: ReplayStep[];
  /** Where the lane's run failed, on the export clock, or null. */
  failure: ReplayLaneFailure | null;
  /** The lane's trace rows, epoch-stamped as the session carries them. */
  trace: TraceStep[];
  /** The lane's app-memory readings on its own run clock, or null when none were captured. */
  memory: PerfettoMemory | null;
  /** The session's event streams — network, memory, app logs, whatever producers dropped — one thread each. */
  streams?: PerfettoStream[];
  /** The spans the session's tracer recorded (`trace.json`), epoch-stamped in microseconds. */
  spans?: TracerSpan[];
  /** Lane run clock → export clock. Identity unless the view shows the lanes on some other clock. */
  clock?: (laneMs: number) => number;
}

/** Thread ids, fixed so the same kind of span sits on the same track in every process. */
export const PERFETTO_THREADS = { steps: 1, tools: 2, dispatches: 3, llm: 4 } as const;
/** Event streams take the thread ids from here up, in the order the session lists them. */
export const PERFETTO_FIRST_STREAM_THREAD = 10;
/** The tracer's recording threads take the ids from here up, in order of first appearance. */
export const PERFETTO_FIRST_SPAN_THREAD = 1000;
/** Lane N is process N × this; well clear of the fixed and stream thread ids above. */
export const PERFETTO_PID_STRIDE = 100;
const THREAD_NAMES: Record<number, string> = {
  [PERFETTO_THREADS.steps]: 'Steps',
  [PERFETTO_THREADS.tools]: 'Tools',
  [PERFETTO_THREADS.dispatches]: 'Dispatches',
  [PERFETTO_THREADS.llm]: 'LLM calls',
};
export const PERFETTO_HEAP_COUNTER = 'App heap (MB)';

const micros = (ms: number) => Math.round(ms * 1000);
const megabytes = (kb: number) => Math.round((kb / 1024) * 10) / 10;
const identity = (t: number) => t;
const stepName = (step: ReplayStep) => `${step.num === 0 ? 'TRAILHEAD' : `STEP ${step.num}`}${step.label ? ` · ${step.label}` : ''}`;

/** Drop undefined/null/empty args so the trace stays readable in Perfetto's details pane. */
const compactArgs = (args: Record<string, unknown>): Record<string, unknown> | undefined => {
  const out: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(args)) {
    if (value === undefined || value === null || value === '') continue;
    out[key] = value;
  }
  return Object.keys(out).length ? out : undefined;
};

/**
 * A field whose name says it is an amount of memory or storage — what a counter graph is for.
 */
const SIZE_FIELD = /(Kb|Mb|Gb|Bytes)$/;

/**
 * A change since the last reading, which is a fact about that reading and not a level: graphing it
 * as a counter draws a line that holds the delta until the next row, saying the opposite of what it
 * means. Matched anywhere in the name and case-insensitively, because the producers write it both
 * as a prefix (`deltaKb`) and in the middle (`heapDeltaKb`).
 */
const DELTA_FIELD = /delta/i;

/**
 * The numeric size fields of a stream row's payload, for a counter event's args: Perfetto makes one
 * counter track per key, named `<stream> <field>`. A row's payload is the record a producer wrote,
 * or that record wrapped in the stream line's envelope (`{ timeMs, data }`), so both are looked in.
 * Sizes only: a duration, a status code or a delta on a row is a fact about that event, not a level
 * that holds until the next reading, and graphing it would say otherwise. Null when there is nothing.
 *
 * `graphed` names fields another track already draws — see [PerfettoStream.graphed]. Per field, not
 * per row: a memory reading also carries figures its lane's own counter says nothing about, and
 * those are only graphed here.
 */
export function streamRowSizes(data: unknown, graphed: string[] = []): Record<string, number> | null {
  const record = (value: unknown): Record<string, unknown> | null => (value && typeof value === 'object' && !Array.isArray(value) ? (value as Record<string, unknown>) : null);
  const outer = record(data);
  if (!outer) return null;
  const inner = record(outer.data);
  const sizes: Record<string, number> = {};
  for (const source of inner ? [inner, outer] : [outer]) {
    for (const [key, value] of Object.entries(source)) {
      if (graphed.includes(key)) continue;
      if (typeof value === 'number' && Number.isFinite(value) && SIZE_FIELD.test(key) && !DELTA_FIELD.test(key) && !(key in sizes)) sizes[key] = value;
    }
  }
  return Object.keys(sizes).length ? sizes : null;
}

/**
 * Cut every `X` on a thread that runs past the start of the next one there. Perfetto nests
 * complete events on one thread by containment; a partial overlap is neither nested nor disjoint
 * and the importer rejects it. Rows on one thread are sequential in practice — a tool starts after
 * the last one returned — so the cut only ever trims the tail of a folded or mis-stamped row. The
 * instants on the thread (a failure marker between two steps) take no part: a slice is trimmed
 * against the next SLICE, whatever sits between them.
 */
export function flattenThread(events: PerfettoTraceEvent[]): PerfettoTraceEvent[] {
  const sorted = events.slice().sort((a, b) => a.ts - b.ts);
  const slices = sorted.filter((event) => event.ph === 'X' && event.dur != null);
  for (let i = 0; i < slices.length - 1; i++) {
    const event = slices[i];
    const next = slices[i + 1];
    if (event.ts + (event.dur as number) > next.ts) event.dur = Math.max(0, next.ts - event.ts);
  }
  return sorted;
}

/**
 * Keep every `X` on a recording thread properly nested: a span that outlives the span it opened
 * inside is cut at that parent's end. The tracer stamps a span's start on the wall clock and its
 * duration on the monotonic one, so a child can read as ending a few microseconds after its parent,
 * which Perfetto rejects as a mis-nested slice stack. Everything else is left as recorded: on the
 * tracer's own threads an overlap is nesting — a tool call contains the driver calls it made.
 */
export function nestThread(events: PerfettoTraceEvent[]): PerfettoTraceEvent[] {
  // Longest first among spans starting together, so the enclosing one is the one on the stack.
  const sorted = events.slice().sort((a, b) => a.ts - b.ts || (b.dur || 0) - (a.dur || 0));
  const open: PerfettoTraceEvent[] = [];
  const endOf = (event: PerfettoTraceEvent) => event.ts + (event.dur || 0);
  for (const event of sorted) {
    if (event.ph !== 'X' || event.dur == null) continue;
    while (open.length && endOf(open[open.length - 1]) <= event.ts) open.pop();
    const parent = open[open.length - 1];
    if (parent && endOf(event) > endOf(parent)) event.dur = Math.max(0, endOf(parent) - event.ts);
    open.push(event);
  }
  return sorted;
}

/** Build the trace for a set of lanes. Lane order is process order in Perfetto. */
export function buildPerfettoTrace(lanes: PerfettoLane[]): PerfettoTraceEvent[] {
  const out: PerfettoTraceEvent[] = [];
  lanes.forEach((lane, laneIndex) => {
    // Perfetto badges a thread "main thread" when its tid equals the pid, so keep pids out of the tid range.
    const pid = (laneIndex + 1) * PERFETTO_PID_STRIDE;
    const clock = lane.clock || identity;
    const threads = new Map<number, PerfettoTraceEvent[]>();
    const onThread = (tid: number, event: PerfettoTraceEvent) => {
      const list = threads.get(tid) || [];
      list.push(event);
      threads.set(tid, list);
    };
    // Rows are on the epoch; the lane's clock puts them beside its steps.
    const rowMs = (epochMs: number) => clock(epochMs - (lane.t0 as number));

    for (const step of lane.steps) {
      onThread(PERFETTO_THREADS.steps, {
        name: stepName(step), cat: 'step', ph: 'X', pid, tid: PERFETTO_THREADS.steps,
        ts: micros(step.startMs), dur: micros(Math.max(0, step.endMs - step.startMs)),
        args: compactArgs({ step: step.num, label: step.label, outcome: step.outcome }),
      });
    }
    if (lane.failure) {
      onThread(PERFETTO_THREADS.steps, {
        name: `failed · ${lane.failure.label}`, cat: 'failure', ph: 'i', s: 't', pid, tid: PERFETTO_THREADS.steps,
        ts: micros(lane.failure.atMs), args: compactArgs({ step: lane.failure.stepNum }),
      });
    }

    if (lane.t0 != null) {
      for (const row of lane.trace) {
        // A step's own row is the Steps thread's business; a row with no instant has no place here.
        if (row.objective || row.ts == null) continue;
        // A row's `label` is the tool's name and its `tool` the argument crop the timeline prints
        // beside it (`textRegex: Tap Me`), so the span is named for the tool and carries the crop.
        const tid = row.llm != null ? PERFETTO_THREADS.llm : PERFETTO_THREADS.tools;
        onThread(tid, {
          name: row.label || row.tool || (row.llm != null ? 'LLM call' : 'tool'), cat: row.llm != null ? 'llm' : 'tool', ph: 'X', pid, tid,
          ts: micros(rowMs(row.ts)), dur: micros(Math.max(0, row.ms || 0)),
          args: compactArgs({ detail: row.tool, note: row.note, ok: row.ok, error: row.err, count: row.count, traceId: row.traceId, device: row.device }),
        });
        for (const child of row.children || []) {
          if (child.ts == null) continue;
          onThread(PERFETTO_THREADS.dispatches, {
            name: child.label || child.tool, cat: 'dispatch', ph: 'X', pid, tid: PERFETTO_THREADS.dispatches,
            ts: micros(rowMs(child.ts)), dur: micros(Math.max(0, child.ms || 0)),
            args: compactArgs({ detail: child.tool, ok: child.ok, error: child.err, parent: row.label || row.tool }),
          });
        }
      }
    }

    // Every event stream the session carries is a thread of its own, so a reader can pin the network
    // beside the tools, or pivot the memory readings, without the export knowing what a stream is.
    // Rows are instants: a stream row marks when something was observed, not how long it took. A
    // row's numeric SIZE fields are also graphed as counters, one track per field, which is how a
    // memory reading becomes a heap graph rather than a row of markers.
    const threadNames: Record<number, string> = { ...THREAD_NAMES };
    if (lane.t0 != null) {
      (lane.streams || []).forEach((stream, streamIndex) => {
        const tid = PERFETTO_FIRST_STREAM_THREAD + streamIndex;
        threadNames[tid] = stream.name;
        for (const row of stream.rows) {
          if (row.t == null) continue;
          onThread(tid, {
            name: row.label || stream.name, cat: `stream:${stream.name}`, ph: 'i', s: 't', pid, tid,
            ts: micros(rowMs(row.t)), args: compactArgs({ stream: stream.name, data: row.data }),
          });
          const sizes = streamRowSizes(row.data, stream.graphed);
          if (sizes) out.push({ name: stream.name, cat: `stream:${stream.name}`, ph: 'C', pid, tid: 0, ts: micros(rowMs(row.t)), args: sizes });
        }
      });
    }

    // The session's own trace: what the host process recorded around the work — the agent loop, each
    // tool it dispatched and, when tracing is verbose, the driver's operations inside them — plus
    // whatever a device uploaded of its half. Spans keep the thread the tracer recorded them on, so
    // they nest exactly as they were opened and a tool call opens up into the calls it made. An async
    // span (an HTTP request, observed from whichever callback thread completed it) belongs to no
    // thread and may overlap its neighbours, so it becomes an async slice matched by its own id
    // instead. A span on a device's clock sits seconds off the host's, so its threads stay separate
    // and say so. A run that started while another was still recording shares its trace.json, so
    // two processes can each have a thread 92: a thread is keyed by its process too, and named for
    // it once more than one process is present.
    const asyncEvents: PerfettoTraceEvent[] = [];
    if (lane.t0 != null && lane.spans && lane.spans.length) {
      const spanThreads = new Map<string, number>();
      const processes = new Set(lane.spans.map((span) => span.pid ?? -1));
      const threadFor = (span: TracerSpan): number => {
        const device = span.clock === 'device';
        const key = `${device ? 'device' : 'host'}:${span.pid ?? -1}:${span.tid}`;
        let tid = spanThreads.get(key);
        if (tid == null) {
          tid = PERFETTO_FIRST_SPAN_THREAD + spanThreads.size;
          spanThreads.set(key, tid);
          const where = device ? 'device' : 'host';
          const process = processes.size > 1 && span.pid != null ? ` ${span.pid}` : '';
          threadNames[tid] = `${where}${process} thread ${span.tid}${device ? ' (device clock)' : ''}`;
        }
        return tid;
      };
      let asyncSeq = 0;
      for (const span of lane.spans) {
        const args = compactArgs({ ...(span.args || {}), spanKind: span.kind, clock: span.clock });
        const ts = micros(rowMs(span.ts / 1000));
        const dur = Math.max(0, Math.round(span.dur));
        if (span.args && span.args.async === 'true') {
          const id2 = { local: `async-${++asyncSeq}` };
          asyncEvents.push({ name: span.name, cat: span.cat, ph: 'b', id2, pid, tid: 0, ts, args });
          asyncEvents.push({ name: span.name, cat: span.cat, ph: 'e', id2, pid, tid: 0, ts: ts + dur });
          continue;
        }
        const tid = threadFor(span);
        onThread(tid, { name: span.name, cat: span.cat, ph: 'X', pid, tid, ts, dur, args });
      }
    }

    // Metadata first: Perfetto names the tracks from these, and the sort indexes keep the lanes in
    // the order the reader had them and the threads in the order they read top-down here.
    out.push({ name: 'process_name', cat: '__metadata', ph: 'M', ts: 0, pid, tid: 0, args: { name: lane.name } });
    out.push({ name: 'process_sort_index', cat: '__metadata', ph: 'M', ts: 0, pid, tid: 0, args: { sort_index: laneIndex } });
    for (const tid of Array.from(threads.keys()).sort((a, b) => a - b)) {
      out.push({ name: 'thread_name', cat: '__metadata', ph: 'M', ts: 0, pid, tid, args: { name: threadNames[tid] || `thread ${tid}` } });
      out.push({ name: 'thread_sort_index', cat: '__metadata', ph: 'M', ts: 0, pid, tid, args: { sort_index: tid } });
      const layout = tid >= PERFETTO_FIRST_SPAN_THREAD ? nestThread : flattenThread;
      out.push(...layout(threads.get(tid) as PerfettoTraceEvent[]));
    }
    out.push(...asyncEvents);

    // Memory is a process-level counter: one series for the reading, one for the limit, so the
    // headroom is visible without arithmetic. A death drops the reading to zero at the instant, so
    // the graph shows the gap the app spent dead instead of holding the last figure across it.
    if (lane.memory) {
      const memory = lane.memory;
      const counter = (atMs: number, usedKb: number, limitKb: number | null) => out.push({
        name: PERFETTO_HEAP_COUNTER, cat: 'memory', ph: 'C', pid, tid: 0, ts: micros(clock(atMs)),
        args: limitKb != null ? { used: megabytes(usedKb), limit: megabytes(limitKb) } : { used: megabytes(usedKb) },
      });
      // A zero at a death is drawn under the ceiling that was in force when the app went, which is
      // the last reading at or before it — a death can share the instant of the reading before it.
      const limitAt = (atMs: number): number | null => memory.samples.reduce<number | null>(
        (inForce, sample) => (sample.atMs <= atMs ? sample.limitKb : inForce),
        null,
      );
      for (const sample of memory.samples) counter(sample.atMs, sample.usedKb, sample.limitKb);
      for (const death of memory.deaths) {
        out.push({ name: 'process died', cat: 'memory', ph: 'i', s: 'p', pid, tid: 0, ts: micros(clock(death)) });
        counter(death, 0, limitAt(death));
      }
    }
  });
  return rebaseToZero(out);
}

/**
 * Perfetto drops any event with a negative timestamp, and a lane's clock is zeroed on its first
 * traced row — a reading a producer took before that (a memory sample during launch, a log line
 * from the device booting) sits before zero. So when anything does, the whole export slides later
 * by that much: every relationship between events is kept, and the trace simply begins earlier.
 */
function rebaseToZero(events: PerfettoTraceEvent[]): PerfettoTraceEvent[] {
  let earliest = 0;
  for (const event of events) if (event.ph !== 'M' && event.ts < earliest) earliest = event.ts;
  if (earliest === 0) return events;
  return events.map((event) => (event.ph === 'M' ? event : { ...event, ts: event.ts - earliest }));
}

/** The file Perfetto receives: the event list under `traceEvents`, as Chrome writes it. */
export function perfettoTraceJson(events: PerfettoTraceEvent[]): string {
  return JSON.stringify({ traceEvents: events, displayTimeUnit: 'ms' });
}

// ── Handing the trace over ─────────────────────────────────────────────────────────────────────
//
// ui.perfetto.dev's deep-link protocol: open the UI in a new window, post `PING` until it answers
// `PONG` (the channel is unbuffered — a message sent before its listener exists is lost), then post
// `{ perfetto: { buffer, title, fileName } }`. The window MUST be opened synchronously inside the
// click that asked for it, or the popup blocker eats it — so opening and handing over are two
// calls, and the caller may do slow work (inflating the event payloads) between them.

export interface PerfettoWindow {
  postMessage(message: unknown, targetOrigin: string): void;
  closed?: boolean;
  /**
   * Shut the tab. The window has to be opened in the click, before the trace exists, so a build
   * that fails afterwards leaves a Perfetto sitting there waiting for a trace that will never
   * arrive — the caller closes it rather than leaving the reader looking at an empty one.
   */
  close?(): void;
}

/** The browser surface the handshake needs, injectable so the protocol is testable without one. */
export interface PerfettoHost {
  open(url: string): PerfettoWindow | null;
  /**
   * Subscribe to incoming messages — the data, the sender's origin and the sending window (a
   * MessageEvent's `source`); returns the unsubscribe.
   */
  onMessage(handler: (data: unknown, origin: string, source: unknown) => void): () => void;
  setInterval(fn: () => void, ms: number): unknown;
  clearInterval(handle: unknown): void;
  setTimeout(fn: () => void, ms: number): unknown;
  clearTimeout(handle: unknown): void;
}

export interface PerfettoPayload {
  buffer: ArrayBuffer;
  title: string;
  fileName: string;
}

export type PerfettoHandoff = 'opened' | 'closed' | 'timeout';

/** Open the Perfetto UI. Null means the popup was blocked — the caller should fall back to a download. */
export const openPerfettoWindow = (host: PerfettoHost): PerfettoWindow | null => host.open(PERFETTO_ORIGIN);

/**
 * Complete the handshake with an already-open Perfetto window and hand it the trace. Resolves
 * `opened` once the trace is posted, `closed` if the reader shut the window first, `timeout` if
 * the UI never answered within `timeoutMs` (offline, or a page that isn't Perfetto). Only a PONG
 * from THIS window counts: two exports in flight each have their own Perfetto tab, and the first
 * tab's answer must not make the second post its trace to a page that isn't listening yet.
 */
export function handToPerfetto(win: PerfettoWindow, host: PerfettoHost, payload: PerfettoPayload, timeoutMs = 20_000): Promise<PerfettoHandoff> {
  return new Promise((resolve) => {
    let done = false;
    let unsubscribe: () => void = () => {};
    let ping: unknown = null;
    let deadline: unknown = null;
    const finish = (result: PerfettoHandoff) => {
      if (done) return;
      done = true;
      unsubscribe();
      if (ping != null) host.clearInterval(ping);
      if (deadline != null) host.clearTimeout(deadline);
      resolve(result);
    };
    unsubscribe = host.onMessage((data, origin, source) => {
      if (origin !== PERFETTO_ORIGIN || data !== 'PONG' || source !== win) return;
      win.postMessage({ perfetto: { buffer: payload.buffer, title: payload.title, fileName: payload.fileName } }, PERFETTO_ORIGIN);
      finish('opened');
    });
    const knock = () => {
      if (win.closed) { finish('closed'); return; }
      win.postMessage('PING', PERFETTO_ORIGIN);
    };
    // Timers are armed BEFORE the first knock: a window the reader already closed finishes on that
    // knock, and `finish` can only clear the handles it has been given.
    ping = host.setInterval(knock, 100);
    deadline = host.setTimeout(() => finish('timeout'), timeoutMs);
    knock();
  });
}

/** The trace JSON as the bytes Perfetto wants — a real ArrayBuffer, not a view over one. */
export function perfettoBuffer(json: string): ArrayBuffer {
  const bytes = new TextEncoder().encode(json);
  return bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) as ArrayBuffer;
}
