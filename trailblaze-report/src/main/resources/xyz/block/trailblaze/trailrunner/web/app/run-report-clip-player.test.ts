// Contracts for the recording fallback's WebM demuxer: the in-page player decodes exactly the
// frames and times it returns, so a wrong time here is a wrong frame on screen.
import { afterAll, beforeAll, describe, expect, test } from "bun:test";
import {
  frameIndexAt, keepRefusedPlays, keyframeBefore, parseWebm, registerClipBytes, unregisterClipBytes, watchClipElement,
} from "./run-report-clip-player";

// 32x48 VP9, one keyframe, frames at 0, 0.25, 1.0, 1.25, 1.5, 1.75 s — sparse and variable-rate
// like a damage-driven capture — with a declared 2 s Duration (ffmpeg's webm muxer).
const SPARSE_WEBM =
  "GkXfo59ChoEBQveBAULygQRC84EIQoKEd2VibUKHgQJChYECGFOAZwEAAAAAAAKpEU2bdLpNu4tTq4QVSalmU6yBoU27i1Or" +
  "hBZUrmtTrIHWTbuMU6uEElTDZ1OsggEyTbuMU6uEHFO7a1OsggKT7AEAAAAAAABZAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
  "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAVSalmsCrX" +
  "sYMPQkBNgIxMYXZmNjMuMS4xMDJXQYxMYXZmNjMuMS4xMDJEiYhAn0AAAAAAABZUrmvXrgEAAAAAAABO14EBc8WI7eO592fB" +
  "cZecgQAitZyDdW5kiIEAhoVWX1ZQOYOBASPjg4QO5rKA4JCwgSC6gTCagQJVsIRVuYEBVe6BAOwBAAAAAAAAAgAAElTDZ/5z" +
  "c59jwIBnyJlFo4dFTkNPREVSRIeMTGF2ZjYzLjEuMTAyc3PZY8CLY8WI7eO592fBcZdnyKRFo4dFTkNPREVSRIeXTGF2YzYz" +
  "LjEuMTAyIGxpYnZweC12cDlnyKFFo4hEVVJBVElPTkSHkzAwOjAwOjAyLjAwMDAwMDAwMAAfQ7Z1QNjngQCj5YEAAICCSYNC" +
  "AAHwAvYGOCQcGEIAACBAADUv//7MOFk8RauoWPHkYJJzG/PE1r+glcdmdu6PJVv//xOeo56yBuH/MZEacx57QEOJBWedAF8I" +
  "wSSFXAF/////TLX/////sUwSbMAAo5SBAPoAhgBAkpwoSUAAA3AAAE4KAKOUgQPoAIYAQJKcLErAAANwAABOCgCjlIEE4gCG" +
  "AECSnCxJwAADcAAATgoAo5SBBdwAhgBAkpwoSKAAA3AAAE4KAKOUgQbWAIYAQJKcJEeAAANwAABOCgAcU7trkbuPs4EAt4r3" +
  "gQHxggG18IED";
// The same packets remuxed with `-live 1`: unknown-size Segment, no Duration, as a streaming
// muxer writes a file it never finalized.
const LIVE_WEBM =
  "GkXfo59ChoEBQveBAULygQRC84EIQoKEd2VibUKHgQJChYECGFOAZwH/////////EU2bdKtNu4tTq4QVSalmU6yBoU27i1Or" +
  "hBZUrmtTrIHLTbuMU6uEElTDZ1OsggEY7AEAAAAAAABoAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
  "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAVSalmpSrX" +
  "sYMPQkBNgIxMYXZmNjMuMS4xMDJXQYxMYXZmNjMuMS4xMDIWVK5ryK4BAAAAAAAAP9eBAXPFiH0b/9NkRjZjnIEAIrWcg3Vu" +
  "ZIiBAIaFVl9WUDmDgQEj44OEDuaygOCQsIEguoEwmoECVbCEVbmBARJUw2fac3OfY8CAZ8iZRaOHRU5DT0RFUkSHjExhdmY2" +
  "My4xLjEwMnNztWPAi2PFiH0b/9NkRjZjZ8ikRaOHRU5DT0RFUkSHl0xhdmM2My4xLjEwMiBsaWJ2cHgtdnA5H0O2dUCW54EA" +
  "o+WBAACAgkmDQgAB8AL2BjgkHBhCAAAgQAA1L//+zDhZPEWrqFjx5GCScxvzxNa/oJXHZnbujyVb//8TnqOesgbh/zGRGnMe" +
  "e0BDiQVnnQBfCMEkhVwBf////0y1/////7FMEmzAAKOUgQD6AIYAQJKcKElAAANwAABOCgCjlIED6ACGAECSnCxKwAADcAAA" +
  "TgoAH0O2dcbnggTio5SBAAAAhgBAkpwsScAAA3AAAE4KAKOUgQD6AIYAQJKcKEigAANwAABOCgCjlIEB9ACGAECSnCRHgAAD" +
  "cAAATgoA";

