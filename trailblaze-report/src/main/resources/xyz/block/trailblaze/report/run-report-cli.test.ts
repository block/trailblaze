// Behavior tests for the driver's payload-diet helpers (run-report-cli.ts): the screenshot
// recompression budget (with its no-ffmpeg / bad-image fallbacks) and the device/network log
// gzip packing. The viewer-side halves of these contracts (lazy inflation, identical rendered
// text) live in ../trailrunner/web/app/run-report-core.test.ts.
//
// Run: `bun test run-report-cli.test.ts` from this directory.
import { afterAll, describe, expect, test } from "bun:test";
import { spawnSync } from "child_process";
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "fs";
import { tmpdir } from "os";
import { basename, join } from "path";
import { gunzipSync } from "zlib";
import { isSelectorAnalyzableTree } from "../trailrunner/web/app/run-report-selectors";
import { MAX_ATTACHMENTS_PER_SESSION } from "./run-report-events";
import {
  anyAnalyzableHierarchy,
  formatterContext,
  isRemoteScreenshot,
  isSafeSessionRelativePath,
  localShotUrl,
  packDeviceLog,
  packHierarchies,
  packLlmMessages,
  packNetwork,
  packSelectorEngine,
  readAttachments,
  readVideo,
  remoteShotValue,
  screenshotDataUri,
} from "./run-report-cli";

const MISSING_FFMPEG = "definitely-not-an-ffmpeg-binary";
const ffmpegPresent = (() => {
  try {
    return spawnSync("ffmpeg", ["-version"], { stdio: "ignore" }).status === 0;
  } catch {
    return false;
  }
})();

const sessionDir = mkdtempSync(join(tmpdir(), "tb-report-cli-test-"));
afterAll(() => rmSync(sessionDir, { recursive: true, force: true }));

/** Base64 payload of a data URI, decoded. */
function bytesOf(uri: string): Buffer {
  return Buffer.from(uri.split(",", 2)[1], "base64");
}

/** Write a device-resolution PNG of random noise (incompressible, so realistically large). */
function writeLargePng(name: string): Buffer {
  const raw = join(sessionDir, "raw.bin");
  const w = 1170;
  const h = 2400;
  writeFileSync(raw, require("crypto").randomBytes(w * h * 3));
  const res = spawnSync("ffmpeg", [
    "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", `${w}x${h}`,
    "-i", raw, "-frames:v", "1", join(sessionDir, name),
  ], { stdio: "ignore" });
  expect(res.status).toBe(0);
  return require("fs").readFileSync(join(sessionDir, name));
}

describe("screenshot recompression budget", () => {
  test.skipIf(!ffmpegPresent)("a large screenshot is re-encoded to a much smaller bounded JPEG", () => {
    const original = writeLargePng("big.png");
    expect(original.length).toBeGreaterThan(100 * 1024); // over the recompression threshold
    const uri = screenshotDataUri(sessionDir, "big.png");
    expect(uri).toStartWith("data:image/jpeg;base64,");
    expect(bytesOf(uri!).length).toBeLessThan(original.length / 2);
  });

  test.skipIf(!ffmpegPresent)("a screenshot ffmpeg cannot decode falls back to the original bytes", () => {
    const junk = Buffer.alloc(200 * 1024, 7); // .png extension, not a decodable image
    writeFileSync(join(sessionDir, "corrupt.png"), junk);
    const uri = screenshotDataUri(sessionDir, "corrupt.png");
    expect(uri).toBe(`data:image/png;base64,${junk.toString("base64")}`);
  });

  test("without ffmpeg the original bytes are inlined unchanged", () => {
    const bytes = Buffer.alloc(200 * 1024, 9);
    writeFileSync(join(sessionDir, "no-ffmpeg.png"), bytes);
    const uri = screenshotDataUri(sessionDir, "no-ffmpeg.png", MISSING_FFMPEG);
    expect(uri).toBe(`data:image/png;base64,${bytes.toString("base64")}`);
  });

  test("a screenshot already under the budget skips recompression entirely", () => {
    const bytes = Buffer.alloc(40 * 1024, 3); // e.g. an already-compressed WEBP
    writeFileSync(join(sessionDir, "small.webp"), bytes);
    // Even a broken ffmpeg wouldn't matter: under the threshold no subprocess is involved.
    const uri = screenshotDataUri(sessionDir, "small.webp", MISSING_FFMPEG);
    expect(uri).toBe(`data:image/webp;base64,${bytes.toString("base64")}`);
  });

  test("a missing screenshot file stays null", () => {
    expect(screenshotDataUri(sessionDir, "does-not-exist.png")).toBeNull();
  });
});

