// The viewer shell's loader: the chrome and load paths that turn a session archive into a rendered
// report IN THE SAME DOCUMENT, with no daemon and no backend. buildViewerShellHtml emits the markup
// this wires up (see run-report-html.ts) plus the two scripts it composes:
//
//   window.TbZipReport      — zip-report-core.js: archive bytes → per-session renderer inputs
//   window.__TB_BOOT_REPORT__ — boots the viewer once a payload is in place
//
// The log-derivation and payload-shaping functions come from ordinary imports, so they are bundled
// into THIS script rather than published on the window by the viewer bundle — that keeps them out of
// the bundle every exported report embeds, which never calls them.
//
// Three ways in, all client-side:
//   1. ?zip=<url-encoded archive URL> — a shareable permalink; also the deep-link carrier, since the
//      viewer's own route keys (tab/step/run) ride alongside it on the SAME url and it never strips
//      unknown params. `?zip=…&tab=lightbox` therefore opens straight on the Lightbox.
//   2. Paste a URL and press Render — navigates to form 1 rather than rendering in place, so the
//      address bar is always the permalink and the viewer is never re-entered mid-session. Enter
//      (and Add) line the URL up as a row instead, which is how several are assembled before any
//      of them renders; Render takes the field along with the list either way.
//   3. Drop .zip files on the page, or pick them — read locally, rendered in place. Nothing uploads,
//      and there is no URL to share, so Share stays disabled for these.
//
// All three feed ONE list of sources (see ArchiveSource), which is what makes several archives
// combinable at all: a URL can be added without rendering, a file can be dropped alongside it, and
// any row can be taken back out. A list of URLs still renders via form 1's permalink; a list holding
// any local file has no address, so it renders where it is.
//
// Rendering happens in place (window.__TB_RUN_DATA__ + the viewer's own boot), NOT into a child
// frame: an `about:srcdoc` frame inherits this document's origin anyway, so a frame bought no
// isolation while costing the viewer its URL — no deep links, no Copy link. Real isolation would
// mean a sandboxed frame with an opaque origin, which is a deliberate trade, not a default.

// Out-of-directory like run-report-core.ts's own import: the events module lives beside the bun
// driver (report/), and every surface must reach the SAME decode/detection implementation.
import { ATTACHMENT_MATERIALIZE_MAX_TOTAL_BYTES, ATTACHMENT_MIME, buildEventStream, collectStreamAttachmentRefs, isSafeSessionRelativePath, MAX_ATTACHMENTS_PER_SESSION, MAX_EVENT_STREAM_BYTES, MAX_EVENT_STREAMS_TOTAL_CHARS } from '../../../report/run-report-events';
import { extractLlmLogs, extractTrace, originalYamlFromLogs, toSessionPayloads, traceScreenshotFiles } from './run-report-extract';
import { VIEWER_ROUTE_KEYS } from './run-report-route';

const ZIP_PARAM = 'zip';

// The collaborator zip-report-core's resolveRenderer expects; field names match what it looks for.
// This shell embeds the viewer bundle alone, so a function the zip pipeline consults and this object
// omits has no window fallback to land on — it would surface only as a broken archive load. Exported
// so the pipeline's own tests derive through THIS object rather than a stub that can't go stale.
// The two attachment-policy values ride here for exactly that reason: the pipeline defines neither
// itself (run-report-events.ts is the single home), so an object without them materializes no
// attachment at all, silently.
export const REPORT_DERIVE = { extractTrace, extractLlmLogs, originalYamlFromLogs, traceScreenshotFiles, buildEventStream, collectStreamAttachmentRefs, ATTACHMENT_MIME, MAX_ATTACHMENTS_PER_SESSION, ATTACHMENT_MATERIALIZE_MAX_TOTAL_BYTES, isSafeSessionRelativePath, MAX_EVENT_STREAM_BYTES, MAX_EVENT_STREAMS_TOTAL_CHARS };

// The permalink for one or more archive URLs — a repeated `zip` param, one per archive, so a link
// can carry the same trail's runs across several devices and render them as one report. Each value
// is percent-encoded as a single opaque string so an archive URL that carries its own query (a
// signed artifact URL's `jwt`, an S3 `key`) survives intact instead of its `&`s splitting into
// params of this page.
export function zipPermalink(pathname: string, archiveUrls: string[]): string {
  const urls = archiveUrls.map((url) => String(url || '').trim()).filter(Boolean);
  return `${pathname}?${urls.map((url) => `${ZIP_PARAM}=${encodeURIComponent(url)}`).join('&')}`;
}

// Archive URLs pasted as one string: whitespace/newline separated (URLs carry no whitespace — a
// list pasted out of a build log or a doc splits cleanly), tolerating a stray trailing comma.
export function splitArchiveUrls(raw: string): string[] {
  return String(raw || '').split(/[\s,]+/).map((url) => url.trim()).filter(Boolean);
}

