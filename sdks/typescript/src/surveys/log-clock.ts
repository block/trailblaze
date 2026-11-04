// Puts a session's device-stamped Trailblaze logs on the host clock, so a log's `timeMs` sits where
// the report places that log's step. A survey, or a derived stream's row, that copies a log's time
// then lines up with the report's timeline.
//
// This MIRRORS `deviceClockOffsets()` / `normalizedToHostClock()` in the report's
// `run-report-extract.ts` and `TrailblazeLogClockNormalization.kt`. A change to one belongs in all
// three, or a derived row and the step it came from disagree about where they sit.

/** The fields of a raw log this reads. */
interface ClockedLog {
  class?: string;
  timestamp?: string;
  clock?: string;
  hostReceivedAt?: string;
  durationMs?: number;
  deviceName?: string | null;
  toolName?: string;
  successful?: boolean;
  traceId?: string | null;
}

interface Offsets {
  byDeviceName: Map<string | null, number>;
  sessionWideMs: number;
  handovers: Array<{ atMs: number; name: string }>;
  devicesByTraceId: Map<string, string | null>;
}

const name = (value: unknown): string | null => (typeof value === "string" && value ? value : null);
const isTool = (log: ClockedLog) => (log.class ?? "").split(".").pop() === "TrailblazeToolLog";
const durationOf = (log: ClockedLog) => (typeof log.durationMs === "number" && Number.isFinite(log.durationMs) ? log.durationMs : 0);

function ms(value: unknown): number | null {
  if (typeof value !== "string" || !value) return null;
  const t = Date.parse(value.replace(/(\.\d{3})\d+/, "$1"));
  return Number.isFinite(t) ? t : null;
}

/** When each binding of a multi-device session became the active one, host clock, ascending. */
function handoversOf(logs: ReadonlyArray<ClockedLog>): Array<{ atMs: number; name: string }> {
  const out: Array<{ atMs: number; name: string }> = [];
  for (const log of logs) {
    if (!isTool(log) || log.clock === "device") continue;
    const device = name(log.deviceName);
    if (!device || (log.toolName === "switchDevice" && log.successful === false)) continue;
    const start = ms(log.timestamp);
    if (start != null) out.push({ atMs: start + durationOf(log), name: device });
  }
  return out.sort((a, b) => a.atMs - b.atMs);
}

function handoverAt(handovers: Array<{ atMs: number; name: string }>, at: number | null): string | null {
  if (at == null) return null;
  let lo = 0;
  let hi = handovers.length;
  while (lo < hi) {
    const mid = (lo + hi) >>> 1;
    if (handovers[mid]!.atMs <= at) lo = mid + 1;
    else hi = mid;
  }
  return lo > 0 ? handovers[lo - 1]!.name : null;
}

const toolDevice = (log: ClockedLog, handovers: Array<{ atMs: number; name: string }>) =>
  name(log.deviceName) ?? handoverAt(handovers, ms(log.hostReceivedAt));

function devicesByTrace(logs: ReadonlyArray<ClockedLog>, handovers: Array<{ atMs: number; name: string }>): Map<string, string | null> {
  const devices = new Map<string, string | null>();
  const ambiguous = new Set<string>();
  for (const log of logs) {
    const traceId = isTool(log) ? name(log.traceId) : null;
    if (!traceId) continue;
    const device = toolDevice(log, handovers);
    if (devices.has(traceId) && devices.get(traceId) !== device) ambiguous.add(traceId);
    else devices.set(traceId, device);
  }
  for (const traceId of ambiguous) devices.delete(traceId);
  return devices;
}

/**
 * Per device, the smallest `hostReceivedAt - (timestamp + durationMs)` over its anchored
 * device-clock tool logs: skew plus the least upload latency. Null when nothing is anchored, as in
 * device logs pulled off the device's disk, which keep their raw times here as in the report.
 */
function offsetsOf(logs: ReadonlyArray<ClockedLog>): Offsets | null {
  const handovers = handoversOf(logs);
  // A running minimum, so a session's size never decides whether it loads.
  const byDeviceName = new Map<string | null, number>();
  let sessionWideMs = Infinity;
  for (const log of logs) {
    if (!isTool(log) || log.clock !== "device") continue;
    const ts = ms(log.timestamp);
    const receivedAt = ms(log.hostReceivedAt);
    if (ts == null || receivedAt == null) continue;
    const key = toolDevice(log, handovers);
    const sample = receivedAt - (ts + durationOf(log));
    byDeviceName.set(key, Math.min(byDeviceName.get(key) ?? Infinity, sample));
    sessionWideMs = Math.min(sessionWideMs, sample);
  }
  if (!byDeviceName.size) return null;
  return { byDeviceName, sessionWideMs, handovers, devicesByTraceId: devicesByTrace(logs, handovers) };
}

function offsetFor(log: ClockedLog, offsets: Offsets): number {
  let device: string | null;
  const traceId = name(log.traceId);
  if (!isTool(log) && !name(log.deviceName) && traceId && offsets.devicesByTraceId.has(traceId)) {
    device = offsets.devicesByTraceId.get(traceId) ?? null;
  } else {
    device = toolDevice(log, offsets.handovers);
  }
  return offsets.byDeviceName.get(device) ?? offsets.sessionWideMs;
}

/** Each log's time on the host clock, in input order; undefined where a log has no readable time. */
export function hostTimesOf(logs: ReadonlyArray<unknown>): Array<number | undefined> {
  const clocked = logs as ReadonlyArray<ClockedLog>;
  const offsets = offsetsOf(clocked);
  return clocked.map((log) => {
    const t = ms(log.timestamp);
    if (t == null) return undefined;
    return offsets && log.clock === "device" ? t + offsetFor(log, offsets) : t;
  });
}

/** The session-wide device→host offset the logs give, the trace viewer's fallback; null when nothing is anchored. */
export function sessionWideOffsetOf(logs: ReadonlyArray<unknown>): number | null {
  return offsetsOf(logs as ReadonlyArray<ClockedLog>)?.sessionWideMs ?? null;
}

/**
 * Whether `session_device_clock.json` (its text) measured the device a session started on, so the
 * report converts that device's records from it: the start device's track, or the only one, with
 * readings. Mirrors `clockTrackFor` with no device id in the report's `device-clock-sync.ts`.
 */
export function measuresStartDevice(text: string | undefined): boolean {
  if (text === undefined) return false;
  let sync: { devices?: unknown; startDeviceId?: unknown };
  try {
    sync = JSON.parse(text);
  } catch {
    return false;
  }
  if (!sync || typeof sync !== "object" || !Array.isArray(sync.devices)) return false;
  // The report's parser keeps only devices with an id and samples with all three finite readings.
  const sampled = (sync.devices as Array<{ deviceId?: unknown; samples?: unknown } | null>).filter(
    (d) => d && typeof d.deviceId === "string" && Array.isArray(d.samples) && d.samples.some(validSample),
  );
  if (typeof sync.startDeviceId === "string") return sampled.some((d) => d!.deviceId === sync.startDeviceId);
  return sampled.length === 1;
}

const finite = (v: unknown): v is number => typeof v === "number" && Number.isFinite(v);
const validSample = (s: unknown) => {
  const r = s as { hostBeforeMs?: unknown; hostAfterMs?: unknown; deviceMs?: unknown } | null;
  return !!r && finite(r.hostBeforeMs) && finite(r.hostAfterMs) && finite(r.deviceMs);
};