// Device-farm legs rewrite screenshotFile on driver logs to an absolute artifact URL and leave only
// final_screenshot on disk. Joining such a value onto sessionDir yields a nonsense path, which is
// how a farm report ended up rendering one screenshot; these values go to the viewer as URLs.
describe("remote (device-farm) screenshots", () => {
  test("an absolute artifact URL is recognized, a session-dir filename is not", () => {
    expect(isRemoteScreenshot("https://artifacts.example.com/?bucket=b&key=shot.webp")).toBe(true);
    expect(isRemoteScreenshot("http://artifacts.example.com/shot.webp")).toBe(true);
    expect(isRemoteScreenshot("session_1785221434011.webp")).toBe(false);
  });

  test("a remote screenshot is passed through as a URL, never read off disk", () => {
    const url = "https://artifacts.example.com/?bucket=b&key=shot.webp";
    expect(remoteShotValue(url)).toBe(url);
  });

  test("existing %xx escapes in a farm URL survive untouched", () => {
    // A farm artifact key is a percent-encoded PATH, so the URL arrives already escaped. Re-encoding
    // the `%` turns %2F into %252F and the artifact host answers 403 for the wrong key.
    const farm =
      "https://example.lambda-url.us-east-2.on.aws/?bucket=b&key=artifacts%2Fsession-1%2F0%2Fshot.webp";
    const value = remoteShotValue(farm);
    expect(value).toBe(farm);
    expect(value).not.toContain("%252F");
  });

  test("an already-escaped space is not re-escaped", () => {
    expect(remoteShotValue("https://h/?key=a%20b.webp")).toBe("https://h/?key=a%20b.webp");
  });

  test("a remote screenshot URL cannot break out of src=\"…\"", () => {
    // The viewer interpolates a shot into src="…" unescaped, so a quote in the URL would escape the
    // attribute and inject markup into the report page.
    const value = remoteShotValue(`https://h/?key=a" onerror="alert(1)`);
    expect(value).not.toContain('"');
  });

  test("a value the URL parser rejects still cannot break out of src=\"…\"", () => {
    // No host to parse, so normalization is impossible — the quote must still be neutralized.
    expect(remoteShotValue(`http://[bad" onerror="x`)).not.toContain('"');
  });

});

// Linked-image mode: instead of base64-embedding an on-disk screenshot, the report references it at
// <base><sessionId>/<file> — the same key the legacy WASM report hands transformImageUrl, so the
// daemon's /static/ route and the CI report step's uploaded artifact paths both already serve it.
describe("linked image URLs", () => {
  test("the daemon's /static/ base addresses a screenshot the way that route serves it", () => {
    expect(localShotUrl("/static/", "my_session_123", "shot_1785221434011.webp"))
      .toBe("/static/my_session_123/shot_1785221434011.webp");
  });

  test("an empty base leaves a document-relative reference", () => {
    // CI's case: the browser resolves it against the report's own artifact URL, which is the same
    // arithmetic the WASM report's hosted-report transformImageUrl hook performs.
    expect(localShotUrl("", "session-a", "shot.webp")).toBe("session-a/shot.webp");
  });

  test("spaces and other unsafe characters in either segment are escaped", () => {
    const url = localShotUrl("/static/", "my session", "shot 1.webp");
    expect(url).toBe("/static/my%20session/shot%201.webp");
    expect(url).not.toContain(" ");
  });

  test("a linked image URL cannot break out of src=\"…\"", () => {
    // Same unescaped-attribute contract remoteShotValue is held to: the viewer interpolates the
    // value into src="…" directly.
    expect(localShotUrl("/static/", `s" onerror="alert(1)`, `f" onerror="alert(1).webp`))
      .not.toContain('"');
  });

  test("a linked image URL cannot break out of background-image:url('…')", () => {
    // Sprite sheets are interpolated into a SINGLE-quoted CSS url(), also unescaped — and
    // encodeURIComponent leaves ' intact, so it has to be encoded explicitly.
    const url = localShotUrl("/static/", `s' onload='alert(1)`, `video_sprites'.webp`);
    expect(url).not.toContain("'");
    expect(url).toContain("%27");
  });

  test("a name containing a slash stays a path instead of becoming %2F", () => {
    // %2F is not resolved by a static file route, so encoding the whole key would silently 404.
    expect(localShotUrl("/static/", "sess", "sub dir/shot.webp")).toBe("/static/sess/sub%20dir/shot.webp");
  });

  test("a base URL missing its trailing slash is still a base, not a prefix", () => {
    expect(localShotUrl("/static", "sess", "shot.webp")).toBe("/static/sess/shot.webp");
  });
});

