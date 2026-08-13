// The viewer shell: the data-less edition of the report that loads a session archive in the browser.
// Covers the artifact's observable properties, the permalink round-trip, and the parity claim that
// makes the shell safe to ship — that a shell-loaded report hands the viewer the same session payload
// an exported report embeds.
import { describe, expect, test } from "bun:test";
import { toSessionPayloads } from "./run-report-extract";
import { buildMultiReportHtml } from "./run-report-html";
import { buildViewerShellHtml } from "./run-report-shell-html";
import { addressWithoutArchive, describeArchive, fetchFailureMessage, zipParamFrom, zipPermalink } from "./run-report-shell";

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

  test("offers a keyboard-reachable way to open a local archive, not only drag-and-drop", () => {
    expect(shell).toContain('type="file"');
    expect(shell).toContain('accept=".zip,application/zip"');
    expect(shell).toContain("tb-shell-pick");
    // Drag-and-drop stays, as the discoverable path.
    expect(shell).toContain("tb-shell-overlay");
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
    const href = `https://viewer.example${zipPermalink("/sites/trailblaze-report/", archive)}`;
    expect(zipParamFrom(href)).toBe(archive);
  });

  test("trims incidental whitespace from a pasted URL", () => {
    const href = `https://viewer.example${zipPermalink("/v/", "  https://host/a.zip\n")}`;
    expect(zipParamFrom(href)).toBe("https://host/a.zip");
  });

  test("a deep link rides alongside the archive param", () => {
    // How `?zip=…&tab=lightbox` reaches the viewer: the shell owns `zip`, the viewer owns the route
    // keys, and neither strips the other's.
    const href = `https://viewer.example${zipPermalink("/v/", "https://host/a.zip")}&tab=lightbox&step=4`;
    expect(zipParamFrom(href)).toBe("https://host/a.zip");
    const params = new URL(href).searchParams;
    expect(params.get("tab")).toBe("lightbox");
    expect(params.get("step")).toBe("4");
  });

  test("reports no archive for an address that has none, and for a malformed one", () => {
    expect(zipParamFrom("https://viewer.example/v/")).toBe("");
    expect(zipParamFrom("not a url")).toBe("");
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
    const message = fetchFailureMessage(new Error("404 Not Found — could not download the archive."));
    expect(message).toContain("404 Not Found");
    expect(message).toContain("Access-Control-Allow-Origin");
  });

  test("survives a thrown non-Error", () => {
    expect(fetchFailureMessage("boom")).toContain("boom");
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
