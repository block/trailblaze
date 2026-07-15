// Unit tests for contacts_launchApp. Asserts the observable contract: which platform
// impl it delegates to per device platform, and that unsupported platforms throw.

import { describe, expect, test } from "bun:test";
import { createMockClient, createMockContext } from "@trailblaze/scripting/testing";

import { contacts_launchApp } from "./contacts_launchApp";

describe("contacts_launchApp", () => {
  test("delegates to the iOS impl on an iOS device", async () => {
    const client = createMockClient();

    await contacts_launchApp({}, createMockContext({ platform: "ios" }), client);

    expect(client.calls.map((c) => c.tool)).toEqual(["contacts_ios_launchApp"]);
  });

  test("delegates to the Android impl on an Android device", async () => {
    const client = createMockClient();

    await contacts_launchApp({}, createMockContext({ platform: "android" }), client);

    expect(client.calls.map((c) => c.tool)).toEqual(["contacts_android_launchApp"]);
  });

  test("throws on an unsupported platform", async () => {
    const client = createMockClient();

    expect(contacts_launchApp({}, createMockContext({ platform: "web" }), client)).rejects.toThrow(
      "contacts_launchApp supports android and ios",
    );
  });
});