// Attachment refs (AttachmentRef) embedded in event payloads, resolved into the payload's
// attachments map. Detection is run-report-events' (parity-fixture-locked); these pin the driver's
// resolution: linked vs embedded, the media-type and size gates, and the path-safety refusal.
describe("attachments referenced by event streams", () => {
  const attachDir = mkdtempSync(join(tmpdir(), "tb-report-attach-test-"));
  afterAll(() => rmSync(attachDir, { recursive: true, force: true }));
  require("fs").mkdirSync(join(attachDir, "attachments"));
  writeFileSync(join(attachDir, "attachments", "tone.wav"), Buffer.from([1, 2, 3, 4]));
  writeFileSync(join(attachDir, "attachments", "report.pdf"), Buffer.from([9]));
  writeFileSync(join(attachDir, "attachments", "huge.wav"), Buffer.alloc(64));

  const ref = (path: string, over: Record<string, unknown> = {}) =>
    ({ $attachment: true, path, mimeType: "audio/wav", sizeBytes: 4, ...over });
  const streamsOf = (...refs: unknown[]): EventStream[] => [{
    name: "s",
    total: refs.length,
    truncated: false,
    events: refs.map((r, i) => ({ t: i, d: JSON.stringify({ ref: r }) })),
  }];

  test("embed mode inlines a referenced media file as a data URI of its declared type", () => {
    const map = readAttachments(attachDir, streamsOf(ref("attachments/tone.wav", { mimeType: "audio/WAV" })), "sess", null);
    expect(map).toEqual({
      "attachments/tone.wav": `data:audio/wav;base64,${Buffer.from([1, 2, 3, 4]).toString("base64")}`,
    });
  });

  test("embedding stops at the aggregate budget, leaving the rest as bundle-only notes", () => {
    // The per-file cap and the count ceiling still permit ~137 MiB of base64 in a file that has to
    // stay openable and that Share posts to a route refusing HTML over 64 MiB. Charged in ENCODED
    // bytes, so the budget is directly comparable with the HTML it produces.
    const uri = `data:audio/wav;base64,${Buffer.from([1, 2, 3, 4]).toString("base64")}`;
    const streams = streamsOf(ref("attachments/tone.wav"), ref("attachments/huge.wav", { sizeBytes: 64 }));
    // Room for exactly one: the second keeps its in-bundle note rather than pushing the file over.
    expect(readAttachments(attachDir, streams, "sess", null, 1024, { remaining: uri.length })).toEqual({ "attachments/tone.wav": uri });
    // With room for both, both embed — so the omission above is the budget, not the second file.
    expect(Object.keys(readAttachments(attachDir, streams, "sess", null, 1024, { remaining: uri.length * 10 })!).sort())
      .toEqual(["attachments/huge.wav", "attachments/tone.wav"]);
  });

  test("the budget is spent ACROSS the sessions of one report, not granted to each", () => {
    // A standalone report holds every session, and the ceiling exists to keep that FILE shareable.
    // Re-seeding per session is the bug this pins: ten sessions would each embed up to the ceiling
    // and the file would blow through it tenfold while every individual session looked compliant.
    const uri = `data:audio/wav;base64,${Buffer.from([1, 2, 3, 4]).toString("base64")}`;
    const streams = streamsOf(ref("attachments/tone.wav"));
    const shared = { remaining: uri.length };

    // First session spends the whole budget.
    expect(readAttachments(attachDir, streams, "sess-1", null, 1024, shared)).toEqual({ "attachments/tone.wav": uri });
    expect(shared.remaining).toBe(0);
    // Second session finds nothing left, and its attachment keeps the in-bundle note.
    expect(readAttachments(attachDir, streams, "sess-2", null, 1024, shared)).toBeNull();
  });

  test("main() hands every session the SAME budget object", () => {
    // The tests above prove readAttachments spends a budget it is GIVEN across calls; this pins the
    // wiring that gives it one. main() isn't reachable from here — it loads the transpiled renderer
    // artifact, which this suite deliberately does not stage — so the call site is asserted in the
    // source instead. Passing a fresh `{ remaining: ATTACHMENT_EMBED_MAX_TOTAL_BYTES }` per session
    // typechecks, passes every other test, and silently restores the per-session budget.
    const src = readFileSync(join(import.meta.dir, "run-report-cli.ts"), "utf8");
    expect(src).toContain("input.attachmentInlineMaxBytes ?? ATTACHMENT_INLINE_MAX_BYTES, embedBudget)");
    // Declared once, above the per-session map that consumes it.
    expect(src.split("const embedBudget = { remaining: ATTACHMENT_EMBED_MAX_TOTAL_BYTES }").length - 1).toBe(1);
  });

  test("an attachment that stats but cannot be read spends none of the shared budget", () => {
    // statSync succeeds on a directory; readFileSync throws EISDIR into the catch. Charging before
    // the read would spend shared capacity on bytes that never reach the HTML — and because the
    // budget now spans the whole report, that capacity is taken from readable attachments in LATER
    // sessions, which would then show the in-bundle note while the file sat under the cap.
    mkdirSync(join(attachDir, "attachments", "adirectory.wav"), { recursive: true });
    const uri = `data:audio/wav;base64,${Buffer.from([1, 2, 3, 4]).toString("base64")}`;
    // Headroom, deliberately: a budget tight enough to reject the directory on its ESTIMATE would
    // never reach the read, and would pass whether the charge happens before or after it.
    const budget = { remaining: 10_000 };

    // The unreadable one is attempted first and must leave the budget untouched...
    expect(readAttachments(attachDir, streamsOf(ref("attachments/adirectory.wav")), "sess-1", null, 1024, budget)).toBeNull();
    expect(budget.remaining).toBe(10_000);
    // ...so the readable attachment behind it still fits.
    expect(readAttachments(attachDir, streamsOf(ref("attachments/tone.wav")), "sess-2", null, 1024, budget))
      .toEqual({ "attachments/tone.wav": uri });
    expect(budget.remaining).toBe(10_000 - uri.length);
  });

  test("the budget is charged the exact length of the URI that lands in the HTML", () => {
    // The charge is computed from the file size before the read, so it has to agree with what the
    // read actually produces — a charge that drifted from the emitted length would make the whole
    // budget an approximation of the thing it exists to bound.
    const uri = `data:audio/wav;base64,${Buffer.from([1, 2, 3, 4]).toString("base64")}`;
    const budget = { remaining: 10_000 };
    const map = readAttachments(attachDir, streamsOf(ref("attachments/tone.wav")), "sess", null, 1024, budget)!;
    expect(map["attachments/tone.wav"]).toBe(uri);
    expect(10_000 - budget.remaining).toBe(uri.length);
  });

  test("an attachment named like an Object.prototype member still resolves", () => {
    // A single segment is a legal path under the shared rule, so `__proto__` is a legal attachment
    // name — and on a plain object `attachments['__proto__'] = uri` goes through the prototype
    // setter and records nothing.
    writeFileSync(join(attachDir, "__proto__"), Buffer.from([7, 7]));
    const map = readAttachments(attachDir, streamsOf(ref("__proto__", { sizeBytes: 2 })), "sess", null)!;
    expect(Object.keys(map)).toEqual(["__proto__"]);
    expect(map["__proto__"]).toBe(`data:audio/wav;base64,${Buffer.from([7, 7]).toString("base64")}`);
  });

  test("linked mode points at the static tree with separators intact, media or not", () => {
    const map = readAttachments(
      attachDir,
      streamsOf(ref("attachments/tone.wav"), ref("attachments/report.pdf", { mimeType: "application/pdf" })),
      "sess",
      "/static/",
    );
    // A linked report downloads nothing, so nothing forces the media-type gate: the browser can
    // navigate to any linked file.
    expect(map).toEqual({
      "attachments/tone.wav": "/static/sess/attachments/tone.wav",
      "attachments/report.pdf": "/static/sess/attachments/report.pdf",
    });
  });

  test("embed mode leaves out non-media types and files past the inline cap", () => {
    const map = readAttachments(
      attachDir,
      streamsOf(
        ref("attachments/report.pdf", { mimeType: "application/pdf" }),
        ref("attachments/huge.wav", { sizeBytes: 64 }),
        ref("attachments/tone.wav"),
      ),
      "sess",
      null,
      16, // inline cap below huge.wav's 64 bytes
    );
    expect(Object.keys(map!)).toEqual(["attachments/tone.wav"]);
  });

  test("a traversal-shaped path and a missing file are refused; no refs at all yields null", () => {
    const escape = join(attachDir, "..", basename(attachDir), "attachments", "tone.wav");
    expect(readAttachments(attachDir, streamsOf(
      ref("../elsewhere.wav"),
      ref("/etc/passwd"),
      ref("attachments/../../" + basename(attachDir) + "/attachments/tone.wav"),
      ref(escape),
      ref("attachments/never-written.wav"),
    ), "sess", null)).toBeNull();
    expect(readAttachments(attachDir, streamsOf(), "sess", null)).toBeNull();
    expect(readAttachments(attachDir, null, "sess", null)).toBeNull();
  });

  test("a symlink pointing out of the session is refused in both modes", () => {
    // The lexical path rule passes `attachments/escape.wav` — it cannot see where the entry
    // points. Without the realpath check, readFileSync follows it and embeds host bytes under
    // the ref's own declared audio MIME.
    const outside = join(attachDir, "..", `outside-${basename(attachDir)}.wav`);
    writeFileSync(outside, Buffer.from([7, 7, 7, 7]));
    try {
      require("fs").symlinkSync(outside, join(attachDir, "attachments", "escape.wav"));
    } catch { return; } // a filesystem without symlinks has nothing to test here
    const streams = streamsOf(ref("attachments/escape.wav"));
    expect(readAttachments(attachDir, streams, "sess", null)).toBeNull();
    expect(readAttachments(attachDir, streams, "sess", "/static/")).toBeNull();
    rmSync(outside, { force: true });
    rmSync(join(attachDir, "attachments", "escape.wav"), { force: true });
  });

  test("unsafe paths do not consume per-session cap slots", () => {
    // The cap applies to paths that could resolve at all — run-payload.js and zip-report-core.js
    // filter then slice, and this driver has to pick the same 200 or the three surfaces carry
    // different files for the same session. Here MAX_ATTACHMENTS_PER_SESSION traversal refs come
    // first; slicing before the safety filter would spend the whole cap on them.
    const decoys = Array.from({ length: MAX_ATTACHMENTS_PER_SESSION }, (_, i) => ref(`../decoy-${i}.wav`));
    const map = readAttachments(attachDir, streamsOf(...decoys, ref("attachments/tone.wav")), "sess", "/static/");
    expect(map).toEqual({ "attachments/tone.wav": "/static/sess/attachments/tone.wav" });
  });

  test("isSafeSessionRelativePath admits exactly the paths that stay inside the session dir", () => {
    expect(isSafeSessionRelativePath("attachments/tone.wav")).toBe(true);
    expect(isSafeSessionRelativePath("a/b/c.png")).toBe(true);
    expect(isSafeSessionRelativePath("")).toBe(false);
    expect(isSafeSessionRelativePath("/abs.wav")).toBe(false);
    expect(isSafeSessionRelativePath("..")).toBe(false);
    expect(isSafeSessionRelativePath("a/../b.wav")).toBe(false);
    expect(isSafeSessionRelativePath("a//b.wav")).toBe(false);
    expect(isSafeSessionRelativePath("a/./b.wav")).toBe(false);
    expect(isSafeSessionRelativePath("a\\b.wav")).toBe(false);
    expect(isSafeSessionRelativePath("a/\0.wav")).toBe(false);
  });
});

