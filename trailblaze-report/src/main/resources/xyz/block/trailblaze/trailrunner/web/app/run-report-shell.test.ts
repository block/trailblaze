// The viewer shell: the data-less edition of the report that loads a session archive in the browser.
// Covers the artifact's observable properties, the permalink round-trip, and the parity claim that
// makes the shell safe to ship — that a shell-loaded report hands the viewer the same session payload
// an exported report embeds.
import { describe, expect, test } from "bun:test";
import { toSessionPayloads } from "./run-report-extract";
import { buildMultiReportHtml } from "./run-report-html";
import { buildViewerShellHtml } from "./run-report-shell-html";
import { VIEWER_ROUTE_KEYS } from "./run-report-route";
import { addArchiveUrls, addressWithoutArchive, appendSources, archiveFailure, combineArchives, objectUrlsToRevoke, describeArchive, fetchFailureMessage, focusIndexAfterRemoval, listAnnouncement, loadingMessage, readFailureMessage, removeSourceAt, renderButtonState, renderPlan, sourceKey, sourceListHtml, sourcesPermalink, sourcesShareable, splitArchiveUrls, zipParamsFrom, zipPermalink } from "./run-report-shell";
import type { ArchiveSource } from "./run-report-shell";

describe("buildViewerShellHtml", () => {
  const shell = buildViewerShellHtml();

  test("is a complete standalone document with no report data in it", () => {
    expect(shell.startsWith("<!doctype html>")).toBe(true);
    // The marker the viewer bundle reads to skip its auto-boot. Without it the shell would render an
    // empty report over its own loader chrome.
    expect(shell).toContain("data-tb-shell");
    // No payload chunks: this artifact carries a loader, not a run.
    expect(shell).not.toContain('id="tb-index"');
    expect(shell).not.toContain('id="tb-session-0"');
  });

  test("embeds the whole client-side pipeline, so loading a report needs no further requests", () => {
    // The zip reader + assembly (window.TbZipReport), reached by the loader.
    expect(shell).toContain("buildSessionInputsFromZipBytes");
    // The viewer bundle's published collaborators.
    expect(shell).toContain("__TB_BOOT_REPORT__");
    // No external scripts or styles: everything is inline.
    expect(shell).not.toMatch(/<script[^>]+src=/);
    expect(shell).not.toMatch(/<link[^>]+stylesheet/);
  });

  test("offers a keyboard-reachable way to open local archives, not only drag-and-drop", () => {
    expect(shell).toContain('type="file"');
    expect(shell).toContain('accept=".zip,application/zip"');
    expect(shell).toContain("tb-shell-pick");
    // Drag-and-drop stays, as the discoverable path.
    expect(shell).toContain("tb-shell-overlay");
    // Several at once: the picker is the only path a keyboard user has to a multi-archive report,
    // and without `multiple` it can open exactly one file no matter what the loader does with them.
    expect(shell).toMatch(/id="tb-shell-file"[^>]* multiple/);
  });

  test("archives can be lined up before anything renders", () => {
    // Assembling a multi-archive report by editing one whitespace-separated field only ever worked
    // for URLs — a local file has no text form — so the bar carries an Add affordance and a row of
    // what is lined up so far.
    expect(shell).toContain('id="tb-shell-add"');
    expect(shell).toContain('id="tb-shell-list"');
    // The row ships empty and hidden, and its visibility is attribute-driven, so the stylesheet's
    // `display: flex` must not outrank [hidden].
    expect(shell).toMatch(/id="tb-shell-list"[^>]* hidden/);
    expect(shell).toMatch(/#tb-shell-list\[hidden\] \{ display: none; \}/);
    expect(shell).toMatch(/id="tb-shell-list"[^>]* role="list"/);
    // Announced as it changes — adding and removing rows is the whole interaction, and it happens
    // far from the control that caused it — but through a region of its own, never by making the
    // list live: a live list re-reads every surviving row when one is removed, and it carries
    // [hidden] while empty, which would keep the first archive added from being announced at all.
    expect(shell).not.toMatch(/id="tb-shell-list"[^>]* aria-live=/);
    expect(shell).toMatch(/id="tb-shell-live"[^>]* role="status"/);
    expect(shell).toMatch(/id="tb-shell-live"[^>]* aria-live="polite"/);
    expect(shell).not.toMatch(/id="tb-shell-live"[^>]* hidden/);
    // Off-screen, not display: none — the latter is not announced either.
    expect(shell).toMatch(/\.tb-shell-sr \{[^}]*clip-path: inset\(50%\)/);
    expect(shell).not.toMatch(/\.tb-shell-sr \{[^}]*display: none/);
    // And it scrolls once it outgrows a third of the window. The page can't scroll (the report's
    // stylesheet pins html/body to the viewport with overflow: hidden), so a long list would
    // otherwise clip the very rows carrying the buttons that take archives back out.
    expect(shell).toContain("max-height: 33dvh; overflow-y: auto;");
  });

  test("labels the URL field for assistive tech", () => {
    expect(shell).toMatch(/id="tb-shell-url"[^>]*aria-label=/);
  });

  test("no embedded script can close its own element early", () => {
    // Every script element must be accounted for by exactly one closer. A literal `</script>` inside
    // any embedded bundle — an ordinary future code comment is enough — would truncate the document
    // at that byte, and the publish guards could not see it: they match markers near the top of the
    // page, which a truncated document still has. inertScriptBody is what keeps this equal.
    // Case-insensitive to match what an HTML parser does — and what inertScriptBody's own /gi replace
    // does — so a `<SCRIPT>` opener or `</SCRIPT>` closer can't slip past the count.
    const openers = shell.match(/<script(?:\s[^>]*)?>/gi) || [];
    const closers = shell.match(/<\/script>/gi) || [];
    expect(closers.length).toBe(openers.length);
    // The escaped form is what an embedded closer must appear as.
    expect(shell).not.toMatch(/[^\\]<\/script(?!>)/i);
  });

  test("resizes the report to fit under the bar, keyed on a hook that survives boot", () => {
    // The report stylesheet gives #app height:100dvh — correct in an exported document, where it
    // owns the viewport. Under the shell bar that overflows by the bar's height, pushing the run's
    // footer (target/platform/duration) below the fold where `overflow: hidden` makes it
    // unreachable. The shell overrides that sizing, and the override MUST key on #tb-shell rather
    // than the data-tb-shell marker: the loader clears that marker when it boots the viewer, which
    // is the exact moment #app becomes visible, so a marker-gated rule would stop applying right
    // when it is needed — re-gate it on the marker and this regex stops matching.
    const override = /body:has\(> #tb-shell\)\s*>\s*#app\s*\{[^}]*\}/.exec(shell);
    expect(override).not.toBeNull();
    expect(override![0]).toContain("flex");
  });

  test("the loader bar can get out of the way once a report is on screen", () => {
    // The bar earns its height while there is nothing loaded; over a rendered report it is spent
    // chrome, so the loader collapses it to a slim handle (and the loader script reopens it on a
    // failed load, where the recovery lives on the bar).
    expect(shell).toContain('id="tb-shell-handle"');
    expect(shell).toMatch(/#tb-shell\.tb-shell-min #tb-shell-bar \{ display: none; \}/);
    // The Hide affordance ships hidden: with nothing loaded there is nothing to hide.
    expect(shell).toMatch(/id="tb-shell-collapse"[^>]* hidden/);
    // The handle's visibility is class-driven only — an author display beats [hidden], so the
    // element must not carry the attribute.
    expect(shell).not.toMatch(/id="tb-shell-handle"[^>]* hidden/);
  });

  test("says up front that loading by URL depends on the archive host's CORS header", () => {
    // The two load paths have different requirements: a dropped file needs no network, while `?zip=`
    // is a cross-origin fetch that only works when the host opts in. The loader already names the
    // header when a fetch fails; a hosted viewer's visitors need it BEFORE they try, because the
    // fix is on a host they may not control. Assert it's in the idle panel, not just the error path.
    const panel = /<div id="tb-shell-panel">([\s\S]*?)\n  <\/div>/.exec(shell);
    expect(panel).not.toBeNull();
    expect(panel![1]).toContain("Access-Control-Allow-Origin");
  });
});

describe("archive permalinks", () => {
  test("round-trips an archive URL that carries its own query string", () => {
    // A signed artifact URL's own `&`s must not split into params of the viewer page.
    const archive = "https://host/results/C1/android-phone/latest.zip?jwt=abc.def&key=a/b c.zip";
    const href = `https://viewer.example${zipPermalink("/sites/trailblaze-report/", [archive])}`;
    expect(zipParamsFrom(href)).toEqual([archive]);
  });

  test("trims incidental whitespace from a pasted URL", () => {
    const href = `https://viewer.example${zipPermalink("/v/", ["  https://host/a.zip\n"])}`;
    expect(zipParamsFrom(href)).toEqual(["https://host/a.zip"]);
  });

  test("a deep link rides alongside the archive param", () => {
    // How `?zip=…&tab=lightbox` reaches the viewer: the shell owns `zip`, the viewer owns the route
    // keys, and neither strips the other's.
    const href = `https://viewer.example${zipPermalink("/v/", ["https://host/a.zip"])}&tab=lightbox&step=4`;
    expect(zipParamsFrom(href)).toEqual(["https://host/a.zip"]);
    const params = new URL(href).searchParams;
    expect(params.get("tab")).toBe("lightbox");
    expect(params.get("step")).toBe("4");
  });

  test("reports no archive for an address that has none, and for a malformed one", () => {
    expect(zipParamsFrom("https://viewer.example/v/")).toEqual([]);
    expect(zipParamsFrom("not a url")).toEqual([]);
  });

  test("several archives round-trip as a repeated zip param, order preserved", () => {
    // The multi-device permalink: the same trail's runs, one archive per device, one report.
    const archives = [
      "https://host/results/C1/android-phone/run.zip?jwt=a.b&key=x/y.zip",
      "https://host/results/C1/ios-ipad/run.zip",
    ];
    const href = `https://viewer.example${zipPermalink("/v/", archives)}`;
    expect(zipParamsFrom(href)).toEqual(archives);
  });

  test("splits a pasted list of URLs on whitespace and stray commas", () => {
    expect(splitArchiveUrls(" https://h/a.zip\nhttps://h/b.zip,  https://h/c.zip ")).toEqual([
      "https://h/a.zip", "https://h/b.zip", "https://h/c.zip",
    ]);
    expect(splitArchiveUrls("")).toEqual([]);
  });
});

describe("the list of archives lined up to render", () => {
  // One shared reader, so two fixtures of the same archive compare equal rather than differing by
  // closure identity.
  const bytes = async () => new ArrayBuffer(0);
  const file = (name: string, size = 1, lastModified = 1): ArchiveSource => ({ name, file: { size, lastModified, arrayBuffer: bytes } });

  test("a paste of several URLs adds them all, in the order they were pasted", () => {
    // The list IS the multi-archive interface, so one paste out of a build log has to land whole
    // rather than only its first line.
    expect(addArchiveUrls([], "https://h/a.zip https://h/b.zip"))
      .toEqual([{ url: "https://h/a.zip" }, { url: "https://h/b.zip" }]);
    // Added to what is already lined up, not in place of it.
    expect(addArchiveUrls([{ url: "https://h/a.zip" }], "https://h/b.zip"))
      .toEqual([{ url: "https://h/a.zip" }, { url: "https://h/b.zip" }]);
    // Nothing pasted is not an entry.
    expect(addArchiveUrls([{ url: "https://h/a.zip" }], "   ")).toEqual([{ url: "https://h/a.zip" }]);
  });

  test("re-adding something already lined up doesn't put it in twice", () => {
    // Adding the same address again is a re-paste, not a request to render that run twice — and two
    // rows carrying the same index would make removal ambiguous.
    expect(addArchiveUrls([{ url: "https://h/a.zip" }], "https://h/b.zip https://h/a.zip"))
      .toEqual([{ url: "https://h/a.zip" }, { url: "https://h/b.zip" }]);
    expect(appendSources([file("run.zip")], [file("run.zip")])).toEqual([file("run.zip")]);
  });

  test("two different archives that happen to share a name are two rows, not one", () => {
    // `session.zip` is what every archive off an artifact store is called, so the same name from two
    // folders is the ordinary multi-device case. Deduping those on the name drops a lane silently.
    const a = file("session.zip", 4096, 111);
    const b = file("session.zip", 9001, 222);
    expect(appendSources([a], [b])).toEqual([a, b]);
    expect(sourceKey(a)).not.toBe(sourceKey(b));
    // The same file re-dropped is still one row: same name, same size, same modified time.
    expect(appendSources([a], [file("session.zip", 4096, 111)])).toEqual([a]);
  });

  test("a source with no name at all is not a row", () => {
    // A chip with nothing in it offers the reader no way to tell what it is or why it is there,
    // and its only affordance would be removing something they can't identify.
    expect(appendSources([], [file("")])).toEqual([]);
    expect(appendSources([], [{ url: "" }])).toEqual([]);
    expect(appendSources([{ url: "https://h/a.zip" }], [file("")])).toEqual([{ url: "https://h/a.zip" }]);
  });

  test("files and URLs line up together, which is the combination that had no text form at all", () => {
    const mixed = appendSources(addArchiveUrls([], "https://h/a.zip"), [file("local.zip")]);
    expect(mixed).toEqual([{ url: "https://h/a.zip" }, file("local.zip")]);
  });

  test("a list of URLs is a permalink; a list holding a local file has no address at all", () => {
    const urls = ["https://h/a.zip?jwt=x&key=y", "https://h/b.zip"];
    const permalink = sourcesPermalink("/v/", urls.map((url) => ({ url })));
    expect(zipParamsFrom(`https://viewer.example${permalink}`)).toEqual(urls);
    // The bytes live on the reader's disk, so there is nothing to navigate to and Share stays off —
    // '' is what tells the loader to render in place instead.
    expect(sourcesPermalink("/v/", [{ url: "https://h/a.zip" }, file("local.zip")])).toBe("");
    expect(sourcesPermalink("/v/", [file("local.zip")])).toBe("");
    expect(sourcesPermalink("/v/", [])).toBe("");
  });

  test("every row offers its own removal, keyed on its position", () => {
    // Taking one archive back out without retyping the rest is the whole reason the list exists.
    const html = sourceListHtml([{ url: "https://h/a.zip" }, file("local.zip")]);
    expect(html).toContain('data-tb-remove="0"');
    expect(html).toContain('data-tb-remove="1"');
    expect(html).toContain("https://h/a.zip");
    expect(html).toContain("local.zip");
    // A file row reads as one: it can't be shared by link, and it forces the whole list to render in
    // place.
    expect(html).toContain("tb-shell-src-file");
    expect(html).toContain("tb-shell-src-url");
    expect(sourceListHtml([])).toBe("");
  });

  test("a remove button names its row without reading a whole artifact URL aloud", () => {
    // A button announces its own name and nothing else, so `Remove <signed artifact URL>` is several
    // hundred characters read out every time a keyboard reader passes one. The position identifies a
    // URL row and the chip's text still carries the address. A file name is short and is the ONLY
    // identity a dropped file has, so that one stays named.
    const html = sourceListHtml([{ url: "https://h/a.zip?jwt=verylongtoken" }, file("local.zip")]);
    expect(html).toContain('aria-label="Remove archive 1 of 2"');
    expect(html).toContain('aria-label="Remove local.zip"');
    expect(html).not.toContain('aria-label="Remove https://h/a.zip?jwt=verylongtoken"');
  });

  test("removing a row takes out that one and leaves the rest in order", () => {
    const rows: ArchiveSource[] = [{ url: "https://h/a.zip" }, { url: "https://h/b.zip" }, file("c.zip")];
    expect(removeSourceAt(rows, 1)).toEqual([{ url: "https://h/a.zip" }, file("c.zip")]);
    expect(removeSourceAt(rows, 0)).toEqual([{ url: "https://h/b.zip" }, file("c.zip")]);
    expect(removeSourceAt(rows, 2)).toEqual([{ url: "https://h/a.zip" }, { url: "https://h/b.zip" }]);
    // An index naming no row leaves the list alone — a filter that merely excluded a match would
    // drop nothing here too, but one keyed on the wrong thing (a label, a NaN) would truncate.
    expect(removeSourceAt(rows, 3)).toBe(rows);
    expect(removeSourceAt(rows, -1)).toBe(rows);
    expect(removeSourceAt(rows, NaN)).toBe(rows);
    expect(removeSourceAt([], 0)).toEqual([]);
  });

  test("a change to the list is announced as what changed, not as the list", () => {
    // Re-reading four surviving artifact URLs because a fifth was removed buries the one fact the
    // reader asked for.
    expect(listAnnouncement(0, 1)).toBe("Added 1 archive. 1 archive lined up.");
    expect(listAnnouncement(1, 3)).toBe("Added 2 archives. 3 archives lined up.");
    expect(listAnnouncement(3, 2)).toBe("Removed 1 archive. 2 archives lined up.");
    expect(listAnnouncement(1, 0)).toBe("Removed 1 archive. 0 archives lined up.");
    // Nothing moved, nothing said — the list is rewritten on every render, including the first.
    expect(listAnnouncement(0, 0)).toBe("");
    expect(listAnnouncement(2, 2)).toBe("");
  });

  test("pressing Add over an address already lined up still answers", () => {
    // Nothing moves on screen for a duplicate, so silence is indistinguishable from a broken
    // control — but only when the reader actually asked; an ordinary re-render stays quiet.
    expect(listAnnouncement(2, 2, true)).toBe("Already lined up. 2 archives lined up.");
    expect(listAnnouncement(1, 1, true)).toBe("Already lined up. 1 archive lined up.");
    expect(listAnnouncement(2, 2, false)).toBe("");
    // And a press that DID add something reports the addition, not the duplicate wording.
    expect(listAnnouncement(1, 2, true)).toBe("Added 1 archive. 2 archives lined up.");
  });

  test("focus lands on the row that took the removed one's place", () => {
    // Removing a chip destroys the button that had focus, dropping it to the document and stranding
    // a keyboard reader at the top of the page.
    expect(focusIndexAfterRemoval(0, 2)).toBe(0);
    expect(focusIndexAfterRemoval(1, 3)).toBe(1);
    // The removed row was last, so there is no row in its place: fall back to the new last one.
    expect(focusIndexAfterRemoval(3, 3)).toBe(2);
    // -1 is the field: the list is empty and there is no row left to hold focus.
    expect(focusIndexAfterRemoval(0, 0)).toBe(-1);
    // null is "not a removal" — an ordinary re-render must leave focus where the reader put it.
    expect(focusIndexAfterRemoval(-1, 3)).toBeNull();
    expect(focusIndexAfterRemoval(NaN, 3)).toBeNull();
  });

  test("a hostile archive name can't inject markup into the list", () => {
    // These strings are reader-supplied — a pasted URL, a file name off disk — and go through
    // innerHTML.
    const html = sourceListHtml([file('a"><img src=x onerror=alert(1)>.zip')]);
    expect(html).not.toContain("<img");
    expect(html).toContain("&lt;img");
    expect(html).toContain("&quot;");
  });

  test("the Render button says how many archives it will open", () => {
    // "Render" over a list of three is ambiguous about whether it opens the list or just the field.
    expect(renderButtonState([{ url: "https://h/a.zip" }, { url: "https://h/b.zip" }], "").label).toBe("Render 2 archives");
    expect(renderButtonState([{ url: "https://h/a.zip" }], "").label).toBe("Render");
    expect(renderButtonState([], "").label).toBe("Render");
  });

  test("a URL typed but not yet added is still something to render", () => {
    // The documented primary path is "paste an archive URL, press Render". Render takes the field
    // along with the list, so a button gated on the list alone is dead through exactly that path.
    expect(renderButtonState([], "https://h/a.zip").disabled).toBe(false);
    expect(renderButtonState([], "   ").disabled).toBe(true);
    expect(renderButtonState([], "").disabled).toBe(true);
    // And it counts what will actually open, so typing a second URL beside one row says so.
    expect(renderButtonState([{ url: "https://h/a.zip" }], "https://h/b.zip").label).toBe("Render 2 archives");
    // Re-typing what is already lined up adds nothing, so the count doesn't move.
    expect(renderButtonState([{ url: "https://h/a.zip" }], "https://h/a.zip").label).toBe("Render");
  });

  test("a load in flight refuses the press, and the list changing under it can't hand the button back", () => {
    // Two different reasons to refuse, one answer: taking a row out mid-load re-renders the chips,
    // which recomputes this — and a state that only knew about the list would re-enable the button
    // over a load that is still running.
    expect(renderButtonState([{ url: "https://h/a.zip" }], "", true).disabled).toBe(true);
    expect(renderButtonState([{ url: "https://h/a.zip" }, { url: "https://h/b.zip" }], "", true).disabled).toBe(true);
    expect(renderButtonState([{ url: "https://h/a.zip" }], "", false).disabled).toBe(false);
    // The count still describes what a press would open, so the label doesn't flicker mid-load.
    expect(renderButtonState([{ url: "https://h/a.zip" }, { url: "https://h/b.zip" }], "", true).label).toBe("Render 2 archives");
  });

  test("the spinner says what it is actually doing with each kind of source", () => {
    // A URL is downloaded and a file is read. A mixed list is neither, and saying "Downloading" over
    // one that is half local files describes work the page isn't doing.
    expect(loadingMessage([{ url: "https://h/a.zip" }])).toBe("Downloading archive…");
    expect(loadingMessage([{ url: "https://h/a.zip" }, { url: "https://h/b.zip" }])).toBe("Downloading 2 archives…");
    expect(loadingMessage([file("run.zip")])).toBe("Reading run.zip…");
    expect(loadingMessage([file("a.zip"), file("b.zip")])).toBe("Reading 2 archives…");
    expect(loadingMessage([{ url: "https://h/a.zip" }, file("b.zip")])).toBe("Loading 2 archives…");
  });

  test("each row is a list item, so a screen reader hears a list of archives", () => {
    expect(sourceListHtml([{ url: "https://h/a.zip" }])).toContain('role="listitem"');
  });
});

describe("what pressing Render does", () => {
  const bytes = async () => new ArrayBuffer(0);
  const local: ArchiveSource = { name: "local.zip", file: { size: 1, lastModified: 1, arrayBuffer: bytes } };

  test("a list of URLs navigates to its own permalink instead of loading in place", () => {
    // Navigating is what keeps the address bar equal to the link the reader could share, and boots
    // the viewer exactly once per document.
    const plan = renderPlan("/v/", [{ url: "https://h/a.zip" }, { url: "https://h/b.zip" }], "");
    expect(plan.kind).toBe("navigate");
    expect(zipParamsFrom(`https://viewer.example${(plan as { href: string }).href}`))
      .toEqual(["https://h/a.zip", "https://h/b.zip"]);
  });

  test("a list holding a local file renders where it is, because it has nowhere to navigate to", () => {
    expect(renderPlan("/v/", [local], "").kind).toBe("here");
    expect(renderPlan("/v/", [{ url: "https://h/a.zip" }, local], "").kind).toBe("here");
    // And it carries the staged list, so the caller loads exactly what the button counted.
    expect((renderPlan("/v/", [local], "https://h/a.zip") as { sources: ArchiveSource[] }).sources)
      .toEqual([local, { url: "https://h/a.zip" }]);
  });

  test("what is typed but not yet added is part of the plan, and nothing at all is no plan", () => {
    // "Paste a URL, press Render" is the documented primary path: the field alone has to be enough.
    const plan = renderPlan("/v/", [], "https://h/a.zip");
    expect(plan.kind).toBe("navigate");
    expect(zipParamsFrom(`https://viewer.example${(plan as { href: string }).href}`)).toEqual(["https://h/a.zip"]);
    expect(renderPlan("/v/", [], "").kind).toBe("none");
    expect(renderPlan("/v/", [], "   ").kind).toBe("none");
  });

  test("whether a list is shareable is one answer, not two", () => {
    // The fork above and the Share button are the same question. Answering it twice is how a list
    // renders in place while advertising a link that reproduces nothing.
    expect(sourcesShareable([{ url: "https://h/a.zip" }])).toBe(true);
    expect(sourcesShareable([{ url: "https://h/a.zip" }, local])).toBe(false);
    expect(sourcesShareable([])).toBe(false);
    expect(sourcesShareable([{ url: "https://h/a.zip" }])).toBe(renderPlan("/v/", [{ url: "https://h/a.zip" }], "").kind === "navigate");
    expect(sourcesShareable([local])).toBe(renderPlan("/v/", [local], "").kind === "navigate");
  });
});

describe("several archives becoming one report", () => {
  test("sessions concatenate in list order, sizes add up, and the first archive stamps the report", () => {
    // List order IS the lane order the run index, the device matrix, and the Trail view read.
    const combined = combineArchives([
      { generatedAt: "FIRST-TS", sessions: ["a1", "a2"], zipBytes: 1000 },
      { generatedAt: "SECOND-TS", sessions: ["b1"], zipBytes: 24 },
    ]);
    expect(combined.sessions).toEqual(["a1", "a2", "b1"]);
    expect(combined.totalBytes).toBe(1024);
    // The archives were generated at different moments and the report carries one stamp, so the
    // reader's own order decides it.
    expect(combined.generatedAt).toBe("FIRST-TS");
  });

  test("one archive is the same path as several, and an empty build is not a throw", () => {
    expect(combineArchives([{ generatedAt: "TS", sessions: ["only"], zipBytes: 512 }]))
      .toEqual({ generatedAt: "TS", sessions: ["only"], totalBytes: 512 });
    expect(combineArchives([])).toEqual({ generatedAt: "", sessions: [], totalBytes: 0 });
  });
});

describe("the address left behind by a locally-dropped archive", () => {
  // A file read off disk has no address, so the URL must not keep describing one — including the
  // viewer route keys, which would otherwise be applied to the newly-loaded archive.
  test("drops the archive param and the viewer's route keys, keeping everything else", () => {
    const href = "https://viewer.example/v/?zip=https%3A%2F%2Fhost%2Fa.zip&tab=lightbox&step=4&run=2&theme=dark";
    const next = addressWithoutArchive(href);
    const params = new URL(`https://viewer.example${next}`).searchParams;
    expect(params.has("zip")).toBe(false);
    expect(params.has("tab")).toBe(false);
    expect(params.has("step")).toBe(false);
    expect(params.has("run")).toBe(false);
    // Not ours to drop: a param the shell and the viewer both know nothing about survives.
    expect(params.get("theme")).toBe("dark");
    expect(next.startsWith("/v/")).toBe(true);
  });

  // Every key the viewer routes on, including the Trail view's layout params. One left behind is
  // an instruction from the old report silently applied to the new one — a dropped archive would
  // open on a trail projection that has nothing to do with it. Driven off the set the viewer itself
  // routes on, so a key added there without being stripped here fails rather than going quiet.
  test("drops every viewer route key, not just the ones the detail view uses", () => {
    const keys = VIEWER_ROUTE_KEYS;
    expect(keys).toContain("mode");
    const href = `https://viewer.example/v/?zip=https%3A%2F%2Fhost%2Fa.zip&${keys.map((key) => `${key}=x`).join("&")}&theme=dark`;
    const params = new URL(`https://viewer.example${addressWithoutArchive(href)}`).searchParams;
    expect(keys.filter((key) => params.has(key))).toEqual([]);
    expect(params.get("theme")).toBe("dark");
  });

  test("reports nothing to rewrite when the address is already clean, or is malformed", () => {
    // '' means "skip the history write" — distinct from a URL that needed rewriting to become bare.
    expect(addressWithoutArchive("https://viewer.example/v/")).toBe("");
    expect(addressWithoutArchive("not a url")).toBe("");
    expect(addressWithoutArchive("https://viewer.example/v/?zip=x")).toBe("/v/");
  });
});

describe("what the header chip reports", () => {
  test("counts steps for one session and sessions for several, with a scaled size", () => {
    expect(describeArchive([{ trace: [1, 2, 3] }], 4096)).toBe("3 steps · 4 KB");
    expect(describeArchive([{ trace: [1] }, { trace: [2] }], 2097152)).toBe("2 sessions · 2.0 MB");
    // A session whose trace is absent contributes no steps rather than throwing.
    expect(describeArchive([{}], 512)).toBe("0 steps · 1 KB");
  });
});

describe("a URL that could not be fetched", () => {
  test("names the CORS possibility alongside the error, since the two are indistinguishable here", () => {
    const message = fetchFailureMessage("https://host/a.zip", new Error("Failed to fetch"));
    expect(message).toContain("Failed to fetch");
    expect(message).toContain("Access-Control-Allow-Origin");
  });

  test("names the archive it was reading, which a list of several otherwise leaves ambiguous", () => {
    // With three lined up, "Failed to fetch" alone doesn't say which row to take back out.
    expect(fetchFailureMessage("https://host/b.zip", new Error("Failed to fetch"))).toContain("https://host/b.zip");
  });

  test("survives a thrown non-Error", () => {
    expect(fetchFailureMessage("https://host/a.zip", "boom")).toContain("boom");
  });

  test("bytes that stop arriving after the response did name their source too, without the CORS hint", () => {
    // A dropped connection or a decoding failure throws from the BODY read, not from the request —
    // by then the cross-origin read plainly worked, so the hint would send the reader after the
    // wrong thing, and the bare browser message names no archive at all.
    const message = readFailureMessage("https://host/b.zip", new Error("network error"));
    expect(message).toContain("network error");
    expect(message).toContain("https://host/b.zip");
    expect(message).not.toContain("Access-Control-Allow-Origin");
    // A local file is named the same way: it is a row in the same list.
    expect(readFailureMessage("session.zip", "boom")).toContain("session.zip");
    expect(readFailureMessage("session.zip", "boom")).toContain("boom");
  });

  test("an archive that downloads but won't parse names its row, and only when there are several", () => {
    // The zip pipeline throws AFTER a clean download, so a bad archive out of five would otherwise
    // report "Not a ZIP archive" and name none of them.
    const many = archiveFailure(new Error("Not a ZIP archive"), "https://host/c.zip", true);
    expect((many as Error).message).toContain("Not a ZIP archive");
    expect((many as Error).message).toContain("https://host/c.zip");
    // One archive is already named by being the only one; wrapping it repeats what the reader typed
    // back at them, so the pipeline's own error passes through untouched.
    const one = new Error("Not a ZIP archive");
    expect(archiveFailure(one, "https://host/a.zip", false)).toBe(one);
  });
});

describe("shell / export payload parity", () => {
  // The shell renders in place from toSessionPayloads; an exported document embeds the same function's
  // output as inert JSON. Locking them together is what lets the shell share the renderer rather than
  // reimplement its data contract.
  test("in-place hydration and an exported document give the viewer identical session data", () => {
    const sessions = [{
      meta: { title: "Case 1", status: "Ended.Succeeded", recordingYaml: "recording: yaml" },
      trace: [
        { i: 0, screenshotFile: "shot_0.webp", type: "trailblaze_command" },
        { i: 1, screenshotFile: "shot_1.webp", type: "trailblaze_command" },
      ],
      llmLogs: [{ id: "llm-1", inputTokens: 10, outputTokens: 2, totalCost: 0.5 }],
      shots: { "shot_0.webp": "data:image/webp;base64,AAAA" },
    }];

    const inPlace = toSessionPayloads({ generatedAt: "FIXED-TS", sessions: sessions as never });
    // No `video` in these inputs on purpose: an exported document hoists sprite data URIs out into a
    // separate chunk, which is a document-layout concern the in-place payload has no equivalent for.
    const exported = buildMultiReportHtml({ generatedAt: "FIXED-TS", sessions: sessions as never });
    const chunk = /<script type="application\/json" id="tb-session-0">([\s\S]*?)<\/script>/.exec(exported);
    expect(chunk).not.toBeNull();

    expect(JSON.parse(chunk![1])).toEqual(inPlace[0]);
    // And the shaping the viewer depends on actually happened.
    expect(inPlace[0].meta.generatedAt).toBe("FIXED-TS");
    expect(inPlace[0].meta.steps).toBe(2);
    expect(inPlace[0].recordingYaml).toBe("recording: yaml");
    expect(inPlace[0].llm.length).toBe(1);
  });
});

describe("the archive's recording clip", () => {
  const withClip = [{
    meta: { title: "Case 1", status: "Ended.Succeeded" },
    trace: [{ i: 0, screenshotFile: "shot_0.webp", type: "trailblaze_command" }],
    llmLogs: [],
    shots: {},
    videoClip: { url: "blob:https://example.test/9d1f-clip", durationSec: 12.5, startMs: 1000, endMs: 13500 },
  }];

  test("plays in the page that read the archive, and is dropped from a standalone document", () => {
    // In place the clip is the whole point: Replay plays the archive's own mp4 out of this page's
    // bytes rather than flipping through screenshots.
    expect(toSessionPayloads({ generatedAt: "FIXED-TS", sessions: withClip as never })[0].videoClip?.url)
      .toBe("blob:https://example.test/9d1f-clip");
    // Serialized it is a dangling reference — an object URL only the loading document can resolve —
    // so an exported file would badge the lane REC and then fail to play anything.
    const exported = buildMultiReportHtml({ generatedAt: "FIXED-TS", sessions: withClip as never });
    const chunk = /<script type="application\/json" id="tb-session-0">([\s\S]*?)<\/script>/.exec(exported);
    expect(chunk).not.toBeNull();
    expect(chunk![1]).not.toContain("blob:");
    expect(JSON.parse(chunk![1]).videoClip).toBeNull();
  });

  test("attachment object URLs strip by default and survive only for the in-page zip viewer", () => {
    const withAttachments = [{
      ...withClip[0],
      attachments: {
        "attachments/a.wav": "blob:https://example.test/att-a",
        "attachments/b.wav": "data:audio/wav;base64,AAAA",
      },
    }];
    const sessionChunk = (html: string) => JSON.parse(/<script type="application\/json" id="tb-session-0">([\s\S]*?)<\/script>/.exec(html)![1]);

    // Default: a downloaded document outlives the page that minted the URL, so the blob: entry goes
    // and the portable data: embed stays.
    const exported = sessionChunk(buildMultiReportHtml({ generatedAt: "FIXED-TS", sessions: withAttachments as never }));
    expect(exported.attachments).toEqual({ "attachments/b.wav": "data:audio/wav;base64,AAAA" });

    // Opted in: the zip viewer renders this HTML as the srcDoc of a same-origin iframe on the very
    // page holding the archive's bytes, so the object URLs still resolve and stripping them is what
    // would break Open. The clip is NOT covered by the flag — its own rule is unchanged.
    const inPage = sessionChunk(buildMultiReportHtml({ generatedAt: "FIXED-TS", sessions: withAttachments as never, keepAttachmentObjectUrls: true }));
    expect(inPage.attachments["attachments/a.wav"]).toBe("blob:https://example.test/att-a");
    expect(inPage.videoClip).toBeNull();

    // Stripping every entry leaves null rather than an empty map, so the viewer's "any attachments?"
    // check reads the same as a session that referenced none.
    const allBlobs = sessionChunk(buildMultiReportHtml({
      generatedAt: "FIXED-TS",
      sessions: [{ ...withClip[0], attachments: { "attachments/a.wav": "blob:https://example.test/att-a" } }] as never,
    }));
    expect(allBlobs.attachments).toBeNull();
  });

  test("hands its bytes back to the browser when the next archive replaces it", () => {
    // An object URL pins its Blob for the life of the DOCUMENT, and this shell swaps one report for
    // another in place — without revoking, every recording ever opened stays in memory.
    expect(objectUrlsToRevoke({ sessions: withClip })).toEqual(["blob:https://example.test/9d1f-clip"]);
    // Only what this page minted: a hosted mp4 URL is not ours to revoke.
    expect(objectUrlsToRevoke({ sessions: [{ videoClip: { url: "https://cdn.test/run.mp4" } }] })).toEqual([]);
    // This runs on EVERY hydrate, including the first, so nothing loaded yet, a clipless report, and
    // an unrecognized payload shape all have to be quiet no-ops.
    expect(objectUrlsToRevoke(undefined)).toEqual([]);
    expect(objectUrlsToRevoke({ sessions: [{ videoClip: null }, {}] })).toEqual([]);
    expect(objectUrlsToRevoke({ sessions: "not a list" })).toEqual([]);
  });

  test("sweeps attachment object URLs too, not just the recording clip", () => {
    // The zip pipeline mints one object URL per materialized attachment, so an archive full of audio
    // pins far more bytes here than the single clip does. Both producers have to be swept — a sweep
    // that knew only about videoClip leaked the attachments exactly as before it existed.
    const withBoth = [{
      videoClip: { url: "blob:https://example.test/9d1f-clip" },
      attachments: {
        "audio/a.wav": "blob:https://example.test/att-a",
        "audio/b.wav": "blob:https://example.test/att-b",
        // Not ours: a /static link and a data: embed were never minted by this page.
        "audio/c.wav": "/static/session/audio/c.wav",
        "audio/d.wav": "data:audio/wav;base64,AAAA",
      },
    }];
    expect(objectUrlsToRevoke({ sessions: withBoth })).toEqual([
      "blob:https://example.test/9d1f-clip",
      "blob:https://example.test/att-a",
      "blob:https://example.test/att-b",
    ]);
    // A session with attachments and no clip is the common zip-viewer case; a non-object attachments
    // value is what a malformed payload looks like, and neither may throw on the hydrate path.
    expect(objectUrlsToRevoke({ sessions: [{ attachments: { "a.wav": "blob:x" } }] })).toEqual(["blob:x"]);
    expect(objectUrlsToRevoke({ sessions: [{ attachments: null }, { attachments: "nope" }] })).toEqual([]);
  });
});
