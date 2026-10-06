// Formatter for events/crash.ndjson: the crash index CrashEventArtifactWriter (:trailblaze-capture)
// builds from the app's device log once capture stops. One error row per crash, at the instant the
// log line was written, naming the line so the full trace can be found in the log:
//
//   FATAL EXCEPTION: main   [Java crash]   android · device.log line 812

const KIND_LABELS: Record<string, string> = {
  fatal_exception: "Java crash",
  native_crash: "native crash",
  uncaught_exception: "uncaught exception",
  fatal_error: "Swift fatal error",
  terminated_by_signal: "terminated by signal",
};

export default {
  id: "crash",
  // Both forms, as the viewer surfaces both: the stream, plus a multi-device session's `crash.<device>`.
  streams: ["crash", "crash.*"],
  format(entries) {
    return entries.map((e) => {
      const d = e.data && typeof e.data === "object" ? e.data : {};
      const kind = typeof d.kind === "string" ? d.kind : "";
      // Lookup guarded by hasOwnProperty so an unexpected kind never resolves against Object.prototype.
      const kindLabel = Object.prototype.hasOwnProperty.call(KIND_LABELS, kind) ? KIND_LABELS[kind] : kind || "crash";
      const fields: Array<{ k: string; v: string }> = [];
      if (typeof d.platform === "string" && d.platform) fields.push({ k: "platform", v: d.platform });
      const source = d.source && typeof d.source === "object" ? d.source : null;
      if (source && typeof source.path === "string" && source.path) {
        fields.push({ k: "log", v: typeof source.line === "number" ? `${source.path} line ${source.line}` : source.path });
      }
      return {
        t: e.t,
        label: typeof d.summary === "string" && d.summary ? d.summary : "App crashed",
        tone: "error",
        badges: [{ text: kindLabel }],
        fields,
        raw: [e.data],
      };
    });
  },
};
