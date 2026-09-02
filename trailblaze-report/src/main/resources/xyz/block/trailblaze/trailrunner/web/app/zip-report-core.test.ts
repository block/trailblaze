// Pure-logic tests for the session-zip → run-report assembly (app/zip-report-core.js). No browser,
// no network — zips are built in-test per the ZIP spec (local headers + central directory + EOCD),
// and log records use the same wire shapes the daemon writes (class-discriminated JSON, the
// contract locked by the generated trailrunner-dtos.ts). Inflate is injected from node:zlib the
// way the browser injects DecompressionStream.
//
// Run: `bun test app/zip-report-core.test.ts` from the web/ directory.
import { describe, expect, test } from "bun:test";
import { deflateRawSync, inflateRawSync } from "node:zlib";
// zip-report-core.js dual-exports via module.exports; bun interops the CJS default import.
import Zip from "./zip-report-core.js";
// The frame walk is the real shared one (run-report-core) even where the extractors are faked:
// which files a report gathers is exactly the behavior these tests pin.
import { REPORT_DERIVE } from "./run-report-shell";

const inflateRaw = (data: Uint8Array) => new Uint8Array(inflateRawSync(data));
const encoder = new TextEncoder();

// Minimal spec-conformant ZIP writer (deflate or stored, no zip64, no data descriptors) so the
// reader is exercised against real archive bytes rather than mocked structures.
function buildZip(files: { name: string; data?: Uint8Array; text?: string; stored?: boolean }[]): Uint8Array {
  const chunks: Uint8Array[] = [];
  const central: Uint8Array[] = [];
  let offset = 0;
  for (const f of files) {
    const data = f.data ?? encoder.encode(f.text ?? "");
    const nameBytes = encoder.encode(f.name);
    const isDir = f.name.endsWith("/");
    const method = f.stored || isDir ? 0 : 8;
    const payload = method === 8 ? new Uint8Array(deflateRawSync(data)) : data;

    const local = new Uint8Array(30 + nameBytes.length);
    const lv = new DataView(local.buffer);
    lv.setUint32(0, 0x04034b50, true);
    lv.setUint16(4, 20, true);
    lv.setUint16(10, method, true);
    lv.setUint32(18, payload.length, true);
    lv.setUint32(22, data.length, true);
    lv.setUint16(26, nameBytes.length, true);
    local.set(nameBytes, 30);

    const cen = new Uint8Array(46 + nameBytes.length);
    const cv = new DataView(cen.buffer);
    cv.setUint32(0, 0x02014b50, true);
    cv.setUint16(4, 20, true);
    cv.setUint16(6, 20, true);
    cv.setUint16(10, method, true);
    cv.setUint32(20, payload.length, true);
    cv.setUint32(24, data.length, true);
    cv.setUint16(28, nameBytes.length, true);
    cv.setUint32(42, offset, true);
    cen.set(nameBytes, 46);

    chunks.push(local, payload);
    central.push(cen);
    offset += local.length + payload.length;
  }
  const centralStart = offset;
  let centralSize = 0;
  for (const c of central) centralSize += c.length;
  const eocd = new Uint8Array(22);
  const ev = new DataView(eocd.buffer);
  ev.setUint32(0, 0x06054b50, true);
  ev.setUint16(8, central.length, true);
  ev.setUint16(10, central.length, true);
  ev.setUint32(12, centralSize, true);
  ev.setUint32(16, centralStart, true);
  const total = centralStart + centralSize + 22;
  const zip = new Uint8Array(total);
  let p = 0;
  for (const chunk of [...chunks, ...central, eocd]) { zip.set(chunk, p); p += chunk.length; }
  return zip;
}

// Wire-shaped log records (the same shapes the daemon writes into a session archive).
const SESSION_ID = "android_phone_trail__suites_suite_1_case_2_7b0589c5";
const STATUS_LOG = "xyz.block.trailblaze.logs.client.TrailblazeLog.TrailblazeSessionStatusChangeLog";
const STATUS = "xyz.block.trailblaze.logs.model.SessionStatus.";

function startedLog(overrides: Record<string, unknown> = {}, statusOverrides: Record<string, unknown> = {}) {
  return {
    class: STATUS_LOG,
    sessionStatus: {
      class: STATUS + "Started",
      trailConfig: {
        id: "suites/suite_1/case_2",
        title: "Remove item from cart",
        target: "sample-app",
      },
      trailFilePath: "/ci/workspace/trails/suites/suite_1/case_2/android-phone.trail.yaml",
      hasRecordedSteps: true,
      testMethodName: "android-phone",
      testClassName: "case_2",
      trailblazeDeviceInfo: {
        trailblazeDeviceId: { instanceId: "emulator-5554", trailblazeDevicePlatform: "ANDROID" },
        trailblazeDriverType: "ANDROID_ONDEVICE_ACCESSIBILITY",
        widthPixels: 0,
        heightPixels: 0,
        classifiers: ["android", "phone"],
      },
      trailblazeDeviceId: { instanceId: "emulator-5554", trailblazeDevicePlatform: "ANDROID" },
      ...statusOverrides,
    },
    session: SESSION_ID,
    timestamp: "2026-06-30T20:21:27.456796Z",
    ...overrides,
  };
}

function endedLog(statusClass: string, statusFields: Record<string, unknown> = {}, timestamp = "2026-06-30T20:22:58.000000Z") {
  return {
    class: STATUS_LOG,
    sessionStatus: { class: STATUS + statusClass, durationMs: 90592, ...statusFields },
    session: SESSION_ID,
    timestamp,
  };
}

describe("zip reader", () => {
  test("round-trips deflated and stored entries through a real archive", async () => {
    const zip = buildZip([
      { name: "dir/", text: "" },
      { name: "dir/a.json", text: '{"hello":"world"}' },
      { name: "dir/b.txt", text: "stored bytes", stored: true },
    ]);
    const entries = Zip.parseZipEntries(zip);
    // Directory placeholder entries are dropped; both files are readable regardless of method.
    expect(entries.map((e: { name: string }) => e.name).sort()).toEqual(["dir/a.json", "dir/b.txt"]);
    const byName = Object.fromEntries(entries.map((e: { name: string }) => [e.name, e]));
    const a = await Zip.readZipEntry(zip, byName["dir/a.json"], inflateRaw);
    expect(JSON.parse(new TextDecoder().decode(a))).toEqual({ hello: "world" });
    const b = await Zip.readZipEntry(zip, byName["dir/b.txt"], inflateRaw);
    expect(new TextDecoder().decode(b)).toBe("stored bytes");
  });

  test("rejects bytes that are not a zip archive", () => {
    expect(() => Zip.parseZipEntries(encoder.encode("<html>not a zip</html>"))).toThrow();
  });
});

