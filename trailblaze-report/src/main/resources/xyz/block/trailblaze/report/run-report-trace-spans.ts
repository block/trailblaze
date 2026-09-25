// The session's `trace.json` — the spans `TrailblazeTracer` recorded (see `SessionTraceFile`) — as
// the slim `TracerSpan` shape the report embeds for the Perfetto export. Shared by every surface
// that assembles a report payload: the bun driver (from the session directory), the zip viewer
// (from an archive entry) and the live document (from the daemon's /static tree), so the three
// cannot disagree on which spans a report carries.
//
// Types come from the ambient run-report-types.d.ts (TracerSpan).

// A verbose trace (driver internals, per-node selector matching) runs to a few MB of spans; past
// this the file is left out rather than cut, because a truncated span tree loses children silently.
export const MAX_TRACE_BYTES = 16 * 1024 * 1024;

/**
 * The parsed `trace.json` as TracerSpans. Only Complete ("X") events with a finite start and a
 * non-negative duration on a numeric thread are kept; metadata and anything malformed is skipped.
 * `pid` rides along so two processes that happen to share a thread id (a run that started while
 * another was still recording into the same file) stay on separate tracks. Null when the content is
 * not an array or holds no span.
 */
export function slimTracerSpans(parsed: unknown): TracerSpan[] | null {
  if (!Array.isArray(parsed)) return null;
  const out: TracerSpan[] = [];
  for (const e of parsed) {
    if (!e || typeof e !== "object") continue;
    if (e.ph !== undefined && e.ph !== "X") continue;
    if (typeof e.ts !== "number" || !Number.isFinite(e.ts)) continue;
    if (typeof e.dur !== "number" || !Number.isFinite(e.dur) || e.dur < 0) continue;
    if (typeof e.tid !== "number") continue;
    const span: TracerSpan = {
      name: typeof e.name === "string" && e.name ? e.name : "trace",
      cat: typeof e.cat === "string" && e.cat ? e.cat : "app",
      ts: e.ts,
      dur: e.dur,
      tid: e.tid,
    };
    if (typeof e.pid === "number") span.pid = e.pid;
    if (e.args && typeof e.args === "object" && Object.keys(e.args).length) span.args = e.args;
    if (typeof e.kind === "string" && e.kind) span.kind = e.kind;
    if (typeof e.clock === "string" && e.clock) span.clock = e.clock;
    out.push(span);
  }
  return out.length ? out : null;
}

/** `slimTracerSpans` over the file's text; unparseable text yields null like an absent file. */
export function parseTraceFileText(text: string): TracerSpan[] | null {
  try {
    return slimTracerSpans(JSON.parse(text));
  } catch {
    return null;
  }
}
