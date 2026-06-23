// Tests for `fromMeta`'s parsing of the `_meta.trailblaze` envelope. Focused on the
// `target` block — the rest of the envelope (sessionId, invocationId, device, memory) is
// covered indirectly by client tests + the Kotlin-side envelope tests, but the target
// surface is new contract and needs explicit coverage of:
//   1. `target` absent → `ctx.target === undefined` (older-daemon backward compat).
//   2. `target` present with `appId` → all fields parsed + `resolveAppId()` picks it.
//   3. `target` present without `appId` → `resolveAppId()` falls through to appIds[0].
//   4. Structurally invalid `target` → `ctx.target === undefined` rather than the whole
//      envelope being dropped (the rest is still useful).

import { describe, expect, test } from "bun:test";

import { fromMeta } from "./context.js";

function envelope(target?: unknown): unknown {
  return {
    trailblaze: {
      baseUrl: "http://localhost:52525",
      sessionId: "session-abc",
      invocationId: "inv-123",
      device: { platform: "android", widthPixels: 1080, heightPixels: 2400, driverType: "android-ondevice-accessibility" },
      memory: {},
      ...(target !== undefined ? { target } : {}),
    },
  };
}

describe("fromMeta: device block", () => {
  // Builds an envelope whose device block is exactly `device`, so each test controls the
  // instanceId shape (the shared `envelope()` helper hardcodes a device with no instanceId).
  function withDevice(device: Record<string, unknown>): unknown {
    return {
      trailblaze: {
        baseUrl: "http://localhost:52525",
        sessionId: "session-abc",
        invocationId: "inv-123",
        device,
        memory: {},
      },
    };
  }

  test("instanceId present is parsed onto ctx.device.instanceId", () => {
    const ctx = fromMeta(
      withDevice({
        platform: "ios",
        widthPixels: 1170,
        heightPixels: 2532,
        driverType: "ios-host",
        instanceId: "ABC-123-UDID",
      }),
    );
    expect(ctx?.device.instanceId).toBe("ABC-123-UDID");
  });

  test("instanceId absent leaves it undefined without dropping the envelope (older daemon / subprocess path)", () => {
    const ctx = fromMeta(
      withDevice({
        platform: "android",
        widthPixels: 1080,
        heightPixels: 2400,
        driverType: "android-ondevice-accessibility",
      }),
    );
    expect(ctx).toBeDefined();
    expect(ctx?.device.instanceId).toBeUndefined();
    expect(ctx?.device.platform).toBe("android");
  });

  test("non-string instanceId is treated as absent (lenient parse), envelope still parses", () => {
    const ctx = fromMeta(
      withDevice({
        platform: "android",
        widthPixels: 1080,
        heightPixels: 2400,
        driverType: "android-ondevice-accessibility",
        instanceId: 42,
      }),
    );
    expect(ctx).toBeDefined();
    expect(ctx?.device.instanceId).toBeUndefined();
  });
});

