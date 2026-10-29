// A session's measured device-vs-host clock readings (`session_device_clock.json`) and the conversion that
// puts a `clock: "device"` record on the host timeline. Mirrors the Kotlin `DeviceClockSync`
// (trailblaze-models logs/model/DeviceClockSync.kt); `device-clock-parity-fixtures.json` pins both.
//
// No imports on purpose: RunReportGenerator stages this file beside the bun driver, and the run
// and perf report bundles inline it.

/** One NTP-style read: host clock before the query, the device's answer, host clock after. */
export interface DeviceClockSample {
  hostBeforeMs: number;
  hostAfterMs: number;
  deviceMs: number;
}

export interface DeviceClockTrack {
  deviceId: string;
  samples?: DeviceClockSample[];
  failures?: Array<{ atHostMs: number; error: string }>;
  /** The device's name in a multi-device session: the `.<deviceName>` suffix its streams carry. */
  deviceName?: string | null;
}

export interface DeviceClockSync {
  devices?: DeviceClockTrack[];
  /** The device a multi-device session started on; records that name no device came from it. */
  startDeviceId?: string | null;
}

export const DEVICE_CLOCK_FILE_NAME = "session_device_clock.json";

/** `device − host` for one read, against the round trip's truncated integer midpoint (as Kotlin). */
export function deviceAheadMs(s: DeviceClockSample): number {
  return s.deviceMs - (s.hostBeforeMs + Math.trunc((s.hostAfterMs - s.hostBeforeMs) / 2));
}

/** The read with the shortest round trip; the first wins a tie. Null when empty. */
export function tightestSample(samples: DeviceClockSample[]): DeviceClockSample | null {
  let best: DeviceClockSample | null = null;
  for (const s of samples) {
    if (!best || s.hostAfterMs - s.hostBeforeMs < best.hostAfterMs - best.hostBeforeMs) best = s;
  }
  return best;
}

/**
 * The track for `deviceId`; with none, the session's start device when it named one, else the only
 * sampled device — null when several were sampled, because guessing would put one device's records
 * on another's clock.
 */
export function clockTrackFor(sync: DeviceClockSync | null | undefined, deviceId?: string | null): DeviceClockTrack | null {
  const sampled = (sync?.devices || []).filter((d) => d && (d.samples || []).length > 0);
  if (deviceId != null) {
    const match = sampled.find((d) => d.deviceId === deviceId);
    if (match) return match;
  }
  if (sync?.startDeviceId != null) return sampled.find((d) => d.deviceId === sync.startDeviceId) || null;
  return sampled.length === 1 ? sampled[0] : null;
}

/**
 * A stream or device name as a file-name segment: mirrors the Kotlin `SessionEvents.sanitizeName`
 * (per code point, as Java regex matches, hence the `u` flag).
 */
export function sanitizeStreamName(name: string): string {
  return name.replace(/[^A-Za-z0-9._-]/gu, "_") || "unknown";
}

/**
 * The track for capture stream `streamName`. A stream ending in a named device's `.<deviceName>` is
 * that device's and only its — null when it has no samples, never another device's clock. A stream
 * with no device suffix is attributed as `clockTrackFor` with no id. Compared as file names
 * (`sanitizeStreamName`), the way streams are written.
 */
export function clockTrackForStream(sync: DeviceClockSync | null | undefined, streamName: string): DeviceClockTrack | null {
  const file = sanitizeStreamName(streamName);
  let owner: DeviceClockTrack | null = null;
  let ownerName = "";
  for (const d of sync?.devices || []) {
    if (!d || d.deviceName == null) continue;
    const name = sanitizeStreamName(d.deviceName);
    if (!file.endsWith(`.${name}`)) continue;
    if (!owner || name.length > ownerName.length) {
      owner = d;
      ownerName = name;
    }
  }
  if (!owner) return clockTrackFor(sync);
  return (owner.samples || []).length > 0 ? owner : null;
}

