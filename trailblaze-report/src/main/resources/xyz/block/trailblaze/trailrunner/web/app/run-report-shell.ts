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
//   2. Paste a URL and press Render (or Enter) — navigates to form 1 rather than rendering in place,
//      so the address bar is always the permalink and the viewer is never re-entered mid-session.
//   3. Drop a .zip on the page, or pick one — read locally, rendered in place. Nothing uploads, and
//      there is no URL to share, so Share stays disabled for these.
//
// Rendering happens in place (window.__TB_RUN_DATA__ + the viewer's own boot), NOT into a child
// frame: an `about:srcdoc` frame inherits this document's origin anyway, so a frame bought no
// isolation while costing the viewer its URL — no deep links, no Copy link. Real isolation would
// mean a sandboxed frame with an opaque origin, which is a deliberate trade, not a default.

import { extractLlmLogs, extractTrace, originalYamlFromLogs, toSessionPayloads } from './run-report-extract';

const ZIP_PARAM = 'zip';

// The collaborator zip-report-core's resolveRenderer expects; field names match what it looks for.
const REPORT_DERIVE = { extractTrace, extractLlmLogs, originalYamlFromLogs };

type LoadSource = { url: string } | { fileName: string };

// The permalink for an archive URL. Percent-encoded as a single opaque value so an archive URL that
// carries its own query (a signed artifact URL's `jwt`, an S3 `key`) survives intact instead of its
// `&`s splitting into params of this page.
export function zipPermalink(pathname: string, archiveUrl: string): string {
  return `${pathname}?${ZIP_PARAM}=${encodeURIComponent(String(archiveUrl || '').trim())}`;
}

// The archive URL a shell address points at, or '' for none. Tolerates a malformed href (returns
// none) so a hand-edited address can't stop the loader chrome from rendering.
export function zipParamFrom(href: string): string {
  try { return new URL(String(href)).searchParams.get(ZIP_PARAM) || ''; } catch (e) { return ''; }
}

// The viewer's own route keys, which it reads off location.search (see readRoute in
// run-report-viewer). The shell owns `zip`; these belong to whatever report is loaded.
const VIEWER_ROUTE_PARAMS = ['view', 'runs', 'run', 'tab', 'step', 'streams', 'llm', 'stream', 'sort', 'filter'];