describe("session file selection (LogsRepo read slice)", () => {
  test("accepts hex-prefixed .json logs and rejects everything else", () => {
    expect(Zip.isSessionLogFileName("001_TrailblazeSessionStatusChangeLog.json")).toBe(true);
    expect(Zip.isSessionLogFileName("f00_SomeLog.json")).toBe(true);
    expect(Zip.isSessionLogFileName("capture_metadata.json")).toBe(false); // explicit exclusion ('c' is hex)
    expect(Zip.isSessionLogFileName("trace.json")).toBe(false); // 't' is not a hex digit
    expect(Zip.isSessionLogFileName("recording.trail.yaml")).toBe(false);
    expect(Zip.isSessionLogFileName("001_log.json.bak")).toBe(false);
  });

  test("classifies images by the TrailblazeImageFormat extensions", () => {
    expect(Zip.isImageFileName("shot.webp")).toBe(true);
    expect(Zip.isImageFileName("shot.PNG")).toBe(true);
    expect(Zip.isImageFileName("video.mp4")).toBe(false);
    expect(Zip.isImageFileName("notes.txt")).toBe(false);
    expect(Zip.imageMimeType("shot.webp")).toBe("image/webp");
    expect(Zip.imageMimeType("shot.jpg")).toBe("image/jpeg");
  });

  test("groups entries by top-level session dir, retaining nested files under their subpath", () => {
    const groups = Zip.groupEntriesBySession([
      { name: "sessionA/001_Log.json" },
      { name: "sessionA/shot.webp" },
      { name: "sessionA/events/speech.ndjson" },
      { name: "sessionA/attachments/utterance_1.wav" },
      { name: "sessionA/in-process-scripted-tools/tool.js" },
      { name: "sessionB/001_Log.json" },
    ]);
    expect(groups.map((g: { sessionId: string }) => g.sessionId)).toEqual(["sessionA", "sessionB"]);
    // Nested files stay addressable by their session-relative subpath (that's how an event's
    // attachment ref resolves); the flat inventories elsewhere ignore them.
    expect(Object.keys(groups[0].byFileName).sort()).toEqual([
      "001_Log.json", "attachments/utterance_1.wav", "events/speech.ndjson",
      "in-process-scripted-tools/tool.js", "shot.webp",
    ]);
  });

  test("treats a flat archive (no directories) as a single unnamed session", () => {
    const groups = Zip.groupEntriesBySession([{ name: "001_Log.json" }, { name: "shot.webp" }]);
    expect(groups.length).toBe(1);
    expect(groups[0].sessionId).toBe("");
  });

  test("folds a flat archive's logless subdirs back into the unnamed session, by any name", () => {
    // A zip made from INSIDE the session dir presents the session's own subdirectories as top-level
    // dirs; they belong to the root session, not to phantom sessions named "events"/"attachments".
    // `media/` is the case a known-names list gets wrong: AttachmentRef's contract is that the path
    // is authoritative and `attachments/` is only a convention, so a ref to `media/take.wav` has to
    // stay reachable in the root session's map or its bytes cannot be found.
    const groups = Zip.groupEntriesBySession([
      { name: "001_Log.json" },
      { name: "events/speech.ndjson" },
      { name: "attachments/utterance_1.wav" },
      { name: "media/take.wav" },
    ]);
    expect(groups.length).toBe(1);
    expect(groups[0].sessionId).toBe("");
    expect(Object.keys(groups[0].byFileName).sort())
      .toEqual(["001_Log.json", "attachments/utterance_1.wav", "events/speech.ndjson", "media/take.wav"]);
    // Without root files there is no unnamed session to fold into — dirs group as usual.
    const dirsOnly = Zip.groupEntriesBySession([{ name: "events/speech.ndjson" }]);
    expect(dirsOnly.map((g: { sessionId: string }) => g.sessionId)).toEqual(["events"]);
  });

  test("a real sibling session is never folded into a flat archive's root session", () => {
    // The fold is on absence of session logs, so a directory that IS a session keeps its own group
    // even when root-level files exist alongside it.
    const groups = Zip.groupEntriesBySession([
      { name: "001_Log.json" },
      { name: "media/take.wav" },
      { name: "sessionB/002_Log.json" },
      { name: "sessionB/attachments/b.wav" },
    ]);
    expect(groups.map((g: { sessionId: string }) => g.sessionId)).toEqual(["", "sessionB"]);
    expect(Object.keys(groups[0].byFileName).sort()).toEqual(["001_Log.json", "media/take.wav"]);
    expect(Object.keys(groups[1].byFileName).sort()).toEqual(["002_Log.json", "attachments/b.wav"]);
  });

  test("orders logs chronologically, keeping feed order for identical timestamps", () => {
    const at = (t: string, tag: string) => ({ timestamp: t, tag });
    const sorted = Zip.sortLogsByTimestamp([
      at("2026-06-30T20:21:29Z", "third"),
      at("2026-06-30T20:21:27Z", "first"),
      at("2026-06-30T20:21:28Z", "second-a"),
      at("2026-06-30T20:21:28Z", "second-b"),
    ]);
    expect(sorted.map((l: { tag: string }) => l.tag)).toEqual(["first", "second-a", "second-b", "third"]);
  });

  // The pair this ordering exists for: a driver action and the tool call it belongs to, microseconds
  // apart, written to files in the opposite order. Date.parse stops at the millisecond, so a
  // millisecond-only key calls them equal and the feed-order tiebreak keeps the order that needs
  // correcting — and the run then folds or counts differently here than in the report the daemon
  // and the CLI render from the same logs.
  test("orders a sub-millisecond pair by its fraction, not the filename order it arrived in", () => {
    const at = (t: string, tag: string) => ({ timestamp: t, tag });
    const sorted = Zip.sortLogsByTimestamp([
      at("2026-06-30T20:21:28.500900Z", "tool"),
      at("2026-06-30T20:21:28.500120Z", "driver-action"),
    ]);
    expect(sorted.map((l: { tag: string }) => l.tag)).toEqual(["driver-action", "tool"]);
  });
});

