(() => {
  // ../../../../../../../../../sdks/typescript-tools/src/index.ts
  function registry() {
    const g = globalThis;
    return g.__trailblazeTools ?? (g.__trailblazeTools = {});
  }
  async function callOtherTool(name, args) {
    const g = globalThis;
    if (typeof g.__trailblazeCall !== "function") {
      throw new Error(
        "trailblaze.call: host binding `__trailblazeCall` not installed \u2014 this bundle is running outside a Trailblaze runtime context."
      );
    }
    const resultJson = await g.__trailblazeCall(name, JSON.stringify(args));
    try {
      return JSON.parse(resultJson);
    } catch (e) {
      const message = e instanceof Error ? e.message : String(e);
      throw new Error(
        `trailblaze.call: host returned malformed JSON for tool "${name}": ${message}`
      );
    }
  }
  function createToolsProxy() {
    return new Proxy({}, {
      get(_target, name) {
        if (typeof name !== "string") return void 0;
        return (args) => callOtherTool(name, args);
      }
    });
  }
  var trailblaze = {
    /** Register a tool with the runtime. Idempotent — re-registering with the same name overwrites. */
    tool(name, spec, handler) {
      registry()[name] = { name, spec, handler };
    },
    /** Type-safe tool composition: `trailblaze.tools.maestro({...})`. Prefer this over [call]. */
    tools: createToolsProxy(),
    /**
     * Low-level untyped composition by string name. Prefer [tools] — this exists only for tools the
     * generated typed surface doesn't cover yet, and is slated to become internal.
     */
    call: callOtherTool
  };
  var tool = trailblaze.tool;
  var tools = trailblaze.tools;
  var call = trailblaze.call;

  // openUrl.ts
  trailblaze.tool(
    "openUrl",
    {
      description: "Opens the browser to the provided url.",
      inputSchema: {
        type: "object",
        properties: {
          url: {
            type: "string",
            description: "The URL to open that starts with https"
          }
        },
        required: ["url"]
      },
      _meta: { "trailblaze/supportedPlatforms": ["android", "ios"] }
    },
    async (args) => {
      const url = String(args.url ?? "").trim();
      if (!url) {
        return {
          content: [{ type: "text", text: "openUrl requires a non-empty `url` argument." }],
          isError: true
        };
      }
      await trailblaze.tools.maestro({ commands: [{ openLink: url }] });
      return { content: [{ type: "text", text: `Opened ${url}` }] };
    }
  );
})();
