// Playwright smoke test for the typed daemon-RPC wiring. Hermetic: it serves only the bundle and
// mocks the daemon endpoint, so it verifies exactly what this change introduced —
//   1. `daemon.bundle.js` runs in a real browser and publishes `window.TbRpc`,
//   2. a typed call (`getConnectedDevices()`) POSTs to the right `/rpc/<Name>` path,
//   3. the response is parsed and returned with the generated shape.
// It does NOT boot the full React app or the daemon. Run: `bun run test` from web/e2e/.
import { test, expect } from "@playwright/test";

test("daemon.bundle.js publishes window.TbRpc and getConnectedDevices() round-trips", async ({ page }) => {
  let requestedUrl = "";
  await page.route("**/rpc/GetConnectedDevicesRequest", (route) => {
    requestedUrl = route.request().url();
    return route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        devices: [
          {
            trailblazeDriverType: "ANDROID_ONDEVICE_ACCESSIBILITY",
            instanceId: "emulator-5554",
            description: "Pixel 7",
          },
        ],
      }),
    });
  });

  await page.goto("/e2e/smoke.html");

  // (1) the bundle published the global
  expect(await page.evaluate(() => typeof (window as { TbRpc?: unknown }).TbRpc)).toBe("object");

  // (2) + (3) the typed call round-trips through the mocked endpoint
  const firstDevice = await page.evaluate(async () => {
    const tb = (window as { TbRpc: { getConnectedDevices: () => Promise<{ devices: Array<Record<string, string>> } | null> } }).TbRpc;
    const resp = await tb.getConnectedDevices();
    return resp?.devices?.[0] ?? null;
  });

  expect(requestedUrl).toContain("/rpc/GetConnectedDevicesRequest");
  expect(firstDevice?.instanceId).toBe("emulator-5554");
  expect(firstDevice?.description).toBe("Pixel 7");
});
