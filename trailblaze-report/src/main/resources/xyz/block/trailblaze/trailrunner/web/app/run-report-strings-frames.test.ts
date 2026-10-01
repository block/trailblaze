import { afterEach, describe, expect, test } from "bun:test";

import { grabClipFrames } from "./run-report-strings-frames";

// A stand-in <video> that loads, seeks and reports frames the way a browser's does, and a canvas
// whose still names the media time it was drawn at, so a test can see which frame each capture got.
type FakeVideo = ReturnType<typeof fakeVideo>;
// `metadataOnly` is the in-page fallback player driving an element off the page: it announces the
// clip's header and never a first frame.
// `fatalSeek` is the seek at which the player fails for good, as the fallback does on a decode
// error: that seek fires `error`, and no later one fires anything.
function fakeVideo(opts: { duration: number; loads?: boolean; stuckSeeks?: number[]; metadataOnly?: boolean; fatalSeek?: number }) {
  const listeners: Record<string, Array<(e: unknown) => void>> = {};
  const fire = (type: string) => setTimeout(() => (listeners[type] || []).slice().forEach((f) => f({ type, target: el })), 0);
  const el = {
    tagName: "FAKE",
    muted: false, preload: "", crossOrigin: null as string | null, srcObject: null,
    videoWidth: 540, videoHeight: 1200, duration: NaN,
    seeks: [] as number[],
    attrs: {} as Record<string, string>,
    time: 0,
    get currentTime() { return this.time; },
    set currentTime(t: number) {
      this.time = t;
      this.seeks.push(t);
      if (opts.fatalSeek != null && this.seeks.length >= opts.fatalSeek) {
        if (this.seeks.length === opts.fatalSeek) fire("error");
        return;
      }
      if (!(opts.stuckSeeks || []).includes(this.seeks.length)) fire("seeked");
    },
    set src(url: string) {
      this.attrs.src = url;
      if (opts.loads === false) { fire("error"); return; }
      this.duration = opts.duration;
      fire("loadedmetadata");
      if (!opts.metadataOnly) fire("loadeddata");
    },
    addEventListener(type: string, f: (e: unknown) => void) { (listeners[type] = listeners[type] || []).push(f); },
    removeEventListener(type: string, f: (e: unknown) => void) { listeners[type] = (listeners[type] || []).filter((g) => g !== f); },
    setAttribute(k: string, v: string) { this.attrs[k] = v; },
    removeAttribute(k: string) { delete this.attrs[k]; },
    load() {},
  };
  return el;
}

const realDocument = (globalThis as any).document;
afterEach(() => { (globalThis as any).document = realDocument; });

function page(video: FakeVideo, opts: { tainted?: boolean } = {}) {
  let videos = 0;
  (globalThis as any).document = {
    createElement(tag: string) {
      if (tag === "video") { videos++; return video; }
      let drawnAt: number | null = null;
      return {
        width: 0, height: 0,
        getContext: () => ({ drawImage: (el: FakeVideo) => { drawnAt = el.currentTime; } }),
        toDataURL: (type: string) => {
          if (opts.tainted) throw new Error("SecurityError");
          return `data:${type};t=${drawnAt}`;
        },
      };
    },
  };
  return { videos: () => videos };
}

// A 10 s recording window whose file runs 9.5 s: a time maps onto the file scaled by 0.95.
const clip = { url: "blob:report/clip", startMs: 1_000, endMs: 11_000 };

async function grab(requests: Array<{ key: string; atMs: number }>, c = clip, timeoutMs?: number) {
  const got: Array<[string, string | null]> = [];
  await grabClipFrames(c, requests, (key, src) => { got.push([key, src]); }, timeoutMs);
  return got;
}