describe("trail names and status labels", () => {
  test("shortTrailName strips the trails/ root and trail.yaml suffixes", () => {
    expect(Zip.shortTrailName("/ci/workspace/trails/suites/suite_1/case_2/android-phone.trail.yaml"))
      .toBe("suites/suite_1/case_2/android-phone");
    expect(Zip.shortTrailName("trails/sample/trail.yaml")).toBe("sample");
    expect(Zip.shortTrailName("trail.yaml")).toBe("trail.yaml");
  });

  test("maps session statuses onto the viewer badge classes", () => {
    expect(Zip.statusLabel({ class: STATUS + "Ended.Succeeded" })).toBe("passed");
    expect(Zip.statusLabel({ class: STATUS + "Ended.SucceededWithSelfHeal" })).toBe("passed");
    expect(Zip.statusLabel({ class: STATUS + "Ended.Failed" })).toBe("failed");
    expect(Zip.statusLabel({ class: STATUS + "Ended.TimeoutReached" })).toBe("failed");
    expect(Zip.statusLabel({ class: STATUS + "Ended.Cancelled" })).toBe("cancelled");
    expect(Zip.statusLabel({ class: STATUS + "Started" })).toBe("running");
    expect(Zip.statusLabel(null)).toBe("unknown");
  });
});

describe("run meta derivation", () => {
  test("derives the full meta from a passing session's logs", () => {
    const logs = [startedLog(), endedLog("Ended.Succeeded", {}, "2026-06-30T20:22:58.048796Z")];
    const meta = Zip.buildRunMeta(logs, { recordingYaml: "- config: {}\n", generatedAt: "test-time" });
    expect(meta.title).toBe("Remove item from cart");
    expect(meta.status).toBe("passed");
    expect(meta.target).toBe("sample-app");
    expect(meta.platform).toBe("android");
    expect(meta.device).toBe("emulator-5554");
    expect(meta.deviceType).toBe("phone"); // classifiers minus the platform name
    expect(meta.deviceClassifier).toBe("android-phone"); // the specific compound classifier
    expect(meta.trailId).toBe("suites/suite_1/case_2");
    expect(meta.duration).toBe("1m 30s");
    expect(meta.cmd).toContain("./trailblaze run ");
    expect(meta.recordingYaml).toBe("- config: {}\n");
    expect(meta.generatedAt).toBe("test-time");
    expect(meta.error).toBeUndefined();
    expect(meta.selfHeal).toBeUndefined();
  });

  test("the classifier is the whole compound identity, not the platform-stripped tail", () => {
    // A zip-opened report has to key its matrix columns the same way a CI-generated one does, so
    // two devices from one hardware family stay distinguishable even though their tails collide.
    const withClassifiers = (classifiers: string[] | null) =>
      Zip.buildRunMeta(
        [startedLog({}, { trailblazeDeviceInfo: { trailblazeDeviceId: { instanceId: "d", trailblazeDevicePlatform: "ANDROID" }, classifiers } })],
        {},
      );
    expect(withClassifiers(["kiosk", "v2"]).deviceClassifier).toBe("kiosk-v2");
    expect(withClassifiers(["kiosk", "v3"]).deviceClassifier).toBe("kiosk-v3");
    // No classifiers → absent, not an empty string a consumer would treat as a real column.
    expect(withClassifiers([]).deviceClassifier).toBeUndefined();
    expect(withClassifiers(null).deviceClassifier).toBeUndefined();
  });

  test("title falls back through config id, trail path, then test class:name", () => {
    const noTitle = startedLog({}, { trailConfig: { id: "suite/case_1" } });
    expect(Zip.buildRunMeta([noTitle], {}).title).toBe("suite/case_1");

    const noConfig = startedLog({}, { trailConfig: null });
    expect(Zip.buildRunMeta([noConfig], {}).title).toBe("suites/suite_1/case_2/android-phone");

    const bareTest = startedLog({}, { trailConfig: null, trailFilePath: null });
    expect(Zip.buildRunMeta([bareTest], {}).title).toBe("case_2:android-phone");

    // The MCP transport marker is suppressed from user-facing names.
    const mcp = startedLog({}, { trailConfig: null, trailFilePath: null, testClassName: "MCP" });
    expect(Zip.buildRunMeta([mcp], {}).title).toBe("android-phone");
  });

  test("forwards consumer trailConfig.metadata for the Info tab and Owner sort", () => {
    const withMeta = startedLog({}, { trailConfig: { id: "suite/case_1", metadata: { owner: "team-a", accountToken: "AT_123" } } });
    expect(Zip.buildRunMeta([withMeta], {}).metadata).toEqual({ owner: "team-a", accountToken: "AT_123" });
    expect(Zip.buildRunMeta([startedLog()], {}).metadata).toBeUndefined();
    const emptyMeta = startedLog({}, { trailConfig: { id: "suite/case_1", metadata: {} } });
    expect(Zip.buildRunMeta([emptyMeta], {}).metadata).toBeUndefined();
  });

  test("surfaces the failure reason and self-heal marker", () => {
    const failed = Zip.buildRunMeta(
      [startedLog(), endedLog("Ended.Failed", { exceptionMessage: "Element not found: Save" })],
      {},
    );
    expect(failed.status).toBe("failed");
    expect(failed.error).toBe("Element not found: Save");
    expect(failed.failureCode).toBeUndefined();

    // A structured failurePayload's top-level string `code` lands as meta.failureCode — same
    // lift as sessionMetaJson, so archive reports render the banner chip too.
    const coded = Zip.buildRunMeta(
      [startedLog(), endedLog("Ended.Failed", { exceptionMessage: "locked out", failurePayload: { schema: "example-repo/trailhead-error/v1", code: "account-state" } })],
      {},
    );
    expect(coded.failureCode).toBe("account-state");
    // Non-string codes are not coerced.
    const numeric = Zip.buildRunMeta(
      [startedLog(), endedLog("Ended.Failed", { exceptionMessage: "boom", failurePayload: { code: 7 } })],
      {},
    );
    expect(numeric.failureCode).toBeUndefined();

    const healed = Zip.buildRunMeta([startedLog(), endedLog("Ended.SucceededWithSelfHeal")], {});
    expect(healed.status).toBe("passed");
    expect(healed.selfHeal).toBe(true);

    const maxCalls = Zip.buildRunMeta(
      [startedLog(), endedLog("Ended.MaxCallsLimitReached", { maxCalls: 25, objectivePrompt: "Tap Save" })],
      {},
    );
    expect(maxCalls.error).toContain("25");
    expect(maxCalls.error).toContain("Tap Save");
  });

  test("a heal the run recovered from cleanly still reports the self-heal", () => {
    const selfHealLog = {
      class: "xyz.block.trailblaze.logs.client.TrailblazeLog.SelfHealInvokedLog",
      promptStep: { prompt: "Tap Save" },
      session: SESSION_ID,
      timestamp: "2026-06-30T20:22:00.000000Z",
    };
    const meta = Zip.buildRunMeta([startedLog(), selfHealLog, endedLog("Ended.Succeeded")], {});
    expect(meta.status).toBe("passed");
    expect(meta.selfHeal).toBe(true);
  });

  test("running session (no Ended log) reads as running", () => {
    expect(Zip.buildRunMeta([startedLog()], {}).status).toBe("running");
  });
});

