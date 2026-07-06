// Unit tests for calendar_android_launch. Asserts the observable contract: which framework tools
// it dispatches, with what args, and the return value — not internal call counts.

import { describe, expect, test } from "bun:test";
import { createMockClient, createMockContext } from "@trailblaze/scripting/testing";

import { calendar_android_launch } from "./calendar_android_launch";

describe("calendar_android_launch", () => {
  test("delegates to calendar_android_launchApp with no args", async () => {
    const client = createMockClient();
    client.stub("calendar_android_launchApp", { textContent: "Launched com.google.android.calendar." });

    const result = await calendar_android_launch(
      {},
      createMockContext({ platform: "android" }),
      client,
    );

    expect(client.calls.map((c) => c.tool)).toEqual(["calendar_android_launchApp"]);
    expect(client.calls[0]!.args).toEqual({});
    expect(result).toBe("Launched com.google.android.calendar.");
  });
});
