// Formatter for events/memory.ndjson — the app-memory samples MemoryCapture (:trailblaze-capture)
// writes: around every tool call (read in the background by default, so a `before_tool` row is
// stamped with the boundary's time and read a fraction of a second later; exactly before/after in
// diagnostics mode), and periodically whenever the app under test's heap moved. One row per event:
//
//   Heap 40.2 MB of 192.0 MB (+2.0 MB)   [after tapOnElement]   gc forced · pid 17220 · device free 1.21 GB · via ondevice · read 210 ms
//   Footprint 64.2 MB (+1.5 MB)          [before launchApp]     pid 5950 · via simulator
//
// The event is deliberately small — heap used (size − free), the heap limit, the movement since
// the last event, and whether a GC ran first — so every field is checked before it is shown: iOS
// has a footprint instead of a heap and no limit; nothing but the app id while the process is not
// running. A tool row also carries the dispatch's `traceId`, which is for joining to the session
// log rather than for reading: the row already names the tool, so it stays in `raw`.

const REASON_LABELS: Record<string, string> = {
  first: "first sample",
  final: "final sample",
  before_tool: "before tool",
  after_tool: "after tool",
  memory_changed: "heap changed",
  process_started: "process started",
  process_restarted: "process restarted",
  process_died: "process died",
};

function reasonLabel(reason: unknown, tool: unknown, isIos: boolean): string {
  if ((reason === "before_tool" || reason === "after_tool") && typeof tool === "string" && tool) {
    return `${reason === "before_tool" ? "before" : "after"} ${tool}`;
  }
  if (reason === "memory_changed" && isIos) return "footprint changed";
  // Lookup guarded by hasOwnProperty so an unexpected reason never resolves against Object.prototype.
  return typeof reason === "string" && Object.prototype.hasOwnProperty.call(REASON_LABELS, reason)
    ? REASON_LABELS[reason]
    : "memory";
}

function toneFor(reason: unknown, deltaKb: number | null, usedKb: number | null, limitKb: number | null): string | undefined {
  if (reason === "process_died" || reason === "process_restarted") return "warn";
  // Within a tenth of the limit is where the next allocation may be the OutOfMemoryError.
  if (usedKb !== null && limitKb !== null && limitKb > 0 && usedKb >= limitKb * 0.9) return "warn";
  // A jump of 50 MB or more in one step is worth a look whatever prompted the sample.
  if (deltaKb !== null && deltaKb >= 50 * 1024) return "warn";
  return undefined;
}

function isNum(v: unknown): v is number {
  return typeof v === "number" && Number.isFinite(v);
}

// A formatter is STAGED as a standalone module beside its siblings (RunReportGenerator copies this
// directory into the driver's working directory), so it can only import from a subdirectory staged
// with it — not from the report modules one level up. These therefore cannot be shared with
// Replay's memory rail, which draws the same readings in the viewer; instead a test holds the two
// copies to the same output, because one figure printing two ways in one report reads as a fault in
// the measurement rather than in the rendering.
export function formatKb(kb: number): string {
  const abs = Math.abs(kb);
  if (abs >= 1024 * 1024) return `${(kb / (1024 * 1024)).toFixed(2)} GB`;
  if (abs >= 1024) return `${(kb / 1024).toFixed(1)} MB`;
  return `${kb} kB`;
}

export function formatDeltaKb(kb: number): string {
  if (kb === 0) return "±0";
  return `${kb > 0 ? "+" : "−"}${formatKb(Math.abs(kb))}`;
}

export default {
  id: "memory",
  // Both forms: the stream itself, plus the device-scoped `memory.<device>` a multi-device
  // session may write.
  streams: ["memory", "memory.*"],
  format(entries) {
    return entries.map((e) => {
      const d = e.data && typeof e.data === "object" ? e.data : {};
      const isIos = isNum(d.footprintKb) && !isNum(d.heapUsedKb);
      const usedKb = isIos ? d.footprintKb : isNum(d.heapUsedKb) ? d.heapUsedKb : null;
      const limitKb = !isIos && isNum(d.heapLimitKb) ? d.heapLimitKb : null;
      const deltaKb = isNum(d.deltaKb) ? d.deltaKb : null;

      let label: string;
      if (usedKb !== null) {
        label = `${isIos ? "Footprint" : "Heap"} ${formatKb(usedKb)}`;
        if (limitKb !== null) label += ` of ${formatKb(limitKb)}`;
        if (deltaKb !== null) label += ` (${formatDeltaKb(deltaKb)})`;
      } else if (typeof d.appId === "string" && d.appId) {
        // A pid with no figure is a process that IS running and a reading that did not come back
        // (a dump this build's parser does not recognise, a `footprint` that refused). The probes
        // deliberately keep the pid in that case; calling it "not running" would show the reader a
        // crash that never happened.
        label = isNum(d.pid) ? `${d.appId} — memory not readable` : `${d.appId} not running`;
      } else {
        label = "Memory sample";
      }

      const badges = [{ text: reasonLabel(d.reason, d.tool, isIos) }];
      const fields: Array<{ k: string; v: string }> = [];
      if (usedKb !== null && typeof d.gcForced === "boolean") fields.push({ k: "gc", v: d.gcForced ? "forced" : "not forced" });
      if (isNum(d.pid)) fields.push({ k: "pid", v: String(d.pid) });
      // What the DEVICE had left: an app killed with a healthy heap and a nearly full device was
      // reclaimed by the system, which no app-side number shows.
      if (isNum(d.deviceAvailableKb)) fields.push({ k: "device free", v: formatKb(d.deviceAvailableKb) });
      if (typeof d.source === "string" && d.source) fields.push({ k: "via", v: d.source });
      // How long the reading itself took — in diagnostics mode this is the cost the trail paid per sample.
      if (isNum(d.readMs)) fields.push({ k: "read", v: `${d.readMs} ms` });

      const row: any = { t: e.t, label, badges, fields, raw: [e.data] };
      const tone = toneFor(d.reason, deltaKb, usedKb, limitKb);
      if (tone) row.tone = tone;
      return row;
    });
  },
};
