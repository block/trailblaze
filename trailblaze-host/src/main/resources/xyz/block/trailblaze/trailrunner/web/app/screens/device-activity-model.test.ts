import { describe, expect, test } from "bun:test";
import M from "./device-activity-model.js";

// Raw logs shaped like the daemon writes them (the `class` key is the log type).
const P = "xyz.block.trailblaze";
const at = (s: number) => new Date(Date.UTC(2026, 9, 7, 15, 0, s)).toISOString();
const started = (s: number, title?: string) => ({
  class: `${P}.logs.client.TrailblazeLog.TrailblazeSessionStatusChangeLog`,
  sessionStatus: { class: `${P}.logs.model.SessionStatus.Started`, trailConfig: title ? { title } : {} },
  timestamp: at(s),
});
const ended = (s: number, kind: string) => ({
  class: `${P}.logs.client.TrailblazeLog.TrailblazeSessionStatusChangeLog`,
  sessionStatus: { class: `${P}.logs.model.SessionStatus.Ended.${kind}` },
  timestamp: at(s),
});
const start = (s: number, promptStep: object) => ({
  class: `${P}.logs.client.TrailblazeLog.ObjectiveStartLog`, promptStep, timestamp: at(s),
});
const complete = (s: number, promptStep: object, ok: boolean, llmExplanation?: string) => ({
  class: `${P}.logs.client.TrailblazeLog.ObjectiveCompleteLog`,
  promptStep,
  objectiveResult: {
    class: ok ? `${P}.agent.model.AgentTaskStatus.Success.ObjectiveComplete` : `${P}.agent.model.AgentTaskStatus.Failure.ObjectiveFailed`,
    llmExplanation,
  },
  timestamp: at(s),
});
const tool = (s: number, toolName: string, trailblazeTool: object, extra: object = {}) => ({
  class: `${P}.logs.client.TrailblazeLog.TrailblazeToolLog`, toolName, trailblazeTool, successful: true, durationMs: 800, timestamp: at(s), ...extra,
});
const llm = (s: number, ...names: string[]) => ({
  class: `${P}.logs.client.TrailblazeLog.TrailblazeLlmRequestLog`,
  actions: names.map((name) => ({ name, args: {} })),
  viewHierarchy: { huge: true },
  timestamp: at(s),
});
const goStep = { class: `${P}.yaml.DirectionStep`, step: "Go to example.com" };
const verifyStep = { class: `${P}.yaml.VerificationStep`, verify: "The page shows a login form" };
const recordedStep = { class: `${P}.yaml.DirectionStep`, step: "Run tapOnPoint", recording: { tools: [] } };

const fold = (logs: object[]) => M.fold(logs.map(M.slimLog));

describe("a step the AI drives", () => {
  const logs = [
    started(0, "Prompt"),
    start(1, goStep),
    llm(2, "web_navigate"),
    tool(3, "web_navigate", { url: "https://example.com", reasoning: "go there" }),
    llm(4, "objectiveStatus"),
    tool(5, "objectiveStatus", { explanation: "Navigated to example.com", status: "COMPLETED" }),
    complete(6, goStep, true, "Navigated to example.com"),
  ];

  test("lists the tools it ran under the step, without the LLM-only reasoning or the status report", () => {
    const a = fold(logs);
    expect(a.title).toBe("Prompt");
    expect(a.items).toHaveLength(1);
    const step = a.items[0];
    expect(step).toMatchObject({ kind: "step", stepKind: "step", text: "Go to example.com", state: "passed" });
    expect(step.tools.map((t: { name: string; args: string }) => [t.name, t.args])).toEqual([["web_navigate", "url: https://example.com"]]);
    expect(step.note).toBe("Navigated to example.com");
    expect(a.now).toBeNull();
  });

  test("while it runs, names the tool the AI just chose, then says it is choosing the next one", () => {
    expect(fold(logs.slice(0, 2)).now).toEqual({ label: "AI is reading the screen", tool: null });
    expect(fold(logs.slice(0, 3)).now).toEqual({ label: "Running web_navigate", tool: "web_navigate" });
    expect(fold(logs.slice(0, 4)).now).toEqual({ label: "AI is choosing the next action", tool: null });
    // Reporting the outcome is not a tool the device runs.
    expect(fold(logs.slice(0, 5)).now!.tool).toBeNull();
    expect(fold(logs.slice(0, 3)).items[0].state).toBe("running");
  });
});

describe("a verify that fails", () => {
  test("is failed, with the AI's reason as its note", () => {
    const a = fold([
      start(1, verifyStep),
      llm(2, "web_verifyElementVisible"),
      tool(3, "web_verifyElementVisible", { ref: 'heading "Log in"' }),
      complete(4, verifyStep, false, "No login form or login fields are present."),
    ]);
    expect(a.items[0]).toMatchObject({ stepKind: "verify", text: "The page shows a login form", state: "failed", note: "No login form or login fields are present." });
  });
});