const bytes = (b64: string) => new Uint8Array(Buffer.from(b64, "base64"));

describe("parseWebm", () => {
  test("reads the video track, its frames' times, and the declared duration", () => {
    const clip = parseWebm(bytes(SPARSE_WEBM));
    expect(clip).not.toBeNull();
    expect(clip.codec).toBe("vp09.00.10.08");
    expect([clip.width, clip.height]).toEqual([32, 48]);
    expect(clip.duration).toBeCloseTo(2, 3);
    expect(clip.frames.map((f) => f.t)).toEqual([0, 0.25, 1, 1.25, 1.5, 1.75]);
    expect(clip.frames.map((f) => f.key)).toEqual([true, false, false, false, false, false]);
    expect(clip.frames.every((f) => f.data.length > 0)).toBe(true);
  });

  test("reads a live-muxed file with unknown sizes and no duration", () => {
    const clip = parseWebm(bytes(LIVE_WEBM));
    expect(clip).not.toBeNull();
    expect(clip.frames.map((f) => f.t)).toEqual([0, 0.25, 1, 1.25, 1.5, 1.75]);
    // No declared duration: the last frame plus the mean frame gap, never shorter than the frames.
    expect(clip.duration).toBeGreaterThan(1.75);
  });

  test("answers null for bytes that are not a WebM video", () => {
    expect(parseWebm(new Uint8Array([0, 1, 2, 3]))).toBeNull();
    expect(parseWebm(bytes(SPARSE_WEBM).subarray(0, 60))).toBeNull();
  });
});

describe("frame lookup", () => {
  const frames = [0, 0.25, 1, 1.25].map((t, i) => ({ t, key: i === 0 || i === 2, data: new Uint8Array(1) }));

  test("the frame on screen is the last one whose time has come", () => {
    expect(frameIndexAt(frames, 0)).toBe(0);
    expect(frameIndexAt(frames, 0.9)).toBe(1);
    expect(frameIndexAt(frames, 1)).toBe(2);
    expect(frameIndexAt(frames, 99)).toBe(3);
  });

  test("a decode starts at the keyframe at or before the target", () => {
    expect(keyframeBefore(frames, 1)).toBe(0);
    expect(keyframeBefore(frames, 2)).toBe(2);
    expect(keyframeBefore(frames, 3)).toBe(2);
  });
});

