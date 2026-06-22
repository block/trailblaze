// Smallest end-to-end example of consuming the GENERATED daemon RPC client from TypeScript.
// `createRpcClient` is generated from the Kotlin `RpcRequest<TResponse>` declarations — the endpoint
// name, request type, response type, and path are all derived — so this is fully typed with nothing
// maintained by hand (no endpoint strings, no type arguments).
//
// Run it (inline mock daemon, no server needed):
//   bun src/rpc/example.ts
// Point it at a live daemon by passing its origin to createRpcClient({ baseUrl }).

import { createRpcClient } from "../generated/host-rpc.js";

/** One typed call via the generated client — `rpc.getConnectedDevices()`, nothing by hand. */
export async function loadConnectedDeviceNames(
  baseUrl: string,
  fetchImpl?: typeof fetch,
): Promise<string[]> {
  const rpc = createRpcClient({ baseUrl, fetchImpl });
  const result = await rpc.getConnectedDevices();
  if (!result.ok) throw new Error(`RPC failed: ${result.error.message}`);
  // `result.data` is GetConnectedDevicesResponse — typed end to end. Rename a Kotlin field,
  // regenerate, and this line stops compiling.
  return result.data.devices.map((d) => `${d.description} (${d.instanceId})`);
}

/** A stand-in daemon so the demo runs with no backend. */
export const mockDaemon: typeof fetch = async () =>
  new Response(
    JSON.stringify({
      devices: [
        {
          trailblazeDriverType: "ANDROID_ONDEVICE_ACCESSIBILITY",
          instanceId: "emulator-5554",
          description: "Pixel 7 (API 34)",
        },
        { trailblazeDriverType: "IOS_HOST", instanceId: "ABC-123", description: "iPhone 15 Sim" },
      ],
    }),
    { status: 200, headers: { "content-type": "application/json" } },
  );

// Run the demo only when executed directly (`import.meta.main` is set by Bun; the cast keeps it
// tsc-clean without pulling in node/bun ambient types here).
if ((import.meta as { main?: boolean }).main) {
  console.log("Connected devices:", await loadConnectedDeviceNames("http://daemon", mockDaemon));
}
