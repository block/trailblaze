// The seam between the report viewer and a tab that draws and drives its own DOM (Theater,
// Summary). The viewer re-renders by rewriting its root's markup, so such a tab is MOUNTED into an
// empty container after each render and DISPOSED before the next one: dispose must remove every
// listener, timer, animation frame and media element the mount created. Anything the tab wants to
// survive a remount (its playhead, a layout choice) goes in `state`, which the viewer keeps per
// session and tab for the page's life.
//
// Types only, so either side can import it without pulling the other into its bundle.

export interface ReportTabHost {
  /** The open session, hydrated: its trace, frames, YAML and meta are all present. */
  session: SessionPayload;
  sessionIndex: number;
  /** Every recording that still plays, in session order (the start device's first). May be empty. */
  clips: VideoClip[];
  /** A safe `<img src>` for a trace row's `screenshotFile`, or null when the frame isn't in the report. */
  shotSrc(file: string | null | undefined): string | null;
  /** The session's event streams once inflated; null when it has none or they are still inflating. */
  events: EventStream[] | null;
  /** True while compressed event streams are still inflating; the viewer remounts when they land. */
  eventsPending: boolean;
  /** The session's network log, when it carries one outside its event streams. */
  network: NetworkEvent[] | null;
  /** The reader asked the OS for less motion. */
  reducedMotion: boolean;
  /** Where the route asks the tab to open, as epoch ms on the capture clock, or null for its start. */
  initialEpochMs: number | null;
  /**
   * True when `initialEpochMs` comes from an explicit navigation (a link, the route changing, another
   * tab's "open this moment"), which the tab must honour over its own saved position. False or absent
   * for an ordinary re-render or coming back to the tab, where the saved position wins.
   */
  seek?: boolean;
  /**
   * True when this mount redraws the same tab of the same run the last render showed (data landing,
   * a live update), so the tab can carry on as it was, playback included. False after the reader
   * left the tab or navigated to a moment.
   */
  inPlace?: boolean;
  /** Writes the tab's position (epoch ms) into the address without a history entry; null clears it. */
  setEpochMs(ms: number | null): void;
  /**
   * The link to hand someone for where the report is now, position included: the report's share URL
   * when it was built with one, else this page's address. Absent when the report has no link worth
   * sharing, such as a local file or an embedded frame.
   */
  shareLink?: () => string;
  /** Switches the report to another tab of this run, optionally at an epoch-ms moment. */
  openTab(tab: 'theater' | 'summary' | 'timeline', epochMs?: number | null): void;
  /** Per-session, per-tab scratch that outlives remounts. */
  state: Record<string, unknown>;
}

export interface ReportTabMount {
  dispose(): void;
}
