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

  test("groups entries by top-level session dir, ignoring nested tool artifacts", () => {
    const groups = Zip.groupEntriesBySession([
      { name: "sessionA/001_Log.json" },
      { name: "sessionA/shot.webp" },
      { name: "sessionA/in-process-scripted-tools/tool.js" },
      { name: "sessionB/001_Log.json" },
    ]);
    expect(groups.map((g: { sessionId: string }) => g.sessionId)).toEqual(["sessionA", "sessionB"]);
    expect(Object.keys(groups[0].byFileName).sort()).toEqual(["001_Log.json", "shot.webp"]);
  });

  test("treats a flat archive (no directories) as a single unnamed session", () => {
    const groups = Zip.groupEntriesBySession([{ name: "001_Log.json" }, { name: "shot.webp" }]);
    expect(groups.length).toBe(1);
    expect(groups[0].sessionId).toBe("");
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
    expect(meta.trailId).toBe("suites/suite_1/case_2");
    expect(meta.duration).toBe("1m 30s");
    expect(meta.cmd).toContain("./trailblaze run ");
    expect(meta.recordingYaml).toBe("- config: {}\n");
    expect(meta.generatedAt).toBe("test-time");
    expect(meta.error).toBeUndefined();
    expect(meta.selfHeal).toBeUndefined();
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

  function fakeRenderer(captured: { input?: unknown }) {
    return {
      extractTrace: () => [
        { screenshotFile: "shot_1.webp", label: "in-zip" },
        { screenshotFile: REMOTE, label: "remote" },
        { screenshotFile: null, label: "no shot" },
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
    ]);
    const captured: { input?: any } = {};
    const built = await Zip.buildReportHtmlFromZipBytes(zip, {
      render: fakeRenderer(captured), inflateRaw, generatedAt: "FIXED-TS",
    });

    expect(built.html).toBe("<html>SINGLE</html>");
    expect(built.sessions.length).toBe(1);
    expect(built.zipBytes).toBe(zip.length);
    // The trace's in-zip screenshot resolves to a data URI; a remote-URL screenshot passes through;
    // a null screenshotFile is skipped. This is the screenshot-gathering contract callers rely on.
    expect(captured.input.shots["shot_1.webp"]).toBe("data:image/webp;base64," + Buffer.from([1, 2, 3, 4]).toString("base64"));
    expect(captured.input.shots[REMOTE]).toBe(REMOTE);
    expect(Object.keys(captured.input.shots).sort()).toEqual([REMOTE, "shot_1.webp"]);
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
    // Deliberately NO buildRunReportHtml / buildMultiReportHtml: a shell embeds only the viewer
    // bundle, so this stage must never reach for them.
    const derivationOnly = {
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
