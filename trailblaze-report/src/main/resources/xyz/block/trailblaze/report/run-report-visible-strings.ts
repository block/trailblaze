// The strings each screen capture showed — its text, box and whether it was on screen — read off
// the session's own log records, where the logging rule put them as each capture was emitted (the
// `visibleStrings` field on the driver, snapshot and LLM request logs). Shared by every surface
// that assembles a report payload: the bun driver, the zip viewer and the live document all
// already hold the logs, so the three cannot disagree on what the Strings tab shows, and no
// session needs a separate file for it.
//
// Types come from the ambient run-report-types.d.ts (VisibleStringsScreen, VisibleStringEntry).

import { normalizedToHostClock } from "../trailrunner/web/app/run-report-extract";

const IMAGE_NAME = /\.(png|webp|jpe?g|gif)$/i;

function decode(value: string): string {
  try {
    return decodeURIComponent(value.replace(/\+/g, " "));
  } catch {
    return value;
  }
}

/**
 * The durable image name inside whatever a log recorded: a bare filename locally, a signed URL on
 * a farm run. The same rules as the Kotlin export's `captureIdFrom`: the last path segment once
 * the query is dropped, else the last query value naming an image, else the reference whole.
 */
export function captureIdFrom(ref: string): string {
  const beforeFragment = ref.split("#")[0];
  const q = beforeFragment.indexOf("?");
  const path = q >= 0 ? beforeFragment.slice(0, q) : beforeFragment;
  const fromPath = decode(path).split("/").pop() || "";
  if (IMAGE_NAME.test(fromPath)) return fromPath;
  if (q >= 0) {
    const fromQuery = beforeFragment.slice(q + 1).split("&")
      .map((kv) => (decode(kv.slice(kv.indexOf("=") + 1)).split("/").pop() || ""))
      .filter((name) => IMAGE_NAME.test(name));
    if (fromQuery.length) return fromQuery[fromQuery.length - 1];
  }
  return ref;
}

function slimEntry(e: any): VisibleStringEntry | null {
  if (!e || typeof e !== "object" || typeof e.text !== "string" || !e.text) return null;
  const out: VisibleStringEntry = {
    text: e.text,
    source: typeof e.source === "string" && e.source ? e.source : "text",
    visible: e.visible !== false,
  };
  if (typeof e.ref === "string" && e.ref) out.ref = e.ref;
  const b = e.bounds;
  if (Array.isArray(b) && b.length === 4 && b.every((n: unknown) => typeof n === "number" && Number.isFinite(n))) {
    out.bounds = [b[0], b[1], b[2], b[3]];
  }
  return out;
}

const screenshotOf = (log: any): string | null =>
  typeof log.screenshotFile === "string" && log.screenshotFile ? log.screenshotFile : null;
const stampedIdOf = (log: any): string | null =>
  typeof log.captureId === "string" && log.captureId ? log.captureId : null;

const timeOf = (log: any): number => {
  const t = Date.parse(typeof log?.timestamp === "string" ? log.timestamp : "");
  return Number.isFinite(t) ? t : Number.POSITIVE_INFINITY;
};

/**
 * Every capture log that carries strings, as screens in capture order. One screen per capture: a
 * driver log and the LLM request made on the same screen can name one image, and the first to
 * name it speaks for it. A capture with no screenshot is named by the id its log was stamped with
 * (`captureId`), so it never merges with another. Logs without the field — recorded before it
 * existed, or with no tree to read — contribute nothing, and neither does a screenshot-less log
 * recorded before ids existed, which has nothing to be named by. Null when no log carries strings.
 * Each screen's `timestamp` is on the host clock, like the Timeline's and the recording's, so a
 * capture can be found in the video.
 */
export function extractVisibleStrings(logs: unknown[] | null | undefined): VisibleStringsScreen[] | null {
  const captures = normalizedToHostClock((Array.isArray(logs) ? logs : []) as TrailblazeLogRecord[])
    .map((log: any, order) => ({ log, order }))
    .filter(({ log }) => log && typeof log === "object" && Array.isArray(log.visibleStrings) && (screenshotOf(log) || stampedIdOf(log)))
    .sort((a, b) => timeOf(a.log) - timeOf(b.log) || a.order - b.order);
  const out: VisibleStringsScreen[] = [];
  const seen = new Set<string>();
  for (const { log } of captures) {
    const ref = screenshotOf(log);
    const screenshot = ref ? captureIdFrom(ref) : null;
    const captureId = screenshot || stampedIdOf(log)!;
    if (seen.has(captureId)) continue;
    seen.add(captureId);
    const strings: VisibleStringEntry[] = [];
    for (const e of log.visibleStrings) {
      const slim = slimEntry(e);
      if (slim) strings.push(slim);
    }
    const screen: VisibleStringsScreen = {
      captureId,
      deviceWidth: typeof log.deviceWidth === "number" && log.deviceWidth > 0 ? log.deviceWidth : 0,
      deviceHeight: typeof log.deviceHeight === "number" && log.deviceHeight > 0 ? log.deviceHeight : 0,
      strings,
    };
    if (ref && screenshot) screen.screenshot = screenshot;
    if (ref && ref !== screenshot) screen.captureUrl = ref;
    if (typeof log.timestamp === "string") screen.timestamp = log.timestamp;
    const action = typeof log.action?.type === "string" ? log.action.type
      : typeof log.displayName === "string" ? log.displayName
      : typeof log.llmRequestLabel === "string" ? log.llmRequestLabel
      : null;
    if (action) screen.action = action;
    if (log.captureCoverage?.looksTruncated === true) screen.partialCapture = true;
    out.push(screen);
  }
  return out.length ? out : null;
}

/**
 * A clock, a balance, or a counter: text that differs on every run. The same rule as the Kotlin
 * `VolatileText.looksVolatile` — a digit and no words except an English AM/PM — judged when read,
 * never stored, so a better rule reaches every session already recorded. Keep the two in step.
 */
export function looksVolatile(text: string): boolean {
  if (!/\d/.test(text)) return false;
  return text.split(/\s+/)
    .filter((word) => /\p{L}/u.test(word))
    .every((word) => ["am", "pm"].indexOf(word.replace(/[^\p{L}]/gu, "").toLowerCase()) >= 0);
}

/**
 * The screenshots the screens name, first seen first. A report embeds only the frames its Timeline
 * uses, and some captures (a text entry, an LLM request) have none there, so each payload assembler
 * adds these to its own list. Each is either a plain image file name — a session file, never a
 * path — or, for a farm capture whose image was never downloaded, the http(s) URL it was recorded
 * under, which every assembler already hands to the browser as a remote shot.
 */
export function visibleStringsShotFiles(lines: VisibleStringsScreen[] | null | undefined): string[] {
  const out: string[] = [];
  for (const line of lines || []) {
    if (!line.strings.length) continue;
    // A line from before `screenshot` existed named its capture by the image.
    const shot = line.screenshot || line.captureId;
    const name = line.captureUrl && /^https?:\/\//i.test(line.captureUrl) ? line.captureUrl
      : shot && /^[\w.-]+\.(png|jpe?g|webp|gif)$/i.test(shot) ? shot
      : null;
    if (name && out.indexOf(name) < 0) out.push(name);
  }
  return out;
}