/**
 * One archive the reader has lined up to render. The shell keeps a LIST of these rather than a
 * single field, because assembling a multi-archive report by editing one whitespace-separated
 * string is only workable for URLs — a local file has no text form at all, so before this there was
 * no way to combine two of them.
 *
 * A URL source can be shared (it becomes a `zip` param); a file source can only be read here.
 */
export type ArchiveSource = { url: string } | { name: string; file: ArchiveFile };

// What the loader needs of a dropped or picked archive: its bytes, plus the two properties that
// tell two same-named files apart. Structural rather than `File` so a test can stand one up, and
// narrower than `unknown` so a source that can't actually be read is a compile error here rather
// than a broken load in the browser.
export type ArchiveFile = { arrayBuffer(): Promise<ArrayBuffer>; size?: number; lastModified?: number };

const isUrlSource = (source: ArchiveSource): source is { url: string } => 'url' in source;

// What the reader sees for a source.
function sourceLabel(source: ArchiveSource): string {
  return isUrlSource(source) ? source.url : source.name;
}

// The identity two sources are deduped on. NOT the label: `session.zip` is what every archive off a
// CI artifact store is called, so two runs downloaded to different folders are the same name and
// different files — deduping those on the name is exactly the multi-archive case this list exists
// for, silently dropping one lane. Size and modified time are what the browser gives us to tell them
// apart without reading the bytes.
export function sourceKey(source: ArchiveSource): string {
  if (isUrlSource(source)) return `url:${source.url}`;
  const file = source.file;
  return `file:${source.name}:${(file && file.size) ?? ''}:${(file && file.lastModified) ?? ''}`;
}

// Appending pasted text to the list. One paste may carry several URLs (that is how a list out of a
// build log arrives), and a URL already on the list is not added twice — a second paste of the same
// address is a re-paste, not a request to render it twice.
export function addArchiveUrls(sources: ArchiveSource[], raw: string): ArchiveSource[] {
  return appendSources(sources, splitArchiveUrls(raw).map((url) => ({ url })));
}

// Appending sources of either kind — the shared primitive every entry point routes through, so the
// no-duplicates rule is stated once: a second paste of the same address, or a re-drop of the same
// file, is a repeat rather than a request to render that run twice.
export function appendSources(sources: ArchiveSource[], added: ArchiveSource[]): ArchiveSource[] {
  const next = sources.slice();
  const seen = new Set(next.map(sourceKey));
  added.forEach((source) => {
    const key = sourceKey(source);
    if (!sourceLabel(source) || seen.has(key)) return;
    seen.add(key);
    next.push(source);
  });
  return next;
}

// Taking one row back out, keyed on its position — the whole reason the list exists. Returns the
// list unchanged (same identity) for an index that names no row, so a stale button in markup the
// reader has already replaced can't truncate the list from the end.
export function removeSourceAt(sources: ArchiveSource[], at: number): ArchiveSource[] {
  if (!Number.isInteger(at) || at < 0 || at >= sources.length) return sources;
  return sources.filter((_, i) => i !== at);
}

// Whether a list has an address of its own. A list holding any local file does not — the bytes live
// on the reader's disk — which decides BOTH that it renders in place rather than navigating and that
// Share stays off. One predicate, because those two answers going out of step is exactly the bug
// worth preventing: a list that renders in place while advertising a link reproduces nothing.
export function sourcesShareable(sources: ArchiveSource[]): boolean {
  return sources.length > 0 && sources.every(isUrlSource);
}

// The shareable address for a list, or '' when there isn't one.
export function sourcesPermalink(pathname: string, sources: ArchiveSource[]): string {
  if (!sourcesShareable(sources)) return '';
  return zipPermalink(pathname, (sources as Array<{ url: string }>).map((source) => source.url));
}

/**
 * What pressing Render does with the list plus whatever is typed but not yet added. The fork is
 * stated here rather than inside the handler because it is the one decision that changes what the
 * button means: a list of URLs NAVIGATES to its own permalink, so the address bar is always the link
 * the reader could share and the viewer is booted once per document; a list holding a local file has
 * nowhere to navigate to, so it renders where it is.
 */
export type RenderPlan =
  | { kind: 'none' }
  | { kind: 'navigate'; href: string; sources: ArchiveSource[] }
  | { kind: 'here'; sources: ArchiveSource[] };

export function renderPlan(pathname: string, sources: ArchiveSource[], typed: string): RenderPlan {
  const staged = addArchiveUrls(sources, typed);
  if (!staged.length) return { kind: 'none' };
  const href = sourcesPermalink(pathname, staged);
  return href ? { kind: 'navigate', href, sources: staged } : { kind: 'here', sources: staged };
}