// The sprite sheet is the single largest blob a session contributes, and it takes the same
// embedded-vs-linked switch as the step screenshots — but through readVideo's own resolver rather
// than the shots loop, so it needs its own coverage.
describe("video sprite sheets", () => {
  const videoDir = mkdtempSync(join(tmpdir(), "tb-report-sprite-test-"));
  afterAll(() => rmSync(videoDir, { recursive: true, force: true }));

  const SPRITE = "video_sprites.webp";
  writeFileSync(join(videoDir, SPRITE), Buffer.alloc(2048, 5));
  writeFileSync(
    join(videoDir, "video_sprites.txt"),
    ["fps=2", "frames=4", "height=720", "frameWidth=332", "columns=2", "rows=2", "uniqueFrames=4",
      "sheets=1", "frameMap=0,1,2,3", "restamped=false"].join("\n"),
  );
  writeFileSync(
    join(videoDir, "capture_metadata.json"),
    JSON.stringify({ artifacts: [{ filename: SPRITE, type: "VIDEO_FRAMES", startTimestampMs: 1000, endTimestampMs: 3000 }] }),
  );
  const logs = [{ timestamp: new Date(1500).toISOString() }, { timestamp: new Date(2500).toISOString() }] as never;

  test("by default the sheet is embedded as a data URI", () => {
    const video = readVideo(videoDir, logs, 4);
    expect(video).not.toBeNull();
    expect(video!.sprites[0].uri).toStartWith("data:image/webp;base64,");
  });

  test("with a linked resolver the sheet becomes a URL and no bytes are embedded", () => {
    const video = readVideo(videoDir, logs, 4, (path) => localShotUrl("/static/", "sess-1", basename(path)));
    expect(video).not.toBeNull();
    expect(video!.sprites[0].uri).toBe("/static/sess-1/video_sprites.webp");
    expect(video!.sprites[0].uri).not.toContain("base64");
  });
});

