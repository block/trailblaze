// Pure-logic tests for the shared live device mirror transport (app/live-device-stream.js). No
// browser, no DOM, no WebSocket — only the exported DOM-free helpers (h264NalHeaders / h264Codec /
// pickStreamMode / frameImageMime) that decide what to decode and which transport to open. The
// socket/decoder wiring is outside this pure helper suite; here we lock the DOM-free contracts.
//
// Run: `bun test app/live-device-stream.test.ts` from the web/ directory.
import { describe, expect, test } from "bun:test";
// live-device-stream.js dual-exports via module.exports; bun interops the CJS default import.
import Live from "./live-device-stream.js";

describe("h264NalHeaders", () => {
  test("finds the byte just past each Annex-B start code (3- and 4-byte)", () => {
    // 4-byte start code, NAL 0x67 (SPS), then a 3-byte start code, NAL 0x65 (IDR).
    const bytes = new Uint8Array([0, 0, 0, 1, 0x67, 0xaa, 0, 0, 1, 0x65, 0xbb]);
    const headers = Live.h264NalHeaders(bytes);
    expect(headers).toEqual([4, 9]);
    expect(bytes[headers[0]]).toBe(0x67);
    expect(bytes[headers[1]]).toBe(0x65);
  });

  test("returns no headers when there is no start code", () => {
    expect(Live.h264NalHeaders(new Uint8Array([1, 2, 3, 4, 5]))).toEqual([]);
  });
});

describe("h264Codec", () => {
  test("derives the avc1.PPCCLL string from the SPS profile/constraint/level bytes", () => {
    // 4-byte start code, SPS (0x67) with profile=0x42 constraints=0xC0 level=0x1F.
    const bytes = new Uint8Array([0, 0, 0, 1, 0x67, 0x42, 0xc0, 0x1f, 0x00]);
    expect(Live.h264Codec(bytes)).toBe("avc1.42C01F");
  });

  test("returns null when the access unit carries no SPS", () => {
    // Only an IDR NAL (0x65), no SPS (0x67).
    const bytes = new Uint8Array([0, 0, 0, 1, 0x65, 0x11, 0x22, 0x33]);
    expect(Live.h264Codec(bytes)).toBeNull();
  });
});

describe("pickStreamMode", () => {
  test("Android and iOS decode H.264 when WebCodecs is available", () => {
    expect(Live.pickStreamMode("ANDROID", true)).toBe("h264");
    expect(Live.pickStreamMode("IOS", true)).toBe("h264");
  });

  test("Android and iOS fall back to JPEG without WebCodecs", () => {
    expect(Live.pickStreamMode("ANDROID", false)).toBe("jpeg");
    expect(Live.pickStreamMode("IOS", false)).toBe("jpeg");
  });

  test("Web uses the CDP JPEG screencast regardless of WebCodecs", () => {
    expect(Live.pickStreamMode("WEB", true)).toBe("web");
    expect(Live.pickStreamMode("WEB", false)).toBe("web");
  });

  test("an unknown platform goes straight to the JPEG/poll fallback", () => {
    expect(Live.pickStreamMode("DESKTOP", true)).toBe("jpeg");
    expect(Live.pickStreamMode(undefined, true)).toBe("jpeg");
  });
});

describe("frameImageMime", () => {
  test("honors a valid image MIME reported by the server", () => {
    expect(Live.frameImageMime({ mime: "image/avif", screenshotBase64: "unknown" })).toBe("image/avif");
  });

  test("recognizes the image formats returned by device screenshot backends", () => {
    expect(Live.frameImageMime({ screenshotBase64: "/9j/example" })).toBe("image/jpeg");
    expect(Live.frameImageMime({ screenshotBase64: "iVBORexample" })).toBe("image/png");
    expect(Live.frameImageMime({ screenshotBase64: "UklGRexample" })).toBe("image/webp");
  });

  test("uses JPEG for malformed MIME values and unknown payloads", () => {
    expect(Live.frameImageMime({ mime: "text/html", screenshotBase64: "unknown" })).toBe("image/jpeg");
  });
});