// What to say when the list changes. The rows are written far from the control that caused them —
// Add is in the bar, a drop can come from anywhere on the page — so a screen reader gets no signal
// from the change itself. A short delta, not the list: re-reading four surviving artifact URLs
// because a fifth was removed buries the one fact the reader asked for. '' means "say nothing".
export function listAnnouncement(before: number, after: number, asked = false): string {
  const lined = after === 1 ? '1 archive lined up' : `${after} archives lined up`;
  if (before !== after) {
    const moved = Math.abs(after - before);
    return `${after > before ? 'Added' : 'Removed'} ${moved} archive${moved === 1 ? '' : 's'}. ${lined}.`;
  }
  // Add pressed over an address already on the list: nothing moves on screen, so silence here is
  // indistinguishable from a control that doesn't work. `asked` is what tells the two apart — an
  // ordinary re-render of an unchanged list says nothing.
  return asked ? `Already lined up. ${lined}.` : '';
}

// Where focus goes after a chip is removed. Removing one destroys the button that had focus, which
// drops it to the document and strands a keyboard reader at the top of the page. It lands on the row
// that took the removed one's place — the last row, when the removed one was last — and -1 means the
// list is empty, so focus belongs back in the field. null is "not a removal": an ordinary re-render
// must leave focus wherever the reader put it.
export function focusIndexAfterRemoval(removedAt: number, remaining: number): number | null {
  if (!Number.isInteger(removedAt) || removedAt < 0) return null;
  return remaining > 0 ? Math.min(removedAt, remaining - 1) : -1;
}

