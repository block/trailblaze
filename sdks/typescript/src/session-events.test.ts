import { describe, expect, test } from "bun:test";

import {
  createSessionEvents,
  HOST_FUNCTION_SESSION_EVENTS_MARK,
  HOST_FUNCTION_SESSION_EVENTS_QUERY,
} from "./session-events.js";

function recordingHost() {
  const calls: Array<{ name: string; args: unknown }> = [];
  const host = new Proxy({} as Record<string, (args: unknown) => Promise<unknown>>, {
    get: (_t, name: string) => async (args: unknown) => {
      calls.push({ name, args });
      return { captured: true, events: [], cursor: 7, scan: {} };
    },
  });
  return { host, calls };
}

describe("ctx.events", () => {
  test("query sends the match with RegExps encoded, plus only the options given", async () => {
    const { host, calls } = recordingHost();
    const events = createSessionEvents(host);
    const result = await events.query("com.example.journeys", {
      match: { journeyName: "LOGIN", url: /\/v1\/login/i },
      since: "launched",
      waitMs: 5000,
    });
    expect(result.cursor).toBe(7);
    expect(calls).toEqual([
      {
        name: HOST_FUNCTION_SESSION_EVENTS_QUERY,
        args: {
          stream: "com.example.journeys",
          match: { journeyName: "LOGIN", url: { $regex: "\\/v1\\/login", $flags: "i" } },
          since: "launched",
          waitMs: 5000,
        },
      },
    ]);
  });

  test("an option the SDK doesn't know still reaches the host, so a typo is refused there", async () => {
    const { host, calls } = recordingHost();
    const events = createSessionEvents(host);
    await events.query("network", { wait: 5000 } as never);
    expect(calls).toEqual([{ name: HOST_FUNCTION_SESSION_EVENTS_QUERY, args: { stream: "network", wait: 5000 } }]);
  });

  test("count asks the host for a count, and mark sends the name", async () => {
    const { host, calls } = recordingHost();
    const events = createSessionEvents(host);
    await events.count("network", { after: 42 });
    await events.mark("launched");
    expect(calls).toEqual([
      { name: HOST_FUNCTION_SESSION_EVENTS_QUERY, args: { stream: "network", after: 42, count: true } },
      { name: HOST_FUNCTION_SESSION_EVENTS_MARK, args: { name: "launched" } },
    ]);
  });

  test("a function in a match is refused before anything crosses to the host", async () => {
    const { host, calls } = recordingHost();
    const events = createSessionEvents(host);
    await expect(
      events.query("network", { match: { status: ((v: unknown) => v === 200) as never } }),
    ).rejects.toThrow("ctx.events.query: a match can't hold a function");
    expect(calls).toEqual([]);
  });
});