// The player itself, against the smallest stand-ins bun allows: an EventTarget for the <video>, a
// canvas whose captured stream records stops, and a VideoDecoder that can be told to fail.
describe("the fallback player", () => {
  const g = globalThis as any;
  const saved: Record<string, any> = {};
  const decoders: any[] = [];
  const stoppedTracks: number[] = [];
  let failDecodes = false;

  class FakeDecoder {
    state = "unconfigured";
    constructor(private init: { output: (f: any) => void; error: (e: any) => void }) { decoders.push(this); }
    configure() { this.state = "configured"; }
    decode(chunk: { timestamp: number }) {
      queueMicrotask(() => {
        if (this.state === "closed") return;
        if (failDecodes) this.init.error(new Error("corrupt frame"));
        else this.init.output({ timestamp: chunk.timestamp, close() {} });
      });
    }
    close() { this.state = "closed"; }
    static isConfigSupported() { return Promise.resolve({ supported: true }); }
  }
  // The native side of the element: once its load has failed, play() is refused and it stays paused.
  class FakeMedia extends EventTarget {
    loadFailed = false;
    rejectAfterMs = 0; // a browser settles a pending play() only once the load has failed
    play(): Promise<void> {
      if (!this.loadFailed) return Promise.resolve();
      const err = Object.assign(new Error("no source"), { name: "NotSupportedError" });
      return new Promise((_, reject) => setTimeout(() => reject(err), this.rejectAfterMs));
    }
    pause() {}
  }
  class FakeVideo extends FakeMedia {
    tagName = "VIDEO"; isConnected = true; loop = false; autoplay = false; muted = false;
    constructor(private src: string) { super(); }
    getAttribute(name: string) { return name === "src" ? this.src : null; }
  }

  beforeAll(() => {
    for (const k of ["VideoDecoder", "EncodedVideoChunk", "HTMLCanvasElement", "document"]) saved[k] = g[k];
    keepRefusedPlays(FakeMedia.prototype); // what installClipFallback does to HTMLMediaElement's
    g.VideoDecoder = FakeDecoder;
    g.EncodedVideoChunk = class { timestamp: number; constructor(o: any) { this.timestamp = o.timestamp; } };
    g.HTMLCanvasElement = class {};
    g.HTMLCanvasElement.prototype.captureStream = () => {};
    g.document = {
      createElement: () => ({
        getContext: () => ({ drawImage() {} }),
        captureStream: () => ({
          getVideoTracks: () => [{ requestFrame() {} }],
          getTracks: () => [{ stop: () => stoppedTracks.push(1) }],
        }),
      }),
    };
  });
  afterAll(() => { for (const k of Object.keys(saved)) g[k] = saved[k]; });

  const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));
  let n = 0;
  // Registers the clip, then delivers the native load error that hands the element to the player.
  async function adoptedVideo(url = `blob:player-test-${n++}`, register = true): Promise<{ el: any; events: string[] }> {
    if (register) registerClipBytes(url, bytes(SPARSE_WEBM));
    const el: any = new FakeVideo(url);
    const events: string[] = [];
    for (const type of ["error", "pause", "loadeddata"]) el.addEventListener(type, () => events.push(type));
    watchClipElement(el);
    el.dispatchEvent(new Event("error"));
    await sleep(5);
    return { el, events };
  }

  test("a clip the decoder rejects is handed back to the viewer as an error, and freed", async () => {
    failDecodes = true;
    try {
      const { el, events } = await adoptedVideo();

      // One error, the player's own, so the viewer shows screenshots instead of a blank frame.
      expect(events).toEqual(["error"]);
      expect(el.error?.code).toBe(3);
      expect(stoppedTracks.length).toBeGreaterThan(0);
      expect(decoders.at(-1).state).toBe("closed");
      el.play();
      await sleep(40);
      expect(el.paused).toBe(true);
    } finally {
      failDecodes = false;
      stoppedTracks.length = 0;
    }
  });

  test("a playing clip whose element leaves the page pauses and frees its decoder", async () => {
    const { el, events } = await adoptedVideo();
    expect(events).toEqual(["loadeddata"]);
    el.play();
    await sleep(40);
    expect(el.paused).toBe(false);

    el.isConnected = false; // the surface re-rendered its markup without calling load()
    await sleep(40);
    expect(el.paused).toBe(true);
    expect(events).toContain("pause");
    const detachedDecoder = decoders.at(-1);
    expect(detachedDecoder.state).toBe("closed");

    // Put back on the page, it draws again on demand.
    el.isConnected = true;
    el.currentTime = 1;
    await sleep(5);
    expect(decoders.at(-1)).not.toBe(detachedDecoder);
    expect(decoders.at(-1).state).toBe("configured");
  });

  test("an unregistered clip's bytes and frames are gone, so a later element can't play it", async () => {
    const url = "blob:player-test-released";
    expect((await adoptedVideo(url)).events).toEqual(["loadeddata"]);

    unregisterClipBytes(url); // the viewer revoked the URL on loading the next archive

    const { events } = await adoptedVideo(url, false);
    expect(events).not.toContain("loadeddata");
  });

  test("a play pressed after the load failed, before the takeover, starts the clip once it lands", async () => {
    const url = `blob:player-test-${n++}`;
    registerClipBytes(url, bytes(SPARSE_WEBM));
    const el: any = new FakeVideo(url);
    watchClipElement(el);
    el.loadFailed = true; // the host's CSP refused the blob: URL; its error event is still queued

    await el.play(); // resolves: kept for the takeover rather than rejected and lost
    el.dispatchEvent(new Event("error"));
    await sleep(40);

    expect(el.paused).toBe(false);
    expect(el.currentTime).toBeGreaterThan(0);
  });

  test("a play refused only after the takeover landed goes to the takeover", async () => {
    const url = `blob:player-test-${n++}`;
    registerClipBytes(url, bytes(SPARSE_WEBM));
    const el: any = new FakeVideo(url);
    watchClipElement(el);
    el.loadFailed = true;
    el.rejectAfterMs = 30; // the error event, and so the takeover, runs before the rejection

    const pressed = el.play();
    el.dispatchEvent(new Event("error"));
    await pressed;
    await sleep(40);

    expect(el.paused).toBe(false);
    expect(el.currentTime).toBeGreaterThan(0);
  });

  test("a pause before the takeover withdraws the refused play", async () => {
    const url = `blob:player-test-${n++}`;
    registerClipBytes(url, bytes(SPARSE_WEBM));
    const el: any = new FakeVideo(url);
    watchClipElement(el);
    el.loadFailed = true;

    await el.play();
    el.pause();
    el.dispatchEvent(new Event("error"));
    await sleep(40);

    expect(el.paused).toBe(true);
  });

  test("a play refused for a clip the fallback can't take is still refused", async () => {
    const el: any = new FakeVideo("blob:not-a-registered-clip");
    el.loadFailed = true;
    await expect(el.play()).rejects.toThrow("no source");
  });
});