describe("recorded tools and mirror input", () => {
  test("a recorded step replays without the AI, and says so while it runs", () => {
    expect(fold([start(1, recordedStep)]).now).toEqual({ label: "Replaying recorded tools", tool: null });
  });

  test("a recorded tool's arguments come from its raw form, and a failure keeps the tool's reason", () => {
    const a = fold([
      start(1, recordedStep),
      tool(2, "tapOnPoint", { toolName: "tapOnPoint", raw: { x: 938, y: 244 } }, { successful: false, exceptionMessage: "Nothing at that point" }),
      complete(3, recordedStep, false),
    ]);
    expect(a.items[0].tools[0]).toMatchObject({ name: "tapOnPoint", args: "x: 938, y: 244", ok: false, error: "Nothing at that point" });
  });

  test("a tap from the mirror, outside any step, is its own row in order", () => {
    const a = fold([
      tool(1, "tapOn", { toolName: "tapOn", raw: { selector: { textRegex: "Messages" } } }),
      start(2, recordedStep),
      complete(3, recordedStep, true),
      tool(4, "pressKey", { raw: { keyCode: "HOME" } }),
    ]);
    expect(a.items.map((i: { kind: string; name?: string }) => i.kind + (i.name ? ":" + i.name : ""))).toEqual(["tool:tapOn", "step", "tool:pressKey"]);
    expect(a.items[0].args).toBe('selector: {"textRegex":"Messages"}');
  });
});

describe("session edges", () => {
  test("a session that ends mid-step stops the step and has nothing running", () => {
    const a = fold([start(1, goStep), llm(2, "web_navigate"), ended(3, "Cancelled")]);
    expect(a.ended).toBe("failed");
    expect(a.items[0].state).toBe("stopped");
    expect(a.now).toBeNull();
  });

  test("a session that succeeds reads as passed", () => {
    expect(fold([started(0), ended(1, "Succeeded")]).ended).toBe("passed");
  });

  test("joining mid-step, a complete with no start still shows the step and how it went", () => {
    const a = fold([complete(5, verifyStep, true, "Seen")]);
    expect(a.items[0]).toMatchObject({ stepKind: "verify", state: "passed", note: "Seen" });
  });

  test("keeps nothing of the large records but the tool names", () => {
    expect(M.slimLog(llm(1, "web_click"))).toEqual({ cls: "llm", ts: Date.parse(at(1)), actions: ["web_click"] });
    expect(M.slimLog({ class: `${P}.logs.client.TrailblazeLog.AgentDriverLog`, timestamp: at(1) })).toBeNull();
  });

  test("long arguments are cut to one readable line", () => {
    const s = M.argsSummary({ text: "x".repeat(400) });
    expect(s.length).toBe(140);
    expect(s.endsWith("…")).toBe(true);
  });
});

describe("which sessions a device's feed follows", () => {
  const now = 1_000_000_000;
  const row = (id: string, deviceInstanceId: string, minutesAgo: number, status = "passed") =>
    ({ id, deviceInstanceId, timestampMs: now - minutesAgo * 60_000, status });

  test("its recent and still-running sessions, oldest first, newest kept when over the cap", () => {
    const rows = [
      row("other-device", "emulator-5556", 1),
      row("recent", "emulator-5554", 5),
      row("old", "emulator-5554", 300),
      row("old-but-running", "emulator-5554", 400, "running"),
      row("newest", "emulator-5554", 1),
    ];
    expect(M.sessionsForDevice(rows, "emulator-5554", now, 60 * 60_000, 5).map((s: { id: string }) => s.id))
      .toEqual(["old-but-running", "recent", "newest"]);
    expect(M.sessionsForDevice(rows, "emulator-5554", now, 60 * 60_000, 2).map((s: { id: string }) => s.id))
      .toEqual(["recent", "newest"]);
  });
});

describe("prompts and run errors", () => {
  test("a typed step or verify becomes an unrecorded one-step trail", () => {
    expect(Bun.YAML.parse(M.promptYaml("step", 'Tap "Sign in"'))).toEqual({ trail: [{ step: 'Tap "Sign in"' }] });
    expect(Bun.YAML.parse(M.promptYaml("verify", "The cart is empty"))).toEqual({ trail: [{ verify: "The cart is empty" }] });
    expect(M.promptYaml("step", "   ")).toBe("");
  });

  test("a run failure reads as the tool's own reason", () => {
    const daemon = "Trail run failed: Failed to run recording for prompt step (step 1 of 1): prompt: Run web_verifyElementVisible " +
      "recorded tools: web_verifyElementVisible failed tool: web_verifyElementVisible failure: Assertion failed: element 'zzz' is not visible on the page.";
    expect(M.runErrorSummary(daemon)).toBe("Assertion failed: element 'zzz' is not visible on the page.");
    expect(M.runErrorSummary('Trail run failed: Failed to successfully run prompt with AI (step 1 of 1) {\n "class": "x"\n}'))
      .toBe("The step failed. The activity below says why.");
    expect(M.runErrorSummary("Trail run failed: Device not connected")).toBe("Device not connected");
  });
});

describe("session headings", () => {
  const t = Date.UTC(2026, 9, 7, 15, 55);
  test("a device's live session is named by when it began, not its first run", () => {
    expect(M.sessionLabel("2026_10_07_11_55_18_recording_6405", "Run web_verifyElementVisible", "Run web_verifyElementVisible", t))
      .toMatch(/^Live session · started /);
  });
  test("a trail run keeps its title, and a runner's class:method name does not count as one", () => {
    expect(M.sessionLabel("2026_10_07_session_1", "Checkout with a card", null, t)).toBe("Checkout with a card");
    expect(M.sessionLabel("2026_10_07_session_1", "BasePlaywrightNativeTest:run", null, t)).toMatch(/^Live session/);
    expect(M.sessionLabel("2026_10_07_session_1", null, null, 0)).toBe("Live session");
  });
});