// The address to leave behind when a report is loaded from a LOCAL file: relative, with the archive
// param and the viewer's route keys dropped. Content read off the user's disk has no address at all,
// so keeping either would let the URL describe something it can't reproduce — a stale `tab`/`step`
// would also be applied to the newly-loaded archive. Returns '' when there is nothing to rewrite, so
// the caller can skip the history write entirely. A malformed href yields '' for the same reason.
export function addressWithoutArchive(href: string): string {
  try {
    const url = new URL(String(href));
    const dropped = [ZIP_PARAM, ...VIEWER_ROUTE_PARAMS].filter((key) => url.searchParams.has(key));
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
// so name both possibilities rather than guessing.
export function fetchFailureMessage(error: unknown): string {
  const detail = (error && (error as Error).message) || String(error);
  return `${detail}\n\nIf the host is up, it may not send Access-Control-Allow-Origin for this page.`;
}

export function RUN_REPORT_SHELL(): void {
  const byId = (id: string) => document.getElementById(id);
  const shell = byId('tb-shell');
  const urlInput = byId('tb-shell-url') as HTMLInputElement | null;
  const renderBtn = byId('tb-shell-render') as HTMLButtonElement | null;
  const shareBtn = byId('tb-shell-share') as HTMLButtonElement | null;
  const pickBtn = byId('tb-shell-pick') as HTMLButtonElement | null;
  const fileInput = byId('tb-shell-file') as HTMLInputElement | null;
  const stats = byId('tb-shell-stats');
  const panel = byId('tb-shell-panel');
  const overlay = byId('tb-shell-overlay');
  const app = byId('app');
  if (!shell || !panel || !app) return;

  const idleHtml = panel.innerHTML; // the "how to load a report" copy, restored on error-free reset
  let runId = 0;

  // Full HTML escape: these strings carry archive-derived content (session ids, error text), so
  // every special is escaped before it reaches innerHTML.
  const esc = (s: unknown) => String(s == null ? '' : s)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');

  const showPanel = (html: string) => { panel.innerHTML = html; panel.style.display = 'flex'; app.style.display = 'none'; };
  // Clear the inline display rather than setting one: the report stylesheet makes #app a flex
  // column, and an inline `display: block` outranks it, collapsing the viewer's own layout.
  const showReport = () => { panel.style.display = 'none'; app.style.display = ''; };
  const spinner = (msg: string) => showPanel(`<div class="tb-shell-spinner"></div><div class="tb-shell-sub">${esc(msg)}</div>`);
  const failure = (msg: string) => showPanel(`<div class="tb-shell-err">${esc(msg)}</div><div class="tb-shell-sub">${idleHtml}</div>`);

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

  // Hand the viewer a payload and let its own boot take over. The `data-tb-shell` marker comes off
  // first: it is what told the viewer bundle not to auto-boot into an empty document, and leaving it
  // set would suppress the boot on any later re-render too.
  const hydrate = (sessions: SessionPayload[], generatedAt: string) => {
    window.__TB_RUN_DATA__ = { generatedAt, sessions };
    document.documentElement.removeAttribute('data-tb-shell');
    app.innerHTML = ''; // a second local file replaces the first report rather than appending to it
    showReport();
    const boot = window.__TB_BOOT_REPORT__;
    if (boot) boot();
  };

  const renderBytes = async (bytes: Uint8Array, source: LoadSource, id: number) => {
    spinner('Reading sessions…');
    try {
      const zip = window.TbZipReport;
      // Inside the try so the finally below still re-enables Render: a shell built without its ZIP
      // pipeline is broken, but leaving the button dead forever hides that it was ever pressed.
      if (!zip) throw new Error('This viewer is missing its ZIP pipeline — rebuild the shell.');
      const built = await zip.buildSessionInputsFromZipBytes(bytes, {
        render: REPORT_DERIVE,
        onStage: (stage) => { if (id === runId) spinner(stage); },
      });
      if (id !== runId) return;
      const payloads = toSessionPayloads({ generatedAt: built.generatedAt, sessions: built.sessions });
      if (stats) stats.textContent = describeArchive(payloads, built.zipBytes);
      hydrate(payloads, built.generatedAt);
      if ('url' in source) {
        if (urlInput) urlInput.value = source.url;
        setShare(true);
      } else {
        // A local file has no address: drop any stale ?zip so the URL can't misrepresent what is on
        // screen, and leave Share disabled.
        stripArchiveAddress();
        if (urlInput) { urlInput.value = ''; urlInput.placeholder = `${source.fileName} — loaded from file`; }
        setShare(false);
      }
    } catch (e) {
      if (id === runId) failure((e && (e as Error).message) || String(e));
    } finally {
      if (id === runId && renderBtn) renderBtn.disabled = false;
    }
  };

  const stripArchiveAddress = () => {
    const next = addressWithoutArchive(String(location.href || ''));
    if (!next) return;
    try { history.replaceState(null, '', next); } catch (e) { /* non-fatal: the address bar is cosmetic here */ }
  };

  const loadFromUrl = async (raw: string) => {
    const target = String(raw || '').trim();
    if (!target) return;
    const id = ++runId;
    if (renderBtn) renderBtn.disabled = true;
    if (stats) stats.textContent = '';
    spinner('Downloading archive…');
    let bytes: Uint8Array;
    try {
      const res = await fetch(target);
      if (!res.ok) throw new Error(`${res.status} ${res.statusText} — could not download the archive.`);
      bytes = new Uint8Array(await res.arrayBuffer());
    } catch (e) {
      if (id !== runId) return;
      if (renderBtn) renderBtn.disabled = false;
      failure(fetchFailureMessage(e));
      return;
    }
    await renderBytes(bytes, { url: target }, id);
  };

  const loadFromFile = async (file: File) => {
    const id = ++runId;
    if (stats) stats.textContent = '';
    spinner(`Reading ${file.name}…`);
    try {
      const bytes = new Uint8Array(await file.arrayBuffer());
      await renderBytes(bytes, { fileName: file.name }, id);
    } catch (e) {
      if (id === runId) failure((e && (e as Error).message) || String(e));
    }
  };

  // Rendering a pasted URL navigates instead of loading in place: the address bar stays the exact
  // permalink, and the viewer is only ever booted once per document.
  const navigateToUrl = (raw: string) => {
    const target = String(raw || '').trim();
    if (!target) return;
    location.assign(zipPermalink(String(location.pathname || ''), target));
  };

  if (renderBtn && urlInput) renderBtn.onclick = () => navigateToUrl(urlInput.value);
  if (urlInput) urlInput.onkeydown = (e: KeyboardEvent) => { if (e.key === 'Enter') navigateToUrl(urlInput.value); };
  // The picker is the keyboard-reachable twin of drag-and-drop, which no keyboard or assistive-tech
  // user can perform.
  if (pickBtn && fileInput) {
    pickBtn.onclick = () => fileInput.click();
    fileInput.onchange = () => { const f = fileInput.files && fileInput.files[0]; if (f) loadFromFile(f); };
  }
  setShare(false);

  // Drag a .zip anywhere on the page. The depth counter absorbs the dragenter/dragleave pairs fired
  // by every child element the pointer crosses, which would otherwise flicker the overlay.
  let dragDepth = 0;
  const hideOverlay = () => { dragDepth = 0; if (overlay) overlay.classList.remove('show'); };
  window.addEventListener('dragenter', (e) => { e.preventDefault(); dragDepth++; if (overlay) overlay.classList.add('show'); });
  window.addEventListener('dragover', (e) => { e.preventDefault(); });
  window.addEventListener('dragleave', (e) => { e.preventDefault(); if (--dragDepth <= 0) hideOverlay(); });
  window.addEventListener('drop', (e) => {
    e.preventDefault();
    hideOverlay();
    const file = e.dataTransfer && e.dataTransfer.files && e.dataTransfer.files[0];
    if (file) loadFromFile(file);
  });

  // Boot: a ?zip= address renders immediately (this is the permalink path, and the one whose URL
  // already carries any tab/step deep link for the viewer to apply).
  const initial = zipParamFrom(String(location.href || ''));
  if (initial) {
    if (urlInput) urlInput.value = initial;
    loadFromUrl(initial);
  }
}
