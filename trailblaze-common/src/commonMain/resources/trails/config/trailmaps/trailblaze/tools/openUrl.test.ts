// Unit tests for the framework `openUrl` tool. Drives the typed tool directly (no daemon, no
// device) with the mock client + context from `@trailblaze/scripting/testing`. Run via `bun test`.

import { describe, expect, test } from "bun:test";
import { createMockClient, createMockContext } from "@trailblaze/scripting/testing";

import { openUrl } from "./openUrl";

const URL = "https://example.com/path?q=1&r=2";

describe("openUrl", () => {
  test("fires a browsable VIEW intent on Android, passing the URL as one argv token", async () => {
    const c = createMockClient();
    c.stub("android_adbShell", { textContent: "Starting: Intent { act=android.intent.action.VIEW }" });

    const result = await openUrl(
      { url: ` ${URL} ` },
      createMockContext({ platform: "android", device: { driverType: "android-ondevice-accessibility" } }),
      c,
    );

    expect(c.calls).toEqual([
      {
        tool: "android_adbShell",
        args: {
          command: ["am", "start", "-W", "-a", "android.intent.action.VIEW", "-c", "android.intent.category.BROWSABLE", "-d", URL],
        },
      },
    ]);
    expect(result).toBe(`Opened ${URL}`);
  });

  test("fails on Android when no activity resolves the intent", async () => {
    const c = createMockClient();
    c.stub("android_adbShell", {
      textContent:
        "Starting: Intent { act=android.intent.action.VIEW dat=bogus:/// }\n" +
        "Error: Activity not started, unable to resolve Intent { act=android.intent.action.VIEW }",
    });

    await expect(
      openUrl(
        { url: "bogus:///" },
        createMockContext({ platform: "android", device: { driverType: "android-ondevice-accessibility" } }),
        c,
      ),
    ).rejects.toThrow("openUrl could not open bogus:///: Starting:");
  });

  test("opens the URL with ios_openUrl on iOS", async () => {
    const c = createMockClient();

    const result = await openUrl(
      { url: URL },
      createMockContext({ platform: "ios", device: { driverType: "ios-host" } }),
      c,
    );

    expect(c.calls).toEqual([{ tool: "ios_openUrl", args: { url: URL } }]);
    expect(result).toBe(`Opened ${URL}`);
  });

  test("rejects a blank URL without touching the device", async () => {
    const c = createMockClient();

    await expect(
      openUrl({ url: "  " }, createMockContext({ platform: "android", device: { driverType: "android-ondevice-accessibility" } }), c),
    ).rejects.toThrow("openUrl requires a non-empty `url` argument.");
    expect(c.calls).toEqual([]);
  });
});