describe("device/network log gzip packing", () => {
  test("a small device log stays plain text", () => {
    expect(packDeviceLog("I/Tag: ok")).toEqual({ deviceLog: "I/Tag: ok", deviceLogGz: null });
    expect(packDeviceLog(null)).toEqual({ deviceLog: null, deviceLogGz: null });
  });

  test("a large device log is embedded gzipped and round-trips", () => {
    const text = Array.from({ length: 5000 }, (_, i) => `I/Tag(${i}): line ${i}`).join("\n");
    const { deviceLog, deviceLogGz } = packDeviceLog(text);
    expect(deviceLog).toBeNull();
    expect(gunzipSync(Buffer.from(deviceLogGz!, "base64")).toString("utf8")).toBe(text);
    expect(deviceLogGz!.length).toBeLessThan(text.length / 2);
  });

  test("small network logs stay inline; large ones are embedded gzipped and round-trip", () => {
    const one = [{ method: "GET", statusCode: 200, durationMs: 5, urlPath: "/ok", phase: "RESPONSE_END" }];
    expect(packNetwork(one)).toEqual({ network: one, networkGz: null });
    expect(packNetwork(null)).toEqual({ network: null, networkGz: null });

    const many = Array.from({ length: 5000 }, (_, i) => ({ method: "GET", statusCode: 200, durationMs: i, urlPath: `/path/${i}`, phase: "RESPONSE_END" }));
    const { network, networkGz } = packNetwork(many);
    expect(network).toBeNull();
    expect(JSON.parse(gunzipSync(Buffer.from(networkGz!, "base64")).toString("utf8"))).toEqual(many);
  });
});

