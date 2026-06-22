// Runtime tests for the daemon RPC client (`rpcCall` + `RpcResult`). Run via `bun test`.
// Uses an injected `fetchImpl` to simulate the daemon's wire contract — no network — and
// consumes a generated DTO type to prove the generated types + the hand-written client compose
// end-to-end with full type inference.

import { describe, expect, test } from "bun:test";

import { rpcCall, type RpcResult } from "./client.js";
import type {
  GetConnectedDevicesRequest,
  GetConnectedDevicesResponse,
} from "../generated/host-rpc.js";

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

  test("turns a malformed (non-empty) 2xx body into a SERIALIZATION_ERROR result instead of throwing", async () => {
    const fetchImpl: typeof fetch = async () =>
      new Response("<html>502 from a proxy</html>", {
        status: 200,
        headers: { "content-type": "text/html" },
      });

    const result = await rpcCall<GetConnectedDevicesRequest, GetConnectedDevicesResponse>(
      "GetConnectedDevicesRequest",
      {},
      { fetchImpl },
    );

    expect(result.ok).toBe(false);
    if (!result.ok) {
      expect(result.error.errorType).toBe("SERIALIZATION_ERROR");
      expect(result.error.message).toBe("2xx response body was not valid JSON");
      expect(result.error.details).toBe("<html>502 from a proxy</html>");
    }
  });

  test("truncates the malformed-body details to 300 chars", async () => {
    const longBody = "x".repeat(500); // not JSON, and well over the 300-char details cap
    const fetchImpl: typeof fetch = async () => new Response(longBody, { status: 200 });

    const result = await rpcCall<GetConnectedDevicesRequest, GetConnectedDevicesResponse>(
      "GetConnectedDevicesRequest",
      {},
      { fetchImpl },
    );

    expect(result.ok).toBe(false);
    if (!result.ok) {
      expect(result.error.errorType).toBe("SERIALIZATION_ERROR");
      expect(result.error.details).toBe(longBody.slice(0, 300));
      expect(result.error.details!.length).toBe(300);
    }
  });

  test("treats a valid-but-falsy JSON body (\"null\") as a successful null payload", async () => {
    // The empty-body short-circuit yields `data: undefined`; a body of the JSON literal `null`
    // parses to `data: null`. The two are distinct outcomes — this locks that boundary in.
    const fetchImpl: typeof fetch = async () => new Response("null", { status: 200 });

    const result = await rpcCall<GetConnectedDevicesRequest, GetConnectedDevicesResponse | null>(
      "GetConnectedDevicesRequest",
      {},
      { fetchImpl },
    );

    expect(result.ok).toBe(true);
    if (result.ok) {
      expect(result.data).toBeNull();
    }
  });

  test("treats an empty 2xx body as a successful no-payload response", async () => {
    const fetchImpl: typeof fetch = async () => new Response("", { status: 200 });

    // TResponse = void: a no-payload endpoint genuinely has no response body, so the success
    // branch yields `data: undefined`. (Using a real response type here would misleadingly imply
    // that type is nullable.)
    const result = await rpcCall<GetConnectedDevicesRequest, void>(
      "GetConnectedDevicesRequest",
      {},
      { fetchImpl },
    );

    expect(result.ok).toBe(true);
    if (result.ok) {
      expect(result.data).toBeUndefined();
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