// Full HTML escape: these strings carry reader-supplied content (a pasted URL, a file name off
// disk), so every special is escaped before it reaches innerHTML.
const escapeHtml = (s: unknown): string => String(s == null ? '' : s)
  .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
  .replace(/"/g, '&quot;').replace(/'/g, '&#39;');

// The lined-up list, as chips. Each carries the index it removes, so a row can be taken back out
// without retyping the rest — the thing a single whitespace-separated field could never offer.
export function sourceListHtml(sources: ArchiveSource[]): string {
  return sources.map((source, i) => {
    const label = sourceLabel(source);
    const kind = isUrlSource(source) ? 'url' : 'file';
    // A button announces its own name and nothing else, so a signed artifact URL would be read out
    // in full — hundreds of characters — every time a keyboard reader passed one. The position is
    // what identifies a URL row anyway, and the chip's own text still carries the address. A file
    // name is short and IS the only identity a dropped file has, so it stays.
    const remove = isUrlSource(source) ? `Remove archive ${i + 1} of ${sources.length}` : `Remove ${label}`;
    return `<span class="tb-shell-src tb-shell-src-${kind}" role="listitem" title="${escapeHtml(isUrlSource(source) ? label : `${label} — read from your machine`)}">`
      + `<span class="tb-shell-srcname">${escapeHtml(label)}</span>`
      + `<button class="tb-shell-srcx" type="button" data-tb-remove="${i}" aria-label="${escapeHtml(remove)}">×</button>`
      + `</span>`;
  }).join('');
}

// What the spinner says while a list loads. A URL is downloaded and a file is read, so a mixed list
// is neither — claiming "Downloading" over a list that is half local files describes work the page
// isn't doing, and a reader watching an offline load wonders what it is reaching for.
export function loadingMessage(sources: ArchiveSource[]): string {
  const many = sources.length > 1 ? `${sources.length} archives` : 'archive';
  if (sources.every(isUrlSource)) return `Downloading ${many}…`;
  if (!sources.some(isUrlSource)) return `Reading ${sources.length > 1 ? many : sourceLabel(sources[0])}…`;
  return `Loading ${many}…`;
}

// Whether the Render button can be pressed, and what it says. Counts what would ACTUALLY render —
// the list plus whatever is typed but not yet added — because the two disagree constantly: the
// primary documented path is "paste a URL, press Render", and a button gated on the list alone is
// dead at exactly that moment. A load in flight refuses the press for a different reason, and it is
// folded in here so the whole contract is one function: the button has three triggers (the list
// changing, the field being typed in, a load starting or ending) and any of them recomputing the
// state differently is a button that goes dead, or live, at the wrong moment.
export function renderButtonState(sources: ArchiveSource[], typed: string, loading = false): { disabled: boolean; label: string } {
  const staged = addArchiveUrls(sources, typed);
  return { disabled: loading || !staged.length, label: staged.length > 1 ? `Render ${staged.length} archives` : 'Render' };
}

// Every archive URL the address carries (`?zip=` may repeat — one report per device, one view).
export function zipParamsFrom(href: string): string[] {
  try { return new URL(String(href)).searchParams.getAll(ZIP_PARAM).map((url) => url.trim()).filter(Boolean); } catch (e) { return []; }
}

// The address to leave behind when a report is loaded from a LOCAL file: relative, with the archive
// param and the viewer's route keys dropped. Content read off the user's disk has no address at all,
// so keeping either would let the URL describe something it can't reproduce — a stale `tab`/`step`
// would also be applied to the newly-loaded archive. Returns '' when there is nothing to rewrite, so
// the caller can skip the history write entirely. A malformed href yields '' for the same reason.
export function addressWithoutArchive(href: string): string {
  try {
    const url = new URL(String(href));
    const dropped = [ZIP_PARAM, ...VIEWER_ROUTE_KEYS].filter((key) => url.searchParams.has(key));
    if (!dropped.length) return '';
    dropped.forEach((key) => url.searchParams.delete(key));
    return `${url.pathname}${url.search}${url.hash}`;
  } catch (e) { return ''; }
}

// `<n> steps · <size>` for one loaded archive (or `<n> sessions · <size>` for a multi-session one) —
// the header chip's text.
export function describeArchive(sessions: Array<{ trace?: unknown[] }>, zipBytes: number): string {
  const steps = sessions.reduce((total, s) => total + (s.trace || []).length, 0);
  const size = zipBytes < 1048576 ? `${Math.round(zipBytes / 1024)} KB` : `${(zipBytes / 1048576).toFixed(1)} MB`;
  return `${sessions.length === 1 ? `${steps} steps` : `${sessions.length} sessions`} · ${size}`;
}

// What to tell the user when an archive URL could not be fetched. A cross-origin GET the archive host
// doesn't allow surfaces as an opaque network error, indistinguishable here from the host being down,
// so name both possibilities rather than guessing. The URL is part of the message because a list can
// hold several archives and "Failed to fetch" alone doesn't say which one to take back out.
export function fetchFailureMessage(archiveUrl: string, error: unknown): string {
  return `${errorDetail(error)} — ${archiveUrl}\n\nIf the host is up, it may not send Access-Control-Allow-Origin for this page.`;
}

// What a thrown value says for itself. Anything can be thrown, and a rejected fetch or a zip
// pipeline that throws a string would otherwise reach the reader as "[object Object]".
const errorDetail = (error: unknown): string => (error && (error as Error).message) || String(error);

// The same naming, for a source whose bytes failed to arrive. A body can fail AFTER its headers did
// not — a dropped connection, a decoding error — and that throws from the body read rather than from
// the request, where no CORS hint applies: the response plainly arrived. Without this a list of
// archives reports a bare "network error" naming none of them.
export function readFailureMessage(sourceName: string, error: unknown): string {
  return `${errorDetail(error)} — could not read ${sourceName}`;
}

// A zip that downloaded fine but isn't a Trailblaze archive throws from the pipeline rather than the
// download, so the naming the fetch path does has to be repeated. Only when there are several: one
// archive's own error already names itself by being the only one, and wrapping it would just repeat
// what the reader typed back at them.
export function archiveFailure(error: unknown, label: string, many: boolean): unknown {
  return many ? new Error(readFailureMessage(label, error)) : error;
}

// Several archives into one report's worth of input. `generatedAt` comes from the first: the
// archives were generated at different moments and the report carries one stamp, so the list's own
// order — the reader's order — decides which. Pure because the claim that makes the whole feature
// work is here: the sessions concatenate in list order, which is what the run index, the device
// matrix, and the Trail view all lane up.
export function combineArchives(built: Array<{ generatedAt: string; sessions: unknown[]; zipBytes: number }>): { generatedAt: string; sessions: unknown[]; totalBytes: number } {
  return {
    generatedAt: (built[0] && built[0].generatedAt) || '',
    sessions: built.flatMap((b) => b.sessions || []),
    totalBytes: built.reduce((total, b) => total + (b.zipBytes || 0), 0),
  };
}

// Every object URL a loaded payload owns, so the next load can hand their bytes back to the
// browser. An object URL pins its Blob for the life of the DOCUMENT, and this shell replaces one
// report with another in place — reading archive after archive would otherwise stack everything it
// has ever shown in memory until the tab closes. Two producers mint them, and BOTH have to be swept
// or the untouched one leaks exactly as before: the recording clip (`videoClip.url`) and the zip
// pipeline's attachment map (one per materialized media file, so an archive full of audio pins far
// more here than the single clip does). Only `blob:` URLs are returned: anything else — a `/static`
// link, a `data:` embed — was not minted here and is not ours to revoke.
export function objectUrlsToRevoke(data: unknown): string[] {
  const sessions = data && typeof data === 'object' ? (data as { sessions?: unknown }).sessions : null;
  if (!Array.isArray(sessions)) return [];
  const urls = new Set<string>();
  const add = (value: unknown) => { const url = String(value || ''); if (url.startsWith('blob:')) urls.add(url); };
  sessions.forEach((session) => {
    if (!session || typeof session !== 'object') return;
    const s = session as { videoClip?: { url?: unknown }; attachments?: unknown };
    add(s.videoClip && s.videoClip.url);
    if (s.attachments && typeof s.attachments === 'object') Object.values(s.attachments).forEach(add);
  });
  return [...urls];
}

// One load in flight: the id every await in the pipeline checks before it touches the screen, the
// controller that stops its transfers, the header text it covered up, and which archives it holds.
type LoadRun = { id: number; abort: AbortController; stats: string; keys: Set<string> };

export function RUN_REPORT_SHELL(): void {
  const byId = (id: string) => document.getElementById(id);
  const shell = byId('tb-shell');
  const urlInput = byId('tb-shell-url') as HTMLInputElement | null;
  const renderBtn = byId('tb-shell-render') as HTMLButtonElement | null;
  const shareBtn = byId('tb-shell-share') as HTMLButtonElement | null;
  const pickBtn = byId('tb-shell-pick') as HTMLButtonElement | null;
  const addBtn = byId('tb-shell-add') as HTMLButtonElement | null;
  const list = byId('tb-shell-list');
  const live = byId('tb-shell-live');
  const collapseBtn = byId('tb-shell-collapse') as HTMLButtonElement | null;
  const handleBtn = byId('tb-shell-handle') as HTMLButtonElement | null;
  const fileInput = byId('tb-shell-file') as HTMLInputElement | null;
  const stats = byId('tb-shell-stats');
  const panel = byId('tb-shell-panel');
  const overlay = byId('tb-shell-overlay');
  const app = byId('app');
  if (!shell || !panel || !app) return;

  const idleHtml = panel.innerHTML; // the "how to load a report" copy, restored on error-free reset
  let runId = 0;

  // Once a report is on screen the loader bar has done its job, so it collapses to a slim handle
  // and gives the report the height back. It reopens from the handle — and on a load FAILURE, since
  // the recovery (retype the URL, pick another file) lives on the bar the reader just lost.
  const setCollapsed = (min: boolean) => shell.classList.toggle('tb-shell-min', min);
  if (collapseBtn) collapseBtn.onclick = () => setCollapsed(true);
  if (handleBtn) handleBtn.onclick = () => { setCollapsed(false); if (collapseBtn) collapseBtn.focus({ preventScroll: true }); };

  const showPanel = (html: string) => { panel.innerHTML = html; panel.style.display = 'flex'; app.style.display = 'none'; };
  // Clear the inline display rather than setting one: the report stylesheet makes #app a flex
  // column, and an inline `display: block` outranks it, collapsing the viewer's own layout.
  const showReport = () => { panel.style.display = 'none'; app.style.display = ''; };
  const spinner = (msg: string) => showPanel(`<div class="tb-shell-spinner"></div><div class="tb-shell-sub">${escapeHtml(msg)}</div>`);
  const failure = (msg: string) => { setCollapsed(false); showPanel(`<div class="tb-shell-err">${escapeHtml(msg)}</div><div class="tb-shell-sub">${idleHtml}</div>`); };

  // `shareable` says whether the CURRENT report came from a URL; the link itself is read at click
  // time, never captured here. The viewer rewrites location.search as the user moves between runs,
  // tabs, and steps, so a href snapshotted at load would keep copying the entry route — Share would
  // silently contradict the deep link it advertises.
  const setShare = (shareable: boolean) => {
    if (!shareBtn) return;
    shareBtn.disabled = !shareable;
    shareBtn.textContent = 'Share';
    shareBtn.title = shareable
      ? 'Copy a link to what you are looking at'
      : 'Load a report from a URL to get a shareable link';
    shareBtn.onclick = shareable
      ? () => {
        // writeText rejects on permission/insecure-context failures (a cross-origin embed without
        // allow="clipboard-write" among them), so only claim success once it settles.
        const done = (text: string) => { shareBtn.textContent = text; setTimeout(() => { shareBtn.textContent = 'Share'; }, 1200); };
        try {
          Promise.resolve(navigator.clipboard.writeText(String(location.href || ''))).then(() => done('Copied'), () => done('Copy failed'));
        } catch (e) { done('Copy failed'); }
      }
      : null;
  };

  // Give a payload's video and attachment bytes back to the browser.
  const revokeObjectUrlsOf = (data: unknown) => objectUrlsToRevoke(data).forEach((url) => {
    try { URL.revokeObjectURL(url); } catch (e) { /* non-fatal: nothing on screen holds these bytes */ }
  });

  // Hand the viewer a payload and let its own boot take over. The `data-tb-shell` marker comes off
  // first: it is what told the viewer bundle not to auto-boot into an empty document, and leaving it
  // set would suppress the boot on any later re-render too.
  const hydrate = (sessions: SessionPayload[], generatedAt: string) => {
    // Give the outgoing report's video and attachment bytes back before the payload that owns them
    // is dropped.
    revokeObjectUrlsOf(window.__TB_RUN_DATA__);
    window.__TB_RUN_DATA__ = { generatedAt, sessions };
    if (collapseBtn) collapseBtn.hidden = false; // with nothing loaded there is nothing to hide
    setCollapsed(true);
    document.documentElement.removeAttribute('data-tb-shell');
    app.innerHTML = ''; // a second local file replaces the first report rather than appending to it
    showReport();
    const boot = window.__TB_BOOT_REPORT__;
    if (boot) boot();
  };

  const renderBytes = async (archives: Array<{ bytes: Uint8Array | null; label: string }>, shareable: boolean, run: LoadRun) => {
    const id = run.id;
    spinner('Reading sessions…');
    // Each archive's payload owns object URLs the moment it is built, so a build that never reaches
    // the screen — the reader started another load, or a later archive threw — has to hand them back
    // itself. `hydrate` only sweeps the payload it REPLACES, which is the previous report, not this
    // abandoned one.
    const built = [] as Array<{ generatedAt: string; sessions: unknown[]; zipBytes: number }>;
    let shown = false;
    try {
      const zip = window.TbZipReport;
      // Inside the try so the finally below still re-enables Render: a shell built without its ZIP
      // pipeline is broken, but leaving the button dead forever hides that it was ever pressed.
      if (!zip) throw new Error('This viewer is missing its ZIP pipeline — rebuild the shell.');
      // Several archives (one per device) become ONE payload: their sessions concatenate in the
      // order the URLs were given, and the viewer's own multi-session surfaces (the run index, the
      // device matrix, the Trail view) light up exactly as they would for a single multi-session
      // archive.
      const many = archives.length > 1;
      for (let a = 0; a < archives.length; a++) {
        const stage = many ? ` (archive ${a + 1} of ${archives.length})` : '';
        try {
          built.push(await zip.buildSessionInputsFromZipBytes(archives[a].bytes as Uint8Array, {
            render: REPORT_DERIVE,
            onStage: (name) => { if (id === runId) spinner(`${name}${stage}`); },
          }));
        } catch (e) {
          throw archiveFailure(e, archives[a].label || `archive ${a + 1}`, many);
        }
        // Decoded, so the raw bytes can go before the next archive is read: several hundred-MB
        // archives held as both raw bytes and built payloads is how this runs a tab out of memory.
        archives[a].bytes = null;
        if (id !== runId) return;
      }
      const combined = combineArchives(built);
      const payloads = toSessionPayloads({ generatedAt: combined.generatedAt, sessions: combined.sessions as never });
      if (stats) stats.textContent = describeArchive(payloads, combined.totalBytes);
      // Set BEFORE the payload reaches the viewer: hydrate installs it as __TB_RUN_DATA__ and then
      // boots the viewer over it, so a boot that throws would otherwise leave `shown` false and send
      // the finally below to revoke object URLs the report on screen still points at.
      shown = true;
      hydrate(payloads, combined.generatedAt);
      if (shareable) {
        setShare(true);
      } else {
        // A local file has no address: drop any stale ?zip so the URL can't misrepresent what is on
        // screen, and leave Share disabled.
        stripArchiveAddress();
        setShare(false);
      }
    } catch (e) {
      if (id === runId) failure(errorDetail(e));
    } finally {
      if (!shown) built.forEach(revokeObjectUrlsOf);
      endLoad(run);
    }
  };

  const stripArchiveAddress = () => {
    const next = addressWithoutArchive(String(location.href || ''));
    if (!next) return;
    try { history.replaceState(null, '', next); } catch (e) { /* non-fatal: the address bar is cosmetic here */ }
  };

  // Fetch the URL sources and read the file sources, then render the whole list as one report. The
  // fetches run together, so the wait for a multi-device permalink is the slowest archive rather
  // than their sum.
  const loadSources = async (list: ArchiveSource[]) => {
    if (!list.length) return;
    const run = beginLoad(list);
    spinner(loadingMessage(list));
    let bytes: Uint8Array[];
    try {
      // In list order, so the lanes of the rendered report read the way the list does. Each source
      // names itself in its own failure: with several lined up, "Failed to fetch" alone doesn't say
      // which row to take back out.
      bytes = await Promise.all(list.map(async (source) => {
        if (!isUrlSource(source)) {
          try { return new Uint8Array(await source.file.arrayBuffer()); } catch (e) {
            throw new Error(readFailureMessage(source.name, e));
          }
        }
        // The CORS hint belongs to a fetch that never produced a response. A 4xx DID produce one, so
        // the cross-origin read plainly worked and the hint would send the reader after the wrong
        // thing.
        const res = await fetch(source.url, { signal: run.abort.signal }).catch((e) => { throw new Error(fetchFailureMessage(source.url, e)); });
        if (!res.ok) throw new Error(`${res.status} ${res.statusText} — could not download ${source.url}`);
        try { return new Uint8Array(await res.arrayBuffer()); } catch (e) { throw new Error(readFailureMessage(source.url, e)); }
      }));
    } catch (e) {
      // An abandoned load's own abort surfaces here as a failure; it is not one the reader asked
      // about, and the run-id check is what keeps it off the screen.
      if (run.id !== runId) return;
      // One source failing settles the whole load, so the siblings still on the wire are transfers
      // nobody will read — they are stopped here rather than left to fill buffers to completion.
      run.abort.abort();
      endLoad(run);
      failure(errorDetail(e));
      return;
    }
    if (run.id !== runId) return;
    await renderBytes(bytes.map((b, i) => ({ bytes: b, label: sourceLabel(list[i]) })), sourcesShareable(list), run);
  };

  // The list the reader is assembling, and the chrome that reflects it.
  let sources: ArchiveSource[] = [];
  // Everything a load in flight needs to be abandoned, restored from, or asked about, in ONE record
  // so the answers cannot drift: the id every await checks, the controller that stops its transfers,
  // the header text it covered up, and which archives it is actually loading. `load === null` IS
  // "nothing in flight" — there is no second flag to keep in step with it.
  let load: LoadRun | null = null;
  const announce = (said: string) => {
    // Written on a turn of its own. A remove also moves focus, and a polite announcement still
    // pending when focus lands elsewhere is dropped by most screen readers — which would silence
    // the region on the very path it exists for.
    if (live && said) setTimeout(() => { live.textContent = said; }, 0);
  };
  const syncRenderBtn = () => {
    if (!renderBtn) return;
    const state = renderButtonState(sources, urlInput ? urlInput.value : '', load !== null);
    renderBtn.disabled = state.disabled;
    renderBtn.textContent = state.label;
  };
  const beginLoad = (list: ArchiveSource[]): LoadRun => {
    // Whatever was downloading is no longer wanted. The run id alone only makes the pipeline DROP
    // the result — the transfer itself would run to completion, so a reader who presses Render twice
    // over a list of large archives pays for both.
    const run: LoadRun = {
      id: ++runId,
      abort: new AbortController(),
      // The chip describes the report on screen, and this load is about to describe a different one.
      // Kept so a cancelled load can put it back. Taken from the load already running when there is
      // one: that load blanked the chip on its way past, so reading the DOM here would save the
      // blank and "restore" it over the report still up.
      stats: load ? load.stats : (stats ? stats.textContent || '' : ''),
      keys: new Set(list.map(sourceKey)),
    };
    if (load) load.abort.abort();
    load = run;
    if (stats) stats.textContent = '';
    syncRenderBtn();
    return run;
  };
  // Only the CURRENT load hands the chrome back: a superseded one settling later must not re-enable
  // Render over the load that replaced it.
  const endLoad = (run: LoadRun) => { if (load === run) { load = null; syncRenderBtn(); } };
  // Abandon what is loading. Bumping the run id is what every await in the pipeline checks, so the
  // work in flight drops its result (and hands its object URLs back) instead of hydrating; the abort
  // is what stops the transfers still on the wire from being paid for at all.
  const cancelLoad = () => {
    const run = load;
    if (!run) return;
    runId++;
    run.abort.abort();
    load = null;
    syncRenderBtn();
    // Back to whatever the cancelled load covered up: the report already on screen, or the idle copy.
    if (window.__TB_RUN_DATA__) showReport(); else showPanel(idleHtml);
    if (stats) stats.textContent = run.stats;
  };
  const setSources = (next: ArchiveSource[], focusAt = -1) => {
    // A row is written far from the control that added it, so the change is announced rather than
    // left for the reader to discover.
    const said = listAnnouncement(sources.length, next.length);
    sources = next;
    if (list) {
      list.innerHTML = sourceListHtml(sources);
      list.hidden = !sources.length;
      const removes = list.querySelectorAll<HTMLElement>('[data-tb-remove]');
      removes.forEach((btn) => {
        btn.onclick = () => {
          const at = Number(btn.dataset.tbRemove);
          const removed = sources[at];
          // A load holding this archive would go on to render the row just taken out of it, so it is
          // dropped — the reader can press Render again over what's left, which is cheaper than
          // silently showing them what they removed. A load that never held it is left alone: a row
          // added beside a running download and taken straight back out is not a reason to abandon
          // the download.
          if (removed && load && load.keys.has(sourceKey(removed))) cancelLoad();
          setSources(removeSourceAt(sources, at), at);
        };
      });
      // Removing a chip destroys the button that was focused, which drops focus to the document and
      // strands a keyboard reader at the top of the page. Hand it to the row that took its place, or
      // back to the field when the last row is gone.
      const focusTo = focusIndexAfterRemoval(focusAt, removes.length);
      if (focusTo !== null) {
        const target = focusTo >= 0 ? removes[focusTo] : urlInput;
        if (target) target.focus({ preventScroll: true });
      }
    }
    announce(said);
    syncRenderBtn();
  };

  const renderSources = () => {
    const plan = renderPlan(String(location.pathname || ''), sources, urlInput ? urlInput.value : '');
    if (plan.kind === 'none') return;
    if (plan.kind === 'navigate') { location.assign(plan.href); return; }
    // Cleared before the list is written back, so the button's count never includes text that has
    // already become a row.
    if (urlInput) urlInput.value = '';
    setSources(plan.sources);
    loadSources(plan.sources);
  };

  // Adding without rendering: the point of a list is lining several up first. A paste carrying
  // several URLs adds them all. The field is cleared either way — when everything typed is already
  // lined up, the state the reader asked for is what they are looking at, and leaving the text there
  // reads as a press that did nothing. That case still answers, rather than going silent: nothing
  // moves on screen, so without a word the press is indistinguishable from a broken control.
  const addTyped = () => {
    if (!urlInput) return;
    const typed = urlInput.value;
    const next = addArchiveUrls(sources, typed);
    urlInput.value = '';
    if (next.length !== sources.length) { setSources(next); return; }
    if (splitArchiveUrls(typed).length) announce(listAnnouncement(sources.length, sources.length, true));
    syncRenderBtn();
  };

  // Files arrive already chosen, so they are added AND rendered — dropping an archive is a request
  // to see it, and a second drop adds to what is already lined up rather than replacing it. Which
  // means a drop renders the WHOLE list, downloading any URL rows alongside it.
  const addFiles = (files: File[]) => {
    if (!files.length) return;
    const next = appendSources(sources, files.map((file) => ({ name: file.name, file })));
    setSources(next);
    loadSources(next);
  };

  if (addBtn) addBtn.onclick = addTyped;
  if (renderBtn) renderBtn.onclick = renderSources;
  if (urlInput) {
    urlInput.onkeydown = (e: KeyboardEvent) => { if (e.key === 'Enter') addTyped(); };
    // Typing a URL is enough to render: Render takes the field along with the list, so gating it on
    // the list alone would leave it dead through the whole "paste a URL, press Render" path.
    urlInput.oninput = syncRenderBtn;
  }
  // The picker is the keyboard-reachable twin of drag-and-drop, which no keyboard or assistive-tech
  // user can perform. Both take SEVERAL archives: combining runs is the reason to reach for either.
  if (pickBtn && fileInput) {
    pickBtn.onclick = () => fileInput.click();
    fileInput.onchange = () => {
      addFiles(Array.from(fileInput.files || []));
      fileInput.value = ''; // so re-picking the same file still fires a change
    };
  }
  setSources([]);
  setShare(false);

  // Drag a .zip anywhere on the page. The depth counter absorbs the dragenter/dragleave pairs fired
  // by every child element the pointer crosses, which would otherwise flicker the overlay.
  // Drags that START inside the page are not archive drops: dragging a rendered screenshot (the
  // browser gives every <img> a native drag) must not flash the overlay — or worse, hand the image
  // bytes to the loader, which would replace the whole loaded report with a zip-parse error.
  let dragDepth = 0;
  let internalDrag = false;
  window.addEventListener('dragstart', () => { internalDrag = true; });
  window.addEventListener('dragend', () => { internalDrag = false; });
  const hideOverlay = () => { dragDepth = 0; if (overlay) overlay.classList.remove('show'); };
  window.addEventListener('dragenter', (e) => { e.preventDefault(); if (internalDrag) return; dragDepth++; if (overlay) overlay.classList.add('show'); });
  window.addEventListener('dragover', (e) => { e.preventDefault(); });
  window.addEventListener('dragleave', (e) => { e.preventDefault(); if (--dragDepth <= 0) hideOverlay(); });
  window.addEventListener('drop', (e) => {
    e.preventDefault();
    hideOverlay();
    if (internalDrag) { internalDrag = false; return; }
    addFiles(Array.from((e.dataTransfer && e.dataTransfer.files) || []));
  });

  // Boot: a ?zip= address renders immediately (this is the permalink path, and the one whose URL
  // already carries any tab/step deep link for the viewer to apply). A repeated `zip` param loads
  // every archive into one report.
  const initial = zipParamsFrom(String(location.href || ''));
  if (initial.length) {
    // Through the same append the reader's own paths use, so a link that repeats one archive URL
    // becomes one row rather than downloading and rendering that run twice.
    const staged = appendSources([], initial.map((url) => ({ url })));
    setSources(staged);
    loadSources(staged);
  }
}