describe("LLM transcript gzip packing", () => {
  test("a small transcript stays inline", () => {
    const tx = { texts: ["You are an agent…", "Tap login"], calls: [[{ role: "system", t: 0 }, { role: "user", t: 1 }]] };
    expect(packLlmMessages(tx)).toEqual({ llmMessages: tx, llmMessagesGz: null });
    expect(packLlmMessages(null)).toEqual({ llmMessages: null, llmMessagesGz: null });
  });

  test("a large transcript is embedded gzipped and round-trips", () => {
    const texts = Array.from({ length: 40 }, (_, i) => `screen-state dump ${i} ` + "x".repeat(4000));
    const tx = { texts, calls: texts.map((_, i) => texts.slice(0, i + 1).map((_, t) => ({ role: "user", t }))) };
    const { llmMessages, llmMessagesGz } = packLlmMessages(tx);
    expect(llmMessages).toBeNull();
    expect(JSON.parse(gunzipSync(Buffer.from(llmMessagesGz!, "base64")).toString("utf8"))).toEqual(tx);
    expect(llmMessagesGz!.length).toBeLessThan(JSON.stringify(tx).length / 2);
  });
});

describe("view-hierarchy packing (UI Inspector side-channel)", () => {
  test("a small hierarchies map stays inline", () => {
    const map = { "2": { className: "android.widget.Button", text: "Login" } };
    expect(packHierarchies(map, 1)).toEqual({ hierarchies: map, hierarchiesGz: null });
  });

  test("a large hierarchies map is embedded gzipped and round-trips", () => {
    const map = { "2": { text: "node ".repeat(20_000) } };
    const { hierarchies, hierarchiesGz } = packHierarchies(map, 1);
    expect(hierarchies).toBeNull();
    expect(JSON.parse(gunzipSync(Buffer.from(hierarchiesGz!, "base64")).toString("utf8"))).toEqual(map);
  });

  test("a session with no captured hierarchies packs to nothing", () => {
    expect(packHierarchies(null, 0)).toEqual({ hierarchies: null, hierarchiesGz: null });
    expect(packHierarchies({}, 0)).toEqual({ hierarchies: null, hierarchiesGz: null });
  });
});

