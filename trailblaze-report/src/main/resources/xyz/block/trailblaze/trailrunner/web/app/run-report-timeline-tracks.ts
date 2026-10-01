// ---- Timeline tracks and free seeking (pure) ----------------------------------------------------
// The math behind the Timeline tab's seekable scrubber and the event tracks under it. The scrubber's
// axis is time-COMPRESSED (buildPlaybackSchedule clamps every gap), so a position on it is not a
// linear share of the run's wall clock: these functions map between the two the same piecewise way
// the axis's own `tsFrac` maps timestamps onto it, so a mark, the playhead and the recording can
// never disagree about which instant a pixel is.

/** One event drawn on a track: an instant, or an interval when the event says it covers one. */
export interface TrackMark {
  /** The event's key — the same `<stream>-<n>` the Timeline's event list keys its rows by. */
  key: string;
  t: number;
  /** Where the event's interval ends (a request's response), or null for an instant. */
  endT: number | null;
  label: string;
  tone: 'ok' | 'warn' | 'error' | null;
}

/** One track: an event stream, and how many of its events carry a timestamp. */
export interface TimelineTrack {
  name: string;
  streamIndex: number;
  count: number;
}

export type TrackStream = {
  name: string;
  rows?: Array<{ t: number | null; label?: string; tone?: string; endT?: number | null }> | null;
  events?: Array<{ t: number | null; d?: string }> | null;
};

const timed = (t: number | null | undefined): t is number => t != null && Number.isFinite(t);
// A formatted stream is read through its rows (the paired, labelled shape a formatter produced); a
// raw one through its events.
const formatted = (stream: TrackStream) => !!(stream.rows && stream.rows.length);

/**
 * Every stream that has at least one timed event, as a track, in the session's own stream order.
 * Counting only: no event is labelled, so a closed track costs one pass over its timestamps. An
 * event with no timestamp has no place on an axis and is left out; it is still in the event list.
 */
export function timelineTracks(streams: ReadonlyArray<TrackStream>): TimelineTrack[] {
  const tracks: TimelineTrack[] = [];
  streams.forEach((stream, streamIndex) => {
    const items: ReadonlyArray<{ t: number | null }> = (formatted(stream) ? stream.rows : stream.events) || [];
    let count = 0;
    for (const item of items) if (timed(item.t)) count++;
    if (count) tracks.push({ name: stream.name, streamIndex, count });
  });
  return tracks;
}

/**
 * One stream's timed events as marks, in time order: its rows, with a span where a row says where
 * it ends, or its raw events, labelled by `labelOf`. Built only for a track the reader opened.
 */
export function trackMarks(stream: TrackStream, labelOf: (event: { t: number | null; d?: string }, stream: string) => string): TrackMark[] {
  const marks: TrackMark[] = [];
  if (formatted(stream)) {
    stream.rows!.forEach((row, n) => {
      if (!timed(row.t)) return;
      const endT = timed(row.endT) && row.endT > row.t ? row.endT : null;
      const tone = row.tone === 'ok' || row.tone === 'warn' || row.tone === 'error' ? row.tone : null;
      marks.push({ key: `${stream.name}-${n}`, t: row.t, endT, label: String(row.label || ''), tone });
    });
  } else {
    (stream.events || []).forEach((event, n) => {
      if (!timed(event.t)) return;
      marks.push({ key: `${stream.name}-${n}`, t: event.t, endT: null, label: labelOf(event, stream.name), tone: null });
    });
  }
  return marks.sort((a, b) => a.t - b.t);
}

/**
 * The inverse of the axis's `tsFrac`: the wall-clock offset (from the axis's first timestamp) at a
 * fraction of the rail. `real[i]` is entry i's offset and `stepFrac[i]` where it sits on the rail;
 * between two entries the rail is linear in time, which is exactly how `tsFrac` places an instant.
 * Where several entries share one position, that position resolves to the first of their times.
 */
export function fractionToOffset(real: ReadonlyArray<number>, stepFrac: ReadonlyArray<number>, f: number): number | null {
  const n = Math.min(real.length, stepFrac.length);
  if (!n) return null;
  if (f <= stepFrac[0]) return real[0];
  for (let i = 1; i < n; i++) {
    if (f <= stepFrac[i]) {
      const span = stepFrac[i] - stepFrac[i - 1];
      if (span <= 0) return real[i];
      return real[i - 1] + ((f - stepFrac[i - 1]) / span) * (real[i] - real[i - 1]);
    }
  }
  return real[n - 1];
}

/**
 * A pointer position pulled onto the nearest keyframe within `tolerance` (a fraction of the rail),
 * so landing near a step still lands ON it — the snapping the scrubber always had, kept for the
 * moments worth being exact about. Farther than that from every keyframe, the position is left
 * where the reader put it.
 */
export function snapFraction(f: number, targets: ReadonlyArray<number>, tolerance: number): number {
  let best = f;
  let dist = tolerance;
  for (const target of targets) {
    const d = Math.abs(target - f);
    if (d <= dist) { dist = d; best = target; }
  }
  return best;
}

/**
 * The entry on screen at rail position `f`: the last selectable one at or before it. A screen stays
 * up until the next capture replaces it, so the answer is never an entry still to come — except
 * before the first selectable one, where there is nothing earlier and the first is what shows.
 */
export function entryAtOrBefore(stepFrac: ReadonlyArray<number>, selectable: (i: number) => boolean, f: number): number {
  let found = -1;
  let first = -1;
  for (let i = 0; i < stepFrac.length; i++) {
    if (!selectable(i)) continue;
    if (first < 0) first = i;
    if (stepFrac[i] <= f) found = i; else break;
  }
  return found >= 0 ? found : first;
}

/** The mark `dir` places from `key` on a track (or its first/last when `key` isn't on it). */
export function adjacentMark(marks: ReadonlyArray<TrackMark>, key: string | null, dir: 1 | -1): TrackMark | null {
  if (!marks.length) return null;
  const at = key == null ? -1 : marks.findIndex((mark) => mark.key === key);
  if (at < 0) return dir > 0 ? marks[0] : marks[marks.length - 1];
  return marks[at + dir] || null;
}