// Sorted once per track, as Kotlin's `byDeviceTime`. Stable sort, matching Kotlin's `sortedBy`.
const SORTED = new WeakMap<DeviceClockTrack, DeviceClockSample[]>();
function sortedSamples(track: DeviceClockTrack): DeviceClockSample[] {
  let sorted = SORTED.get(track);
  if (!sorted) {
    sorted = [...(track.samples || [])].sort((a, b) => a.deviceMs - b.deviceMs);
    SORTED.set(track, sorted);
  }
  return sorted;
}

/** An offset change between adjacent samples larger than this is a clock step, not drift (as Kotlin). */
export const CLOCK_STEP_MS = 1000;

/** Largest `session_device_clock.json` a reader loads; mirrors Kotlin `DeviceClockSyncFile.MAX_BYTES`. */
export const DEVICE_CLOCK_MAX_BYTES = 4 * 1024 * 1024;

/**
 * How far the device ran ahead of the host at device time `deviceMs`: the nearest sample outside
 * the sampled range, linear interpolation between the bracketing samples inside it. Across a clock
 * step (`CLOCK_STEP_MS`) it takes the nearer sample's offset instead, the earlier on a tie.
 */
export function deviceAheadMsAt(track: DeviceClockTrack, deviceMs: number): number | null {
  const sorted = sortedSamples(track);
  if (!sorted.length) return null;
  const first = sorted[0];
  const last = sorted[sorted.length - 1];
  if (deviceMs <= first.deviceMs) return deviceAheadMs(first);
  if (deviceMs >= last.deviceMs) return deviceAheadMs(last);
  // First sample at or after deviceMs, by binary search: a session converts every event, so a
  // linear scan per event is quadratic on a long run.
  let after = 0;
  let end = sorted.length - 1;
  while (after < end) {
    const mid = (after + end) >> 1;
    if (sorted[mid].deviceMs >= deviceMs) end = mid;
    else after = mid + 1;
  }
  const hi = sorted[after];
  const lo = sorted[after - 1];
  if (hi.deviceMs === lo.deviceMs) return deviceAheadMs(hi);
  const loAhead = deviceAheadMs(lo);
  const hiAhead = deviceAheadMs(hi);
  if (Math.abs(hiAhead - loAhead) > CLOCK_STEP_MS) {
    return deviceMs - lo.deviceMs <= hi.deviceMs - deviceMs ? loAhead : hiAhead;
  }
  const fraction = (deviceMs - lo.deviceMs) / (hi.deviceMs - lo.deviceMs);
  return Math.round(loAhead + fraction * (hiAhead - loAhead));
}

/** `deviceMs` on the host timeline, or null when no measured track applies. */
export function deviceToHostMs(
  sync: DeviceClockSync | null | undefined,
  deviceMs: number,
  deviceId?: string | null,
): number | null {
  return trackToHostMs(clockTrackFor(sync, deviceId), deviceMs);
}

/** `deviceMs` on `track`'s device clock moved onto the host timeline; null with no track. */
export function trackToHostMs(track: DeviceClockTrack | null, deviceMs: number): number | null {
  if (!track) return null;
  const ahead = deviceAheadMsAt(track, deviceMs);
  return ahead == null ? null : deviceMs - ahead;
}

const finite = (v: unknown): v is number => typeof v === "number" && Number.isFinite(v);

/** Decode `session_device_clock.json`, dropping malformed samples. Null for unreadable input. */
export function parseDeviceClockSync(text: string): DeviceClockSync | null {
  try {
    const o = JSON.parse(text);
    if (!o || typeof o !== "object" || !Array.isArray(o.devices)) return null;
    return {
      startDeviceId: typeof o.startDeviceId === "string" ? o.startDeviceId : null,
      devices: o.devices
        .filter((d: any) => d && typeof d.deviceId === "string")
        .map((d: any) => ({
          deviceId: d.deviceId,
          deviceName: typeof d.deviceName === "string" ? d.deviceName : null,
          samples: (Array.isArray(d.samples) ? d.samples : []).filter(
            (s: any) => s && finite(s.hostBeforeMs) && finite(s.hostAfterMs) && finite(s.deviceMs),
          ),
        })),
    };
  } catch {
    return null;
  }
}
