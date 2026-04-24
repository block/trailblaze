// MCP tools for the Trailblaze sample-app example.
//
// This file is the "real" (not fixture) reference for how anyone authoring an opensource
// Trailblaze target can ship TypeScript tools alongside their app. The host spawns this as a
// subprocess at session start, runs the MCP `initialize` handshake, and registers every tool
// advertised here into the session's tool repo. From there the LLM can call them just like
// any Kotlin-defined tool.
//
// Tools advertised:
//   generateTestUser    Returns a fresh random {name, email} pair. Use this whenever a trail
//                       needs a throwaway identity (signup, forms, login). Each call returns
//                       a different user so tests stay hermetic.
//   currentEpochMillis  Returns the current Unix epoch in milliseconds as a string. Useful
//                       for date-picker tests that need to know "what's 'today'" or to stamp
//                       test artifacts with a timestamp.
//
// Run via `bun run tools.ts` (preferred — bun executes TS directly) or, if you don't have
// bun, install `tsx` globally (`npm i -g tsx`) and invoke `tsx tools.ts`. Install deps first:
// `bun install` (or `npm install`) in this directory.

import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";

const server = new McpServer(
  { name: "sample-app-mcp-tools", version: "0.1.0" },
  { capabilities: { tools: {} } },
);

// Small pool so the generated names feel realistic without pulling in a full faker dep. The
// important property for a reference example is "every call returns different output" — not
// linguistic variety. Swap in `@faker-js/faker` if you want richer data in your own targets.
const FIRST_NAMES = [
  "Alex", "Sam", "Jordan", "Taylor", "Casey", "Morgan", "Riley", "Jamie", "Quinn", "Reese",
];
const LAST_NAMES = [
  "Carter", "Nguyen", "Patel", "Okafor", "Hernandez", "Kim", "Silva", "Müller", "Kowalski", "Tanaka",
];

function pick<T>(xs: readonly T[]): T {
  return xs[Math.floor(Math.random() * xs.length)]!;
}

server.registerTool(
  "generateTestUser",
  {
    description:
      "Generates a random test user with {name, email}. Use whenever a trail needs a fresh identity " +
      "(signup, form entry, login flows). Each call returns a different user.",
    inputSchema: {},
  },
  async () => {
    const first = pick(FIRST_NAMES);
    const last = pick(LAST_NAMES);
    const name = `${first} ${last}`;
    // Lowercase + 4-digit salt so multiple calls in one session don't collide.
    const salt = Math.floor(Math.random() * 10000).toString().padStart(4, "0");
    const email = `${first}.${last}.${salt}@example.com`.toLowerCase();
    return {
      content: [{ type: "text", text: JSON.stringify({ name, email }) }],
      isError: false,
    };
  },
);

server.registerTool(
  "currentEpochMillis",
  {
    description:
      "Returns the current Unix epoch time in milliseconds, as a string. Useful for date-picker " +
      "tests or stamping test artifacts with a timestamp.",
    inputSchema: {},
  },
  async () => ({
    content: [{ type: "text", text: String(Date.now()) }],
    isError: false,
  }),
);

const transport = new StdioServerTransport();
await server.connect(transport);
