// Sample test for the `requireFields` per-field probe loop and the tool-wide scope of
// `client.stub` as a fault-injection lever.
//
// The default mock client returns success for every dispatch, so the "all fields visible"
// happy path is the natural default and is asserted directly. Injecting a per-tool stub
// with `errorMessage` flips every `assertVisibleWithAccessibilityText` call to throw —
// **including** the up-front name assertion the tool runs before entering the
// requireFields loop. That tool-name-wide scope is precisely what the third test below
// documents: the stub short-circuits the tool at the name probe, so the multi-field
// "missing fields: …" error path inside the loop is not exercisable here without a
// sequence-aware mock. The test asserts on the observed call shape rather than overselling
// what's covered.
//
// Run via:
//
//   ./trailblaze check contacts

import { describe, expect, test } from "bun:test";
import { createMockClient, createMockContext } from "@trailblaze/scripting/testing";

import { contacts_ios_verifyContactStructure } from "./contacts_ios_verifyContactStructure";

describe("contacts_ios_verifyContactStructure", () => {
  test("dispatches a single name assertion when requireFields is omitted", async () => {
    const client = createMockClient();
    const ctx = createMockContext({ platform: "ios" });

    const result = await contacts_ios_verifyContactStructure(
      { name: "Apple Inc." },
      ctx,
      client,
    );

    // No per-field probes when requireFields is absent — the tool's contract is "is the
    // detail screen showing the right contact?" and nothing more.
    expect(client.calls.map((c) => c.tool)).toEqual([
      "assertVisibleWithAccessibilityText",
    ]);
    expect(client.calls[0]?.args).toMatchObject({ accessibilityText: "Apple Inc." });
    expect(result).toContain("detail screen rendered");
  });

  test("probes each requireFields entry after the name assertion", async () => {
    const client = createMockClient();
    const ctx = createMockContext({ platform: "ios" });

    const result = await contacts_ios_verifyContactStructure(
      { name: "Apple Inc.", requireFields: ["phone", "email", "work"] },
      ctx,
      client,
    );

    // Name assertion + one probe per requireFields entry, in declaration order. The
    // default mock returns success for every dispatch, so every probe succeeds and the
    // tool returns the verified-with-fields message.
    expect(client.calls.map((c) => c.tool)).toEqual([
      "assertVisibleWithAccessibilityText",
      "assertVisibleWithAccessibilityText",
      "assertVisibleWithAccessibilityText",
      "assertVisibleWithAccessibilityText",
    ]);
    expect(client.calls.map((c) => c.args.accessibilityText)).toEqual([
      "Apple Inc.",
      "phone",
      "email",
      "work",
    ]);
    expect(result).toContain("phone");
    expect(result).toContain("email");
    expect(result).toContain("work");
  });

  test("a tool-name-wide stub on assertVisibleWithAccessibilityText short-circuits on the name probe before requireFields runs", async () => {
    // `client.stub` injects a failure into every `assertVisibleWithAccessibilityText`
    // dispatch — including the up-front name assertion. The tool's name-assertion call
    // doesn't run through `textIsVisible` / `tryOrFalse`, so the stub causes that first
    // assertion to throw and the tool to bail out before reaching the requireFields
    // loop. This documents the tool-name-wide scope of the stub mechanism; a
    // sequence-aware stub (not yet supported by the mock client) would be needed to
    // drive the "name visible but fields missing" sub-case in isolation and demonstrate
    // the tool's multi-field "missing fields: …" error format.
    const client = createMockClient();
    client.stub("assertVisibleWithAccessibilityText", {
      textContent: "",
      errorMessage: "Text not found",
    });
    const ctx = createMockContext({ platform: "ios" });

    await expect(
      contacts_ios_verifyContactStructure(
        { name: "Nobody", requireFields: ["phone"] },
        ctx,
        client,
      ),
    ).rejects.toThrow(/tool failed: Text not found/);

    // The thrown name assertion stopped the tool before the per-field probes ran.
    expect(client.calls.map((c) => c.tool)).toEqual([
      "assertVisibleWithAccessibilityText",
    ]);
    expect(client.calls[0]?.args).toMatchObject({ accessibilityText: "Nobody" });
  });
});
