// Verifies the BUILT artifact (daemon.bundle.js — the file the daemon serves), not just the source.
// Browser-free: we give the IIFE a DOM-less `window` + a mocked global `fetch`, load it, and confirm
// its side effect publishes a working `window.TbRpc`. This is the CI-runnable stand-in for the
// Playwright smoke test (e2e/devices.spec.ts), which needs a real browser.
//
// Run: `bun test app/rpc/bundle.test.ts` from the web/ directory.
import { beforeAll, describe, expect, test } from "bun:test";

const fetchCalls: string[] = [];

describe("daemon.bundle.js (served artifact)", () => {
  beforeAll(async () => {
    // The bundle guards on `typeof window !== "undefined"`, so expose one; it uses the global
    // `fetch` (no fetchImpl injected for the same-origin instance), so mock that too.
    (globalThis as Record<string, unknown>).window = globalThis;
    (globalThis as Record<string, unknown>).fetch = (async (url: string | URL | Request) => {
      fetchCalls.push(String(url));
      return new Response(
        JSON.stringify({
          devices: [{ trailblazeDriverType: "IOS_HOST", instanceId: "sim-1", description: "iPhone 15" }],
        }),
        { status: 200, headers: { "content-type": "application/json" } },
      );
    }) as unknown as typeof fetch;
    await import("./daemon.bundle.js");
  });

  test("publishes window.TbRpc", () => {
    expect(typeof (globalThis as { window: { TbRpc?: unknown } }).window.TbRpc).toBe("object");
  });

  test("getConnectedDevices() round-trips through the global fetch", async () => {
    const tb = (globalThis as { window: { TbRpc: { getConnectedDevices: () => Promise<{ devices: Array<Record<string, string>> } | null> } } }).window.TbRpc;
    const resp = await tb.getConnectedDevices();
    expect(resp?.devices[0].instanceId).toBe("sim-1");
    expect(fetchCalls.some((u) => u.endsWith("/rpc/GetConnectedDevicesRequest"))).toBe(true);
  });
});
