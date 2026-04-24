// TypeScript MCP server used by SubprocessRuntimeEndToEndTest's opt-in SDK path.
//
// Mirrors fixture.js one-for-one so both the vanilla-JS hand-rolled fixture and this real-SDK
// fixture exercise the same three tools through the Kotlin client. The JS fixture catches
// Kotlin-side wire regressions without an npm install step; this fixture catches drift in the
// `@modelcontextprotocol/sdk` author surface Trailblaze tool authors will actually use.
//
// Tools advertised (must stay in lockstep with fixture.js):
//   echo        tier 1, no Trailblaze metadata. Echoes the `message` argument.
//   hostOnly    tier 2, `_meta["trailblaze/requiresHost"]: true` so host-agent sessions keep
//               it and on-device-agent sessions drop it.
//   memoryTap   tier 2. Reads `_trailblazeContext` off its arguments and returns the session's
//               device.platform + memory["probe"] so the test can verify envelope plumbing.
//
// Run via `bun run fixture.ts` (preferred) or `tsx fixture.ts` if `tsx` is installed globally.
// The opt-in gate on the Kotlin side only activates when `node_modules/` exists next to this
// file.
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";

// Single source of truth for the `_trailblazeContext` shape. The `TrailblazeContext` type is
// derived from the Zod schema via `z.infer` so the type and runtime validator can't drift.
// A longer-term direction is a shared proto that generates both the Kotlin side and this
// TypeScript side — tracked separately; `z.infer` is the simple step for today.
const trailblazeContextSchema = z
  .object({
    device: z.object({ platform: z.string().optional() }).optional(),
    memory: z.record(z.string(), z.unknown()).optional(),
  })
  .passthrough();

type TrailblazeContext = z.infer<typeof trailblazeContextSchema>;

const server = new McpServer(
  { name: "trailblaze-fixture-ts", version: "0.0.1" },
  { capabilities: { tools: {} } },
);

server.registerTool(
  "echo",
  {
    description: "Echo back the message argument",
    inputSchema: { message: z.string().describe("text to echo") },
  },
  async ({ message }) => ({
    content: [{ type: "text", text: String(message) }],
    isError: false,
  }),
);

server.registerTool(
  "hostOnly",
  {
    description: "Host-agent-only demo tool",
    inputSchema: {},
    _meta: { "trailblaze/requiresHost": true },
  },
  async () => ({
    content: [{ type: "text", text: "host tool ran" }],
    isError: false,
  }),
);

// memoryTap reads the reserved `_trailblazeContext` key the host injects into arguments. The
// SDK's Zod inputSchema would strip unknown keys, so declare `_trailblazeContext` as an
// explicit optional passthrough field and let the SDK round-trip it untouched.
server.registerTool(
  "memoryTap",
  {
    description: "Reads _trailblazeContext and returns a summary",
    inputSchema: {
      _trailblazeContext: trailblazeContextSchema.optional(),
    },
  },
  async (args) => {
    const ctx = (args as { _trailblazeContext?: TrailblazeContext })._trailblazeContext;
    if (!ctx) {
      return {
        content: [{ type: "text", text: "no _trailblazeContext" }],
        isError: true,
      };
    }
    const platform = ctx.device?.platform ?? "<missing>";
    const probe = (ctx.memory?.probe as string | undefined) ?? "<missing>";
    return {
      content: [{ type: "text", text: `platform=${platform} probe=${probe}` }],
      isError: false,
    };
  },
);

const transport = new StdioServerTransport();
await server.connect(transport);
