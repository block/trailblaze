// Pure-JS tool — no TypeScript, no SDK imports, no build step. The simplest possible
// surface: populate `globalThis.__trailblazeTools` directly. Whatever evaluates this file
// inside QuickJS picks the registration up immediately.
//
// This flavor exists for authors who don't want the TS toolchain at all — drop a `.js` file
// next to your pack, the runtime evaluates it, your tool is registered. Same dispatch path
// as the bundled-from-TS flavor; the runtime doesn't know or care which one produced the
// registration.

(function registerPureJsTools() {
  const tools = (globalThis.__trailblazeTools = globalThis.__trailblazeTools || {});

  tools["sampleApp_reverseString"] = {
    name: "sampleApp_reverseString",
    spec: {
      description:
        "Reverses the given input string. Pure-JS demo tool — no TypeScript, no SDK, " +
        "just a globalThis registration. Useful as a smoke check that the QuickJS runtime " +
        "is dispatching tool calls correctly.",
      inputSchema: {
        text: { type: "string", description: "The string to reverse." },
      },
    },
    handler: async function (args) {
      const text = String(args && args.text != null ? args.text : "");
      return {
        content: [{ type: "text", text: text.split("").reverse().join("") }],
        isError: false,
      };
    },
  };

  tools["sampleApp_addNumbers"] = {
    name: "sampleApp_addNumbers",
    spec: {
      description: "Adds two numbers and returns their sum as text.",
      inputSchema: {
        a: { type: "number", description: "First addend." },
        b: { type: "number", description: "Second addend." },
      },
    },
    handler: async function (args) {
      const a = Number(args && args.a);
      const b = Number(args && args.b);
      return {
        content: [{ type: "text", text: String(a + b) }],
        isError: false,
      };
    },
  };
})();