describe("fromMeta: target block", () => {
  test("absent target leaves ctx.target undefined (older-daemon backward compat)", () => {
    const ctx = fromMeta(envelope());
    expect(ctx).toBeDefined();
    expect(ctx?.target).toBeUndefined();
  });

  test("explicit null target reads identically to absent target", () => {
    // A producer that emits `target: null` explicitly (vs. omitting the key) lands the same
    // place — `ctx.target === undefined`. Locks the semantic equivalence so a future writer
    // that switches from "omit when null" to "always emit, possibly null" doesn't accidentally
    // change consumer behavior.
    const ctx = fromMeta(envelope(null));
    expect(ctx).toBeDefined();
    expect(ctx?.target).toBeUndefined();
  });

  test("empty target object reads as no target (missing required id and appIds)", () => {
    // `target: {}` is structurally invalid — neither required field present. `parseTarget`
    // drops it, the rest of the envelope still parses. Same observable shape as the "absent
    // target" case, just via a different shape of producer bug.
    const ctx = fromMeta(envelope({}));
    expect(ctx).toBeDefined();
    expect(ctx?.target).toBeUndefined();
    expect(ctx?.sessionId).toBe("session-abc");
  });

  test("present target with appId parses all fields", () => {
    const ctx = fromMeta(envelope({
      id: "example",
      appIds: ["com.example.dev", "com.example.staging", "com.example"],
      appId: "com.example.dev",
    }));
    expect(ctx?.target?.id).toBe("example");
    expect(ctx?.target?.appIds).toEqual(["com.example.dev", "com.example.staging", "com.example"]);
    expect(ctx?.target?.appId).toBe("com.example.dev");
    expect(ctx?.target?.resolveAppId()).toBe("com.example.dev");
  });

  test("present target without appId falls through to appIds[0] via resolveAppId", () => {
    // Session started before any declared candidate was installed → appId absent.
    // The resolver should still hand back the first declared candidate so the launch attempt
    // at least targets the canonical id.
    const ctx = fromMeta(envelope({
      id: "example",
      appIds: ["com.example.dev", "com.example.staging"],
    }));
    expect(ctx?.target?.appId).toBeUndefined();
    expect(ctx?.target?.resolveAppId()).toBe("com.example.dev");
  });

  test("empty appIds + no appId falls through to caller default", () => {
    // Sentinel for the third priority layer — caller-supplied default after both the framework
    // resolution and the declared list are empty. Authors writing portable tools should always
    // supply a default so the resolver can never return undefined on a target-aware session.
    const ctx = fromMeta(envelope({ id: "example", appIds: [] }));
    expect(ctx?.target?.resolveAppId()).toBeUndefined();
    expect(ctx?.target?.resolveAppId({ defaultAppId: "com.fallback" })).toBe("com.fallback");
  });

  test("displayName / baseUrls / resolvedBaseUrl all propagate when emitted", () => {
    const ctx = fromMeta(envelope({
      id: "wikipedia",
      displayName: "Wikipedia",
      appIds: ["org.wikipedia"],
      appId: "org.wikipedia",
      baseUrls: ["https://en.wikipedia.org", "https://en.m.wikipedia.org"],
      resolvedBaseUrl: "https://en.wikipedia.org",
    }));
    expect(ctx?.target?.displayName).toBe("Wikipedia");
    expect(ctx?.target?.baseUrls).toEqual(["https://en.wikipedia.org", "https://en.m.wikipedia.org"]);
    expect(ctx?.target?.resolvedBaseUrl).toBe("https://en.wikipedia.org");
    expect(ctx?.target?.resolveBaseUrl()).toBe("https://en.wikipedia.org");
  });

  test("resolveBaseUrl falls through to caller default when no base url is wired", () => {
    // The framework doesn't yet emit baseUrls / resolvedBaseUrl (manifest schema lands later),
    // so this is the load-bearing path for current web tools: empty data layers + caller
    // default. Lock the behavior so the future emission rollout doesn't accidentally break it.
    const ctx = fromMeta(envelope({ id: "wikipedia", appIds: [] }));
    expect(ctx?.target?.resolveBaseUrl({ defaultBaseUrl: "https://en.wikipedia.org" }))
      .toBe("https://en.wikipedia.org");
  });

  test("malformed target (non-string id) drops target but keeps rest of envelope", () => {
    // Producer-side bug or in-flight schema drift shouldn't take down the whole call — the
    // sessionId/invocationId are still useful for callback routing.
    const ctx = fromMeta(envelope({ id: 42, appIds: ["com.example"] }));
    expect(ctx).toBeDefined();
    expect(ctx?.target).toBeUndefined();
    expect(ctx?.sessionId).toBe("session-abc");
  });

  test("malformed appIds (non-array) drops target but keeps rest of envelope", () => {
    const ctx = fromMeta(envelope({ id: "example", appIds: "com.example" }));
    expect(ctx?.target).toBeUndefined();
    expect(ctx?.sessionId).toBe("session-abc");
  });

  test("appIds with non-string entry drops target (no partial filtering)", () => {
    // Reject-the-whole-block stance: filtering nulls out of the array would silently mask a
    // producer bug. An explicit drop forces the bug to surface as missing target rather than
    // a target with the wrong canonical first-candidate.
    const ctx = fromMeta(envelope({ id: "example", appIds: ["com.example", null] }));
    expect(ctx?.target).toBeUndefined();
  });

  test("destructured resolveAppId still resolves (closures, not this-bound methods)", () => {
    // Authors sometimes write `const { resolveAppId } = ctx.target` to thread the function
    // into helpers. A `this`-bound method would lose its data context the moment that
    // happens — the closure-based implementation captures the parsed locals so the function
    // value works no matter how it's passed around.
    const ctx = fromMeta(envelope({
      id: "example",
      appIds: ["com.example.dev"],
      appId: "com.example.dev",
    }));
    const target = ctx?.target;
    if (!target) throw new Error("expected target to be defined");
    const { resolveAppId, resolveBaseUrl } = target;
    expect(resolveAppId()).toBe("com.example.dev");
    expect(resolveBaseUrl({ defaultBaseUrl: "https://fallback.example" }))
      .toBe("https://fallback.example");
  });
});
