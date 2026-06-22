// Runtime tests for the GENERATED typed client (`createRpcClient`). Run via `bun test`. Uses an
// injected `fetchImpl` to simulate the daemon's wire contract — no network — and exercises the
// generated methods (typed endpoint name, request, and response all baked in).

import { describe, expect, test } from "bun:test";

import { createRpcClient } from "../generated/host-rpc.js";

describe("createRpcClient (generated)", () => {
  test("a method maps to its /rpc/<Name> path and returns the typed response on 2xx", async () => {
    let capturedUrl = "";
    const fetchImpl: typeof fetch = async (url) => {
      capturedUrl = String(url);
      return new Response(
        JSON.stringify({
          devices: [{ trailblazeDriverType: "COMPOSE", instanceId: "emu", description: "Emu" }],
        }),
        { status: 200, headers: { "content-type": "application/json" } },
      );
    };

    const rpc = createRpcClient({ baseUrl: "http://daemon", fetchImpl });
    const r = await rpc.getConnectedDevices(); // no args — request is optional (Kotlin object)

    expect(capturedUrl).toBe("http://daemon/rpc/GetConnectedDevicesRequest");
    expect(r.ok).toBe(true);
    if (r.ok) expect(r.data.devices[0]!.instanceId).toBe("emu"); // typed end-to-end
  });

  test("surfaces a flat RpcErrorResponse on non-2xx", async () => {
    const fetchImpl: typeof fetch = async () =>
      new Response(JSON.stringify({ errorType: "HTTP_ERROR", message: "no devices" }), { status: 500 });

    const rpc = createRpcClient({ fetchImpl });
    const r = await rpc.getTargetApps();

    expect(r.ok).toBe(false);
    if (!r.ok) expect(r.error.message).toBe("no devices");
  });
});
