// Behavior tests for the driver's payload-diet helpers (run-report-cli.ts): the screenshot
// recompression budget (with its no-ffmpeg / bad-image fallbacks) and the device/network log
// gzip packing. The viewer-side halves of these contracts (lazy inflation, identical rendered
// text) live in ../trailrunner/web/app/run-report-core.test.ts.
//
// Run: `bun test run-report-cli.test.ts` from this directory.
import { afterAll, describe, expect, test } from "bun:test";
import { spawnSync } from "child_process";
import { mkdtempSync, rmSync, writeFileSync } from "fs";
import { tmpdir } from "os";
import { join } from "path";
import { gunzipSync } from "zlib";
import {
  formatterContext,
  isRemoteScreenshot,
  packDeviceLog,
  packHierarchies,
  packLlmMessages,
  packNetwork,
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