describe("grabClipFrames", () => {
  test("each capture gets the frame at its time on the recording, from one load of the clip", async () => {
    const video = fakeVideo({ duration: 9.5 });
    const doc = page(video);
    const got = await grab([{ key: "a", atMs: 3_000 }, { key: "b", atMs: 6_000 }]);

    expect(got).toEqual([["a", "data:image/jpeg;t=1.9"], ["b", "data:image/jpeg;t=4.75"]]);
    expect(doc.videos()).toBe(1);
    // An embedded clip is readable as is; only a linked one has to ask its host.
    expect(video.crossOrigin).toBeNull();
    // The element lets go of the clip when it is done.
    expect(video.attrs.src).toBeUndefined();
  });

  test("a clip linked from another site asks that site to let its pixels be read", async () => {
    const video = fakeVideo({ duration: 9.5 });
    page(video);
    await grab([{ key: "a", atMs: 3_000 }], { ...clip, url: "https://media.example/clip.webm" });
    expect(video.crossOrigin).toBe("anonymous");
  });

  test("a time the recording does not cover has no frame and costs no seek", async () => {
    const video = fakeVideo({ duration: 9.5 });
    page(video);
    expect(await grab([{ key: "before", atMs: 500 }])).toEqual([["before", null]]);
    expect(video.seeks).toEqual([]);
  });

  test("a recording that will not load answers every capture with no frame", async () => {
    page(fakeVideo({ duration: 9.5, loads: false }));
    expect(await grab([{ key: "a", atMs: 3_000 }, { key: "b", atMs: 6_000 }])).toEqual([["a", null], ["b", null]]);
  });

  test("a recording whose pixels cannot be read gives no frames", async () => {
    page(fakeVideo({ duration: 9.5 }), { tainted: true });
    expect(await grab([{ key: "a", atMs: 3_000 }])).toEqual([["a", null]]);
  });

  test("a seek that never lands gives up on that frame and goes on to the next", async () => {
    page(fakeVideo({ duration: 9.5, stuckSeeks: [1] }));
    expect(await grab([{ key: "a", atMs: 3_000 }, { key: "b", atMs: 6_000 }], clip, 20)).toEqual([["a", null], ["b", "data:image/jpeg;t=4.75"]]);
  });

  test("a player that fails for good answers the rest of the batch with no frame, without seeking again", async () => {
    const video = fakeVideo({ duration: 9.5, fatalSeek: 2 });
    page(video);
    expect(await grab([{ key: "a", atMs: 3_000 }, { key: "b", atMs: 6_000 }, { key: "c", atMs: 8_000 }], clip, 20))
      .toEqual([["a", "data:image/jpeg;t=1.9"], ["b", null], ["c", null]]);
    expect(video.seeks).toEqual([1.9, 4.75]);
  });

  test("a capture taken the other way round from the recording has no frame, since its boxes would not fit", async () => {
    // A portrait recording (540×1200), as Android keeps the orientation the session started in.
    page(fakeVideo({ duration: 9.5 }));
    expect(await grab([
      { key: "portrait", atMs: 3_000, width: 1080, height: 2400 },
      { key: "landscape", atMs: 6_000, width: 2400, height: 1080 },
    ])).toEqual([["portrait", "data:image/jpeg;t=1.9"], ["landscape", null]]);
  });

  test("a player that announces only the clip's header, as the fallback does off the page, still gives every frame", async () => {
    page(fakeVideo({ duration: 9.5, metadataOnly: true }));
    expect(await grab([{ key: "a", atMs: 3_000 }, { key: "b", atMs: 6_000 }], clip, 50))
      .toEqual([["a", "data:image/jpeg;t=1.9"], ["b", "data:image/jpeg;t=4.75"]]);
  });

  test("a capture at the recording's first instant is still sought to, so its frame has been decoded", async () => {
    const video = fakeVideo({ duration: 9.5 });
    page(video);
    expect(await grab([{ key: "start", atMs: 1_000 }])).toEqual([["start", "data:image/jpeg;t=0"]]);
    expect(video.seeks).toEqual([0]);
  });

  test("a recording with no known length gives no frames, since no time can be placed on it", async () => {
    page(fakeVideo({ duration: Infinity }));
    expect(await grab([{ key: "a", atMs: 3_000 }])).toEqual([["a", null]]);
  });
});
