// Unit tests for contacts_ios_launchApp. Asserts the observable contract: which framework tools it
// dispatches, with what args, and the return value — not internal call counts.

import { describe, expect, test } from "bun:test";
import { createMockClient, createMockContext } from "@trailblaze/scripting/testing";

import { contacts_ios_launchApp } from "./contacts_ios_launchApp";

describe("contacts_ios_launchApp", () => {
  test("force-restarts the Contacts app", async () => {
    const client = createMockClient();

    const result = await contacts_ios_launchApp({}, createMockContext({ platform: "ios" }), client);

    expect(client.calls.map((c) => c.tool)).toEqual(["launchApp"]);
    expect(client.calls[0]!.args).toEqual({
      appId: "com.apple.MobileAddressBook",
      launchMode: "FORCE_RESTART",
    });
    expect(result).toContain("com.apple.MobileAddressBook");
  });
});