describe("loadZipSessions end-to-end", () => {
  test("assembles sessions from a session archive: sorted logs, recording yaml, image inventory", async () => {
    const dir = SESSION_ID + "/";
    const zip = buildZip([
      { name: dir, text: "" },
      // Deliberately numbered against chronological order to prove timestamp sorting.
      { name: dir + "002_TrailblazeSessionStatusChangeLog.json", text: JSON.stringify(endedLog("Ended.Succeeded")) },
      { name: dir + "001_TrailblazeSessionStatusChangeLog.json", text: JSON.stringify(startedLog()) },
      { name: dir + "capture_metadata.json", text: "{}" },
      { name: dir + "trace.json", text: "{}" },
      { name: dir + "recording.trail.yaml", text: "- config:\n    id: test\n" },
      { name: dir + "shot_1.webp", data: new Uint8Array([1, 2, 3, 4]) },
      { name: dir + "video.mp4", data: new Uint8Array([9, 9]) },
      { name: dir + "in-process-scripted-tools/tool.js", text: "// tool" },
    ]);

    const sessions = await Zip.loadZipSessions(zip, { inflateRaw });
    expect(sessions.length).toBe(1);
    const session = sessions[0];
    expect(session.sessionId).toBe(SESSION_ID);
    expect(session.logs.length).toBe(2); // capture_metadata + trace + yaml + media excluded
    expect(Zip.statusLabel(session.logs[1].sessionStatus)).toBe("passed"); // chronological despite file naming
    expect(session.recordingYaml).toContain("id: test");
    expect(session.imageFiles).toEqual(["shot_1.webp"]);

    const dataUri = await Zip.sessionImageDataUri(zip, session, "shot_1.webp", { inflateRaw });
    expect(dataUri).toBe("data:image/webp;base64," + Buffer.from([1, 2, 3, 4]).toString("base64"));
    expect(await Zip.sessionImageDataUri(zip, session, "missing.webp", { inflateRaw })).toBeNull();
  });

  test("passes an absolute-URL screenshotFile through as its own src (test-farm remote shots)", async () => {
    // Test-farm runs bundle only a subset of screenshots in the zip and record the rest as remote
    // lambda URLs. A URL is already a usable <img src> — it must be returned verbatim, not looked up
    // in (and missed from) the archive.
    const zip = buildZip([{ name: SESSION_ID + "/001_Log.json", text: JSON.stringify(startedLog()) }]);
    const [session] = await Zip.loadZipSessions(zip, { inflateRaw });
    const remote = "https://abc123.lambda-url.us-east-2.on.aws/?bucket=farm&key=shot_9.webp";
    expect(await Zip.sessionImageDataUri(zip, session, remote, { inflateRaw })).toBe(remote);
  });

  test("skips top-level groups that contain no session logs", async () => {
    const zip = buildZip([
      { name: "not-a-session/readme.txt", text: "hi" },
      { name: "real-session/001_Log.json", text: JSON.stringify(startedLog()) },
    ]);
    const sessions = await Zip.loadZipSessions(zip, { inflateRaw });
    expect(sessions.map((s: { sessionId: string }) => s.sessionId)).toEqual(["real-session"]);
  });
});

