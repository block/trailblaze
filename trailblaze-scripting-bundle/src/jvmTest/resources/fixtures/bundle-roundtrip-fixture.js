// Hand-crafted MCP-speaking JS fixture for InProcessBundleRoundTripTest.
//
// Does NOT use the real @modelcontextprotocol/sdk — speaks MCP wire protocol directly
// against `globalThis.__trailblazeInProcessTransport` so the Kotlin-side transport +
// launcher + registration-filter plumbing is exercised without a bundled SDK
// dependency. The real SDK is exercised at the author-bundle build layer (PR A5's
// §BYO bundler) and tracked separately; the runtime's own responsibility ends at
// "JSON-RPC in, JSON-RPC out."
//
// Advertises three tools:
//   echoReverse              — no `_meta`; always registers; `tools/call` returns reversed text.
//   hostOnlyTool             — `_meta: { "trailblaze/requiresHost": true }`; skipped on-device.
//   instrumentationOnlyTool  — `_meta: { "trailblaze/supportedDrivers":
//                              ["android-ondevice-instrumentation"] }`; registers only for
//                              that driver.

(function() {
  const SERVER_INFO = { name: "trailblaze-fixture-bundle", version: "1.0.0" };
  const tools = [
    {
      name: "echoReverse",
      description: "Returns the input string reversed.",
      inputSchema: {
        type: "object",
        properties: { text: { type: "string", description: "Text to reverse" } },
        required: ["text"]
      }
    },
    {
      name: "hostOnlyTool",
      description: "Requires the host agent. Must not register on-device.",
      inputSchema: { type: "object", properties: {}, required: [] },
      _meta: { "trailblaze/requiresHost": true }
    },
    {
      name: "instrumentationOnlyTool",
      description: "Registers only for the instrumentation driver.",
      inputSchema: { type: "object", properties: {}, required: [] },
      _meta: { "trailblaze/supportedDrivers": ["android-ondevice-instrumentation"] }
    }
  ];

  const transport = globalThis.__trailblazeInProcessTransport;
  if (!transport) {
    throw new Error("Fixture bundle: __trailblazeInProcessTransport missing. Prelude must evaluate first.");
  }

  // MCP-like dispatcher. Not the real SDK — just enough wire-protocol behaviour for
  // the Kotlin Client's handshake and a tools/call round-trip. All handlers respond
  // synchronously via `transport.send`, so the Kotlin-side evaluate() picks up the
  // response in the same dispatch cycle.
  transport.onmessage = function(msg) {
    if (msg.method === "initialize") {
      transport.send({
        jsonrpc: "2.0",
        id: msg.id,
        result: {
          protocolVersion: "2025-06-18",
          capabilities: { tools: {} },
          serverInfo: SERVER_INFO
        }
      });
      return;
    }
    if (msg.method === "notifications/initialized") {
      // Notifications don't get a response.
      return;
    }
    if (msg.method === "tools/list") {
      transport.send({
        jsonrpc: "2.0",
        id: msg.id,
        result: { tools: tools }
      });
      return;
    }
    if (msg.method === "tools/call") {
      const params = msg.params;
      if (params.name === "echoReverse") {
        const input = (params.arguments && params.arguments.text) || "";
        const reversed = input.split("").reverse().join("");
        transport.send({
          jsonrpc: "2.0",
          id: msg.id,
          result: {
            content: [{ type: "text", text: reversed }],
            isError: false
          }
        });
        return;
      }
      // Unknown tool — surface as JSON-RPC error.
      transport.send({
        jsonrpc: "2.0",
        id: msg.id,
        error: { code: -32601, message: "Unknown tool: " + params.name }
      });
      return;
    }
    transport.send({
      jsonrpc: "2.0",
      id: msg.id,
      error: { code: -32601, message: "Unknown method: " + msg.method }
    });
  };
})();
