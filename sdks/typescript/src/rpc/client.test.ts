// Runtime tests for the daemon RPC client (`rpcCall` + `RpcResult`). Run via `bun test`.
// Uses an injected `fetchImpl` to simulate the daemon's wire contract — no network — and
// consumes a generated DTO type to prove the generated types + the hand-written client compose
// end-to-end with full type inference.

import { describe, expect, test } from "bun:test";

import { rpcCall, type RpcResult } from "./client.js";
import type {
  GetConnectedDevicesRequest,
  GetConnectedDevicesResponse,
} from "../generated/host-rpc-dtos.js";

describe("rpcCall", () => {
  test("POSTs to /rpc/<Name> and deserializes a 2xx body as the typed response", async () => {
    let capturedUrl = "";
    let capturedBody = "";
    const fetchImpl: typeof fetch = async (url, init) => {
      capturedUrl = String(url);
      capturedBody = String(init?.body ?? "");
      return new Response(
        JSON.stringify({
          devices: [{ trailblazeDriverType: "COMPOSE", instanceId: "emu-5554", description: "Pixel" }],
        }),
        { status: 200, headers: { "content-type": "application/json" } },
      );
    };

    const result: RpcResult<GetConnectedDevicesResponse> = await rpcCall<
      GetConnectedDevicesRequest,
      GetConnectedDevicesResponse
    >("GetConnectedDevicesRequest", {}, { baseUrl: "http://daemon", fetchImpl });

    expect(capturedUrl).toBe("http://daemon/rpc/GetConnectedDevicesRequest");
    expect(capturedBody).toBe("{}");
    expect(result.ok).toBe(true);
    if (result.ok) {
      // Typed all the way through — `result.data` is GetConnectedDevicesResponse.
      expect(result.data.devices[0]!.instanceId).toBe("emu-5554");
    }
  });

  test("deserializes a non-2xx body as a flat RpcErrorResponse", async () => {
    const fetchImpl: typeof fetch = async () =>
      new Response(JSON.stringify({ errorType: "HTTP_ERROR", message: "Device not connected" }), {
        status: 500,
      });

    const result = await rpcCall<GetConnectedDevicesRequest, GetConnectedDevicesResponse>(
      "GetConnectedDevicesRequest",
      {},
      { fetchImpl },
    );

    expect(result.ok).toBe(false);
    if (!result.ok) {
      expect(result.error.errorType).toBe("HTTP_ERROR");
      expect(result.error.message).toBe("Device not connected");
    }
  });

  test("turns a thrown fetch (transport failure) into a NETWORK_ERROR result", async () => {
    const fetchImpl: typeof fetch = async () => {
      throw new Error("connect failed");
    };

    const result = await rpcCall("GetConnectedDevicesRequest", {}, { fetchImpl });

    expect(result.ok).toBe(false);
    if (!result.ok) {
      expect(result.error.errorType).toBe("NETWORK_ERROR");
      expect(result.error.message).toBe("connect failed");
    }
  });
});