describe("buildReportHtmlFromZipBytes (shared zip → report-HTML assembly)", () => {
  // The renderer (run-report-core.js) is a collaborator, injected here so the test asserts what the
  // assembly HANDS it — the composed inputs — without depending on the real renderer's output. In
  // the browser this same code path reads the renderer from the globals run-report-core.js sets.
  const REMOTE = "https://x.lambda-url.us-east-2.on.aws/?bucket=farm&key=remote.webp";

  // Spread from the viewer shell's real collaborator object, so the only things stubbed here are the
  // derivations this test wants to control. A function the pipeline starts consulting that the shell
  // does not hand it fails here instead of in a browser.
  function fakeRenderer(captured: { input?: unknown }) {
    return {
      ...REPORT_DERIVE,
      extractTrace: () => [
        { screenshotFile: "shot_1.webp", label: "in-zip" },
        { screenshotFile: REMOTE, label: "remote" },
        // A folded batch: the row itself captured nothing, but its child dispatch did — that frame
        // must be gathered too (it's what the child previews when selected).
        { screenshotFile: null, label: "no shot", children: [{ label: "tapOnElementBySelector", tool: "", screenshotFile: "kid_1.webp" }] },
      ],
      extractLlmLogs: () => [{ id: "llm-1" }],
      originalYamlFromLogs: () => "orig: yaml",
      buildRunReportHtml: (input: unknown) => { captured.input = input; return "<html>SINGLE</html>"; },
      buildMultiReportHtml: (input: unknown) => { captured.input = input; return "<html>MULTI</html>"; },
    };
  }

  test("composes sessions, meta, and referenced screenshots into the single-session renderer call", async () => {
    const dir = SESSION_ID + "/";
    const zip = buildZip([
      { name: dir + "001_Log.json", text: JSON.stringify(startedLog()) },
      { name: dir + "002_Log.json", text: JSON.stringify(endedLog("Ended.Succeeded")) },
      { name: dir + "shot_1.webp", data: new Uint8Array([1, 2, 3, 4]) },
      { name: dir + "kid_1.webp", data: new Uint8Array([5, 6]) },
    ]);
    const captured: { input?: any } = {};
    const built = await Zip.buildReportHtmlFromZipBytes(zip, {
      render: fakeRenderer(captured), inflateRaw, generatedAt: "FIXED-TS",
    });

    expect(built.html).toBe("<html>SINGLE</html>");
    expect(built.sessions.length).toBe(1);
    expect(built.zipBytes).toBe(zip.length);
    // The trace's in-zip screenshot resolves to a data URI; a remote-URL screenshot passes through;
    // a null screenshotFile is skipped; a folded child dispatch's own frame is gathered like a
    // row's. This is the screenshot-gathering contract callers rely on.
    expect(captured.input.shots["shot_1.webp"]).toBe("data:image/webp;base64," + Buffer.from([1, 2, 3, 4]).toString("base64"));
    expect(captured.input.shots[REMOTE]).toBe(REMOTE);
    expect(captured.input.shots["kid_1.webp"]).toBe("data:image/webp;base64," + Buffer.from([5, 6]).toString("base64"));
    expect(Object.keys(captured.input.shots).sort()).toEqual([REMOTE, "kid_1.webp", "shot_1.webp"]);
    // trace + llmLogs are handed to the renderer verbatim; the injected generatedAt reaches the meta.
    expect(captured.input.trace.length).toBe(3);
    expect(captured.input.llmLogs).toEqual([{ id: "llm-1" }]);
    expect(built.sessions[0].meta.generatedAt).toBe("FIXED-TS");
  });

  test("rejects an archive with no session logs", async () => {
    const zip = buildZip([{ name: "not-a-session/readme.txt", text: "hi" }]);
    await expect(
      Zip.buildReportHtmlFromZipBytes(zip, { render: fakeRenderer({}), inflateRaw }),
    ).rejects.toThrow("No Trailblaze session logs");
  });

  // Stage one on its own — what a home that renders IN PLACE consumes (the viewer shell hydrating
  // itself) instead of an HTML string.
  test("derives the same session inputs without either HTML builder", async () => {
    const dir = SESSION_ID + "/";
    const zip = buildZip([
      { name: dir + "001_Log.json", text: JSON.stringify(startedLog()) },
      { name: dir + "002_Log.json", text: JSON.stringify(endedLog("Ended.Succeeded")) },
      { name: dir + "shot_1.webp", data: new Uint8Array([1, 2, 3, 4]) },
    ]);
    // The shell's own collaborator object, which deliberately carries NO buildRunReportHtml /
    // buildMultiReportHtml: a shell embeds only the viewer bundle, so this stage must never reach
    // for them — and must find everything else it does reach for.
    const derivationOnly = {
      ...REPORT_DERIVE,
      extractTrace: () => [{ screenshotFile: "shot_1.webp", label: "in-zip" }],
      extractLlmLogs: () => [{ id: "llm-1" }],
      originalYamlFromLogs: () => "orig: yaml",
    };
    const built = await Zip.buildSessionInputsFromZipBytes(zip, {
      render: derivationOnly, inflateRaw, generatedAt: "FIXED-TS",
    });

    expect(built.sessions.length).toBe(1);
    expect(built.zipBytes).toBe(zip.length);
    expect(built.generatedAt).toBe("FIXED-TS");
    const [session] = built.sessions;
    expect(session.meta.generatedAt).toBe("FIXED-TS");
    expect(session.llmLogs).toEqual([{ id: "llm-1" }]);
    expect(session.originalYaml).toBe("orig: yaml");
    expect(session.shots["shot_1.webp"]).toBe("data:image/webp;base64," + Buffer.from([1, 2, 3, 4]).toString("base64"));
  });

  test("stage one rejects an archive with no session logs", async () => {
    const zip = buildZip([{ name: "not-a-session/readme.txt", text: "hi" }]);
    await expect(
      Zip.buildSessionInputsFromZipBytes(zip, { render: fakeRenderer({}), inflateRaw }),
    ).rejects.toThrow("No Trailblaze session logs");
  });
});