describe("selector-engine packing (UI Inspector suggestions)", () => {
  test("the real-sized bundle lands on the gz side and round-trips", () => {
    const code = `globalThis.TrailblazeSelectorEngine = {}; // ${"x".repeat(320 * 1024)}`;
    const packed = packSelectorEngine(code)!;
    expect(packed.js).toBeNull();
    expect(gunzipSync(Buffer.from(packed.gz!, "base64")).toString("utf8")).toBe(code);
  });

  test("a small payload stays inline; an absent bundle packs to nothing", () => {
    expect(packSelectorEngine("globalThis.TrailblazeSelectorEngine = {};"))
      .toEqual({ js: "globalThis.TrailblazeSelectorEngine = {};", gz: null });
    expect(packSelectorEngine(null)).toBeNull();
    expect(packSelectorEngine("")).toBeNull();
  });

  // Driven by the viewer's OWN predicate (the one the UI gates the suggestions section on), so a
  // report can never pay for an engine whose suggestions the inspector would refuse to render.
  test("the embed gate: only a session carrying an engine-analyzable hierarchy qualifies a report", () => {
    const tbNode = { nodeId: 1, driverDetail: { class: "androidAccessibility", className: "android.widget.FrameLayout" } };
    const legacy = { nodeId: 1, className: "android.widget.FrameLayout", children: [] };
    expect(anyAnalyzableHierarchy([{ "1": tbNode }], isSelectorAnalyzableTree)).toBe(true);
    expect(anyAnalyzableHierarchy([null, { "4": legacy }, { "7": tbNode }], isSelectorAnalyzableTree)).toBe(true);
    // A legacy-tree-only report (agent / MCP-sampling captures) opens an inspector that can never
    // show a suggestion — it gets no engine bytes.
    expect(anyAnalyzableHierarchy([{ "1": legacy }, { "2": legacy }], isSelectorAnalyzableTree)).toBe(false);
    expect(anyAnalyzableHierarchy([null, {}], isSelectorAnalyzableTree)).toBe(false);
    expect(anyAnalyzableHierarchy([], isSelectorAnalyzableTree)).toBe(false);
  });
});

describe("formatter context (size-budget gate)", () => {
  test("only an affirmative passed status enables the size budgets", () => {
    expect(formatterContext("passed", false)).toEqual({ sessionPassed: true });
    for (const status of ["failed", "cancelled", "running", "unknown", undefined]) {
      expect(formatterContext(status, false)).toEqual({ sessionPassed: false });
    }
  });

  test("fullEventPayloads (--full-report-payloads) disables the budgets even for passed sessions", () => {
    expect(formatterContext("passed", true)).toEqual({ sessionPassed: false });
    expect(formatterContext("failed", true)).toEqual({ sessionPassed: false });
  });
});