describe("session events and the attachments they reference", () => {
  // Detection and decode are the SHARED implementations (run-report-events via REPORT_DERIVE), so
  // these tests pin the zip pipeline's wiring, not a re-specification of the contract.
  const derivationOnly = {
    ...REPORT_DERIVE,
    extractTrace: () => [],
    extractLlmLogs: () => [],
    originalYamlFromLogs: () => null,
  };

  const wavRef = { $attachment: true, path: "attachments/utterance_1.wav", mimeType: "audio/wav", sizeBytes: 4, label: "hello" };
  const binRef = { $attachment: true, path: "attachments/data.bin", mimeType: "application/octet-stream", sizeBytes: 2 };
  const goneRef = { $attachment: true, path: "attachments/missing.wav", mimeType: "audio/wav", sizeBytes: 9 };

  function eventsZip() {
    const dir = SESSION_ID + "/";
    return buildZip([
      { name: dir + "001_Log.json", text: JSON.stringify(startedLog()) },
      {
        name: dir + "events/speech.ndjson",
        text: [
          JSON.stringify({ timeMs: 1_700_000_000_000, data: { text: "hello", audio: wavRef } }),
          JSON.stringify({ timeMs: 1_700_000_001_000, data: { blob: binRef, gone: goneRef } }),
        ].join("\n") + "\n",
      },
      { name: dir + "attachments/utterance_1.wav", data: new Uint8Array([1, 2, 3, 4]) },
      { name: dir + "attachments/data.bin", data: new Uint8Array([5, 6]) },
    ]);
  }

  test("decodes events/*.ndjson into the same EventStream shape the bun driver embeds", async () => {
    const built = await Zip.buildSessionInputsFromZipBytes(eventsZip(), { render: derivationOnly, inflateRaw, generatedAt: "T" });
    const [session] = built.sessions;
    expect(session.events.length).toBe(1);
    expect(session.events[0].name).toBe("speech");
    expect(session.events[0].events.length).toBe(2);
    expect(JSON.parse(session.events[0].events[0].d).audio.path).toBe("attachments/utterance_1.wav");
  });

  test("an event stream too big to inflate is skipped instead of decompressing into the tab", async () => {
    // A stream is inflated whole before its first line is decoded, so the archive's declared size is
    // the only thing standing between a highly compressible ndjson and the tab's memory. Squeezed to
    // 10 bytes here so the fixture's own stream is the one that doesn't fit.
    const render = { ...derivationOnly, MAX_EVENT_STREAM_BYTES: 10 };
    const built = await Zip.buildSessionInputsFromZipBytes(eventsZip(), { render, inflateRaw, generatedAt: "T" });
    expect(built.sessions[0].events).toBeNull();
    // Skipping the stream also means nothing it referenced is materialized.
    expect(built.sessions[0].attachments).toBeNull();

    const roomy = { ...derivationOnly, MAX_EVENT_STREAM_BYTES: 1024 * 1024 };
    const fits = await Zip.buildSessionInputsFromZipBytes(eventsZip(), { render: roomy, inflateRaw, generatedAt: "T" });
    expect(fits.sessions[0].events.length).toBe(1);
  });

  test("a stream that blows the per-session total budget is dropped, and the ones under it are kept", async () => {
    const dir = SESSION_ID + "/";
    const line = (text: string) => JSON.stringify({ timeMs: 1, data: { text } }) + "\n";
    const twoStreams = () => buildZip([
      { name: dir + "001_Log.json", text: JSON.stringify(startedLog()) },
      { name: dir + "events/a.ndjson", text: line("first") },
      { name: dir + "events/b.ndjson", text: line("second") },
    ]);
    const all = await Zip.buildSessionInputsFromZipBytes(twoStreams(), { render: derivationOnly, inflateRaw, generatedAt: "T" });
    expect(all.sessions[0].events.map((s: { name: string }) => s.name)).toEqual(["a", "b"]);

    // Exactly enough room for the first decoded stream: the second pushes the session past it.
    const tight = { ...derivationOnly, MAX_EVENT_STREAMS_TOTAL_CHARS: JSON.stringify(all.sessions[0].events[0]).length };
    const built = await Zip.buildSessionInputsFromZipBytes(twoStreams(), { render: tight, inflateRaw, generatedAt: "T" });
    expect(built.sessions[0].events.map((s: { name: string }) => s.name)).toEqual(["a"]);
  });

  test("resolves referenced media attachments to object URLs; non-media and missing files stay out", async () => {
    const built = await Zip.buildSessionInputsFromZipBytes(eventsZip(), { render: derivationOnly, inflateRaw, generatedAt: "T" });
    const [session] = built.sessions;
    // Only the media-typed ref whose bytes are in the archive materializes; the octet-stream ref
    // (never handed to a browser-native element) and the missing file render as in-bundle notes.
    expect(Object.keys(session.attachments)).toEqual(["attachments/utterance_1.wav"]);
    expect(session.attachments["attachments/utterance_1.wav"]).toStartWith("blob:");
  });

  test("an attachment named like an Object.prototype member still resolves", async () => {
    // The shared path rule accepts any single segment, so `__proto__` and `constructor` are legal
    // attachment names. On plain objects they are not: the dedupe set reports `constructor` as
    // already seen before it has seen anything, the archive index stores `__proto__` as a prototype
    // rather than an entry, and assigning the resulting URI back into the map sets nothing.
    const dir = SESSION_ID + "/";
    const protoRef = { $attachment: true, path: "__proto__", mimeType: "audio/wav", sizeBytes: 2 };
    const ctorRef = { $attachment: true, path: "constructor", mimeType: "audio/wav", sizeBytes: 2 };
    const zip = buildZip([
      { name: dir + "001_Log.json", text: JSON.stringify(startedLog()) },
      { name: dir + "events/speech.ndjson", text: JSON.stringify({ timeMs: 1, data: { a: protoRef, b: ctorRef } }) + "\n" },
      { name: dir + "__proto__", data: new Uint8Array([1, 2]) },
      { name: dir + "constructor", data: new Uint8Array([3, 4]) },
    ]);
    const built = await Zip.buildSessionInputsFromZipBytes(zip, { render: derivationOnly, inflateRaw, generatedAt: "T" });
    const attachments = built.sessions[0].attachments;
    expect(Object.keys(attachments).sort()).toEqual(["__proto__", "constructor"]);
    expect(attachments["__proto__"]).toStartWith("blob:");
    expect(attachments["constructor"]).toStartWith("blob:");
  });

  test("a failed attachment read hands its budget reservation back to the refs behind it", async () => {
    // The budget is charged before the inflate, so a corrupt entry that claims what is left would
    // otherwise drop every valid attachment after it while holding no bytes at all.
    const dir = SESSION_ID + "/";
    const badRef = { $attachment: true, path: "attachments/corrupt.wav", mimeType: "audio/wav", sizeBytes: 4 };
    const zip = buildZip([
      { name: dir + "001_Log.json", text: JSON.stringify(startedLog()) },
      { name: dir + "events/speech.ndjson", text: JSON.stringify({ timeMs: 1, data: { first: badRef, second: wavRef } }) + "\n" },
      { name: dir + "attachments/corrupt.wav", data: new Uint8Array([0xff, 0, 0, 0]) },
      { name: dir + "attachments/utterance_1.wav", data: new Uint8Array([1, 2, 3, 4]) },
    ]);
    // Room for exactly one of the two 4-byte entries, and the corrupt one is selected first.
    const render = { ...derivationOnly, ATTACHMENT_MATERIALIZE_MAX_TOTAL_BYTES: 4 };
    const failsOnMarkedEntry = (data: Uint8Array) => {
      const out = inflateRaw(data);
      if (out[0] === 0xff) throw new Error("corrupt entry");
      return out;
    };
    const built = await Zip.buildSessionInputsFromZipBytes(zip, { render, inflateRaw: failsOnMarkedEntry, generatedAt: "T" });
    expect(Object.keys(built.sessions[0].attachments)).toEqual(["attachments/utterance_1.wav"]);
  });

  test("a traversal-shaped ref is refused even when the archive really holds that entry", async () => {
    // An archive can name an entry whatever it likes, so the exact-match lookup alone is not the
    // guard it looks like: with `attachments/../outside.png` present under BOTH names, the ref
    // resolves and the policy that exists to refuse traversal never runs.
    const dir = SESSION_ID + "/";
    const escapeRef = { $attachment: true, path: "attachments/../outside.png", mimeType: "image/png", sizeBytes: 3 };
    const zip = buildZip([
      { name: dir + "001_Log.json", text: JSON.stringify(startedLog()) },
      { name: dir + "events/speech.ndjson", text: JSON.stringify({ timeMs: 1, data: { shot: escapeRef, audio: wavRef } }) + "\n" },
      { name: dir + "attachments/../outside.png", data: new Uint8Array([7, 8, 9]) },
      { name: dir + "attachments/utterance_1.wav", data: new Uint8Array([1, 2, 3, 4]) },
    ]);
    const built = await Zip.buildSessionInputsFromZipBytes(zip, { render: derivationOnly, inflateRaw, generatedAt: "T" });
    expect(Object.keys(built.sessions[0].attachments)).toEqual(["attachments/utterance_1.wav"]);
  });

  test("attachments too large to materialize stay in the bundle instead of decompressing into the tab", async () => {
    // Every selected entry is inflated up front, before anything is opened, so the byte budget —
    // not just the 200-ref count ceiling — is what keeps a huge archive from landing in memory on
    // load. Squeezed to 3 bytes here so the fixture's own 4-byte wav is the one that doesn't fit.
    const render = { ...derivationOnly, ATTACHMENT_MATERIALIZE_MAX_TOTAL_BYTES: 3 };
    const built = await Zip.buildSessionInputsFromZipBytes(eventsZip(), { render, inflateRaw, generatedAt: "T" });
    expect(built.sessions[0].attachments).toBeNull();

    // Room for it again, and it materializes as before.
    const roomy = { ...derivationOnly, ATTACHMENT_MATERIALIZE_MAX_TOTAL_BYTES: 4 };
    const fits = await Zip.buildSessionInputsFromZipBytes(eventsZip(), { render: roomy, inflateRaw, generatedAt: "T" });
    expect(Object.keys(fits.sessions[0].attachments)).toEqual(["attachments/utterance_1.wav"]);
  });

  test("the single-session HTML branch hands the renderer everything the multi-session one does", async () => {
    // buildReportHtmlFromZipBytes picks buildRunReportHtml for a lone session and buildMultiReportHtml
    // otherwise. The single-session wrapper takes each field by name, so anything the composition
    // added and this call site did not list is dropped for one-session archives ONLY — which is
    // every CI results zip, and invisible in a multi-session test.
    const captured: { input?: any } = {};
    const built = await Zip.buildReportHtmlFromZipBytes(eventsZip(), {
      render: { ...derivationOnly, buildRunReportHtml: (input: unknown) => { captured.input = input; return "<html>SINGLE</html>"; } },
      inflateRaw,
      generatedAt: "T",
    });
    expect(built.html).toBe("<html>SINGLE</html>");
    expect(captured.input.events?.length).toBe(1);
    expect(Object.keys(captured.input.attachments)).toEqual(["attachments/utterance_1.wav"]);
  });

  test("only the zip viewer keeps attachment object URLs; every other document strips them", async () => {
    // The HTML the zip viewer builds goes straight into a same-origin iframe on the page that minted
    // these blob: URLs, so they resolve. A downloaded document outlives that page, so the default
    // must stay "strip" — a preserved blob: there badges the attachment as embedded and then opens
    // to nothing.
    const captured: { input?: any } = {};
    const render = { ...derivationOnly, buildRunReportHtml: (input: unknown) => { captured.input = input; return "<html>SINGLE</html>"; } };
    await Zip.buildReportHtmlFromZipBytes(eventsZip(), { render, inflateRaw, generatedAt: "T" });
    expect(captured.input.keepAttachmentObjectUrls).toBe(false);
    await Zip.buildReportHtmlFromZipBytes(eventsZip(), { render, inflateRaw, generatedAt: "T", keepAttachmentObjectUrls: true });
    expect(captured.input.keepAttachmentObjectUrls).toBe(true);
  });

  test("hands back every object URL it minted, so archive after archive is not pinned in memory", async () => {
    // An object URL pins its Blob for the life of the DOCUMENT, and the zip screen replaces one
    // report with another in place. Both producers have to be swept or the untouched one leaks
    // exactly as before: the recording clip, and the attachment map (one URL per materialized media
    // file, so an archive of audio pins far more here than the single clip does).
    const built = await Zip.buildSessionInputsFromZipBytes(eventsZip(), { render: derivationOnly, inflateRaw, generatedAt: "T" });
    expect(Zip.sessionObjectUrls(built.sessions)).toEqual(Object.values(built.sessions[0].attachments));

    const swept = Zip.sessionObjectUrls([
      { videoClip: { url: "blob:clip" }, attachments: { "a.wav": "blob:a", "b.wav": "blob:a" } },
      // Not ours to revoke: a hosted mp4, a /static link and a data: embed were minted elsewhere.
      { videoClip: { url: "https://cdn.test/run.mp4" }, attachments: { "c.wav": "/static/c.wav", "d.wav": "data:audio/wav;base64,AAAA" } },
      // Shapes a malformed or clipless payload takes; none of them may throw.
      { attachments: null }, { videoClip: null }, {}, null,
    ]);
    expect(swept).toEqual(["blob:clip", "blob:a"]);
    expect(Zip.sessionObjectUrls(null)).toEqual([]);
  });

  test("a session without events carries neither events nor attachments", async () => {
    const zip = buildZip([{ name: SESSION_ID + "/001_Log.json", text: JSON.stringify(startedLog()) }]);
    const built = await Zip.buildSessionInputsFromZipBytes(zip, { render: derivationOnly, inflateRaw, generatedAt: "T" });
    expect(built.sessions[0].events).toBeNull();
    expect(built.sessions[0].attachments).toBeNull();
  });

  test("an older renderer without the events pipeline degrades to an event-less report", async () => {
    const { buildEventStream: _b, collectStreamAttachmentRefs: _c, ...older } = derivationOnly;
    const built = await Zip.buildSessionInputsFromZipBytes(eventsZip(), { render: older, inflateRaw, generatedAt: "T" });
    expect(built.sessions[0].events).toBeNull();
    expect(built.sessions[0].attachments).toBeNull();

    // The read budgets are policy too: a bundle missing either one decodes no stream rather than
    // inflating archive entries under a limit this pipeline made up for itself.
    expect(REPORT_DERIVE.MAX_EVENT_STREAM_BYTES).toBeGreaterThan(0);
    expect(REPORT_DERIVE.MAX_EVENT_STREAMS_TOTAL_CHARS).toBeGreaterThan(0);
    for (const missing of ["MAX_EVENT_STREAM_BYTES", "MAX_EVENT_STREAMS_TOTAL_CHARS"]) {
      const { [missing]: _dropped, ...noBudget } = derivationOnly as Record<string, unknown>;
      const capless = await Zip.buildSessionInputsFromZipBytes(eventsZip(), { render: noBudget, inflateRaw, generatedAt: "T" });
      expect(capless.sessions[0].events).toBeNull();
    }
  });

  test("the attachment policy comes from the renderer, and a bundle missing it materializes nothing", async () => {
    // The MIME rule, the ceilings and the path rule are defined once, in run-report-events.ts, and reach
    // this pipeline over the same collaborator channel as the detector — REPORT_DERIVE carries them,
    // which is what the passing test above already proves. A bundle too old to export them must
    // decode its events and materialize NO bytes rather than fall back on a second copy of limits
    // that could disagree with the one the other surfaces enforce.
    expect(REPORT_DERIVE.ATTACHMENT_MIME).toBeInstanceOf(RegExp);
    expect(REPORT_DERIVE.MAX_ATTACHMENTS_PER_SESSION).toBeGreaterThan(0);
    expect(REPORT_DERIVE.ATTACHMENT_MATERIALIZE_MAX_TOTAL_BYTES).toBeGreaterThan(0);
    expect(typeof REPORT_DERIVE.isSafeSessionRelativePath).toBe("function");
    // Each policy piece on its own: dropping ANY one of them has to stop materialization, or a
    // bundle missing just that piece silently runs without it.
    for (const missing of ["ATTACHMENT_MIME", "MAX_ATTACHMENTS_PER_SESSION", "ATTACHMENT_MATERIALIZE_MAX_TOTAL_BYTES", "isSafeSessionRelativePath"]) {
      const { [missing]: _dropped, ...noPolicy } = derivationOnly as Record<string, unknown>;
      const built = await Zip.buildSessionInputsFromZipBytes(eventsZip(), { render: noPolicy, inflateRaw, generatedAt: "T" });
      expect(built.sessions[0].events).not.toBeNull();
      expect(built.sessions[0].attachments).toBeNull();
    }
  });
});

describe("finding the run's recording in the archive", () => {
  const CAPTURE_META = (artifacts: unknown[]) => JSON.stringify({ artifacts });

  test("a VIDEO artifact names its own file and carries the recorder's bookends", () => {
    const meta = CAPTURE_META([
      { filename: "device.log", type: "LOGCAT", startTimestampMs: 100, endTimestampMs: 900 },
      { filename: "video.mp4", type: "VIDEO", startTimestampMs: 200, endTimestampMs: 800 },
    ]);
    expect(Zip.videoArtifactFrom(meta, ["device.log", "video.mp4"]))
      .toEqual({ fileName: "video.mp4", startMs: 200, endMs: 800 });
  });

  test("a VIDEO_FRAMES artifact lends its bookends to the archive's playable file", () => {
    // This is what a real iOS run writes: the artifact names the sprite SHEET, which no element can
    // play, but it was cut from the recording and shares its window.
    const meta = CAPTURE_META([
      { filename: "video_sprites.webp", type: "VIDEO_FRAMES", startTimestampMs: 1_000, endTimestampMs: 2_000 },
    ]);
    expect(Zip.videoArtifactFrom(meta, ["video_sprites.webp", "video.mp4", "shot.webp"]))
      .toEqual({ fileName: "video.mp4", startMs: 1_000, endMs: 2_000 });
    // Sprites but no video file: nothing to play.
    expect(Zip.videoArtifactFrom(meta, ["video_sprites.webp"])).toBeNull();
  });

  test("a VIDEO artifact wins over a VIDEO_FRAMES one listed before it", () => {
    const meta = CAPTURE_META([
      { filename: "video_sprites.webp", type: "VIDEO_FRAMES", startTimestampMs: 1_000, endTimestampMs: 2_000 },
      { filename: "capture.webm", type: "VIDEO", startTimestampMs: 1_100, endTimestampMs: 2_100 },
    ]);
    expect(Zip.videoArtifactFrom(meta, ["video_sprites.webp", "capture.webm"]))
      .toEqual({ fileName: "capture.webm", startMs: 1_100, endMs: 2_100 });
  });

  test("no bookends means no clip: a recording that can't be placed on the clock is unusable", () => {
    // Without a window there is nothing to map an instant through, so this is not a video the
    // replay can show beside other devices — it is a file.
    expect(Zip.videoArtifactFrom(CAPTURE_META([{ filename: "video.mp4", type: "VIDEO" }]), ["video.mp4"])).toBeNull();
    expect(Zip.videoArtifactFrom(CAPTURE_META([
      { filename: "video.mp4", type: "VIDEO", startTimestampMs: 500, endTimestampMs: 500 },
    ]), ["video.mp4"])).toBeNull();
  });

  test("no artifacts, unparseable metadata, and logcat-only runs all yield nothing", () => {
    expect(Zip.videoArtifactFrom("{}", ["video.mp4"])).toBeNull();
    expect(Zip.videoArtifactFrom("not json", ["video.mp4"])).toBeNull();
    // The iPhone lane of a real 5-device run looks exactly like this — it recorded logs, not video.
    expect(Zip.videoArtifactFrom(CAPTURE_META([
      { filename: "device.log", type: "LOGCAT", startTimestampMs: 1, endTimestampMs: 2 },
    ]), ["device.log"])).toBeNull();
  });

  test("only known container extensions count as playable", () => {
    expect(Zip.videoMimeType("video.mp4")).toBe("video/mp4");
    expect(Zip.videoMimeType("capture.webm")).toBe("video/webm");
    expect(Zip.videoMimeType("video_sprites.webp")).toBeNull();
    expect(Zip.videoMimeType("shot.png")).toBeNull();
  });

  test("a session with no capture metadata reports no clip", async () => {
    const dir = SESSION_ID + "/";
    const zip = buildZip([
      { name: dir + "001_TrailblazeSessionStatusChangeLog.json", text: JSON.stringify(startedLog()) },
      { name: dir + "video.mp4", data: new Uint8Array([9, 9]) },
    ]);
    const [session] = await Zip.loadZipSessions(zip, { inflateRaw });
    // A video file alone is not enough — the bookends live in capture_metadata.json.
    expect(await Zip.sessionVideoClip(zip, session, { inflateRaw })).toBeNull();
  });
});
