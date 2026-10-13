import { describe, expect, test } from "bun:test";
import M from "./device-console-model.js";

// Bun's built-in YAML parser stands in for js-yaml (a CDN global in the app). `calls` is the tool
// list the one step records for the device's platform.
const parse = (yaml: string) => Bun.YAML.parse(yaml) as any;
const calls = (yaml: string, classifier = "web") => parse(yaml).trail[0].recording[classifier];

describe("pointer on the mirror", () => {
  const rect = { left: 100, top: 50, width: 360, height: 780 };

  test("maps a client point to device pixels at the device's own resolution", () => {
    expect(M.toDevicePoint(rect, 100 + 180, 50 + 390, 1080, 2340)).toEqual({ x: 540, y: 1170 });
  });

  test("clamps a point dragged off the mirror to the screen edge", () => {
    expect(M.toDevicePoint(rect, 0, 2000, 1080, 2340)).toEqual({ x: 0, y: 2339 });
  });

  test("has no point before the mirror has a size or the device reported one", () => {
    expect(M.toDevicePoint({ ...rect, width: 0 }, 150, 60, 1080, 2340)).toBeNull();
    expect(M.toDevicePoint(rect, 150, 60, 0, 0)).toBeNull();
  });

  test("a quick press that barely moves is a tap at the press point", () => {
    expect(M.gestureFor({ x: 500, y: 900 }, { x: 510, y: 905 }, 90, 1080)).toEqual({ type: "tap", x: 500, y: 900 });
  });

  test("holding still past the threshold is a long press", () => {
    expect(M.gestureFor({ x: 500, y: 900 }, { x: 502, y: 900 }, 800, 1080)).toEqual({ type: "longPress", x: 500, y: 900 });
  });

  test("travelling far enough is a swipe whose duration follows the drag, within bounds", () => {
    const g = M.gestureFor({ x: 540, y: 1800 }, { x: 540, y: 600 }, 300, 1080);
    expect(g).toEqual({ type: "swipe", startX: 540, startY: 1800, endX: 540, endY: 600, durationMs: 300 });
    expect(M.gestureFor({ x: 0, y: 0 }, { x: 900, y: 0 }, 20, 1080).durationMs).toBe(120);
    expect(M.gestureFor({ x: 0, y: 0 }, { x: 900, y: 0 }, 9000, 1080).durationMs).toBe(1200);
  });

  test("the swipe threshold scales with the device, so a small drag on a phone is still a tap", () => {
    // 50px is under 6% of a 1080-wide phone but over 6% of a 390-wide one.
    expect(M.gestureFor({ x: 0, y: 0 }, { x: 50, y: 0 }, 100, 1080).type).toBe("tap");
    expect(M.gestureFor({ x: 0, y: 0 }, { x: 50, y: 0 }, 100, 390).type).toBe("swipe");
  });
});

describe("device ids and keys", () => {
  test("a device row becomes the daemon's id with the platform upper-cased", () => {
    expect(M.deviceIdFor({ id: "emulator-5554", platform: "android" })).toEqual({
      instanceId: "emulator-5554",
      trailblazeDevicePlatform: "ANDROID",
    });
    expect(M.deviceIdFor(null)).toBeNull();
  });

  test("only keys the platform's connection can press get a button", () => {
    expect(M.keysFor("android")).toEqual(["Back", "Home", "Enter"]);
    // An iOS connection has no Home key to press, so a Home button would do nothing and report success.
    expect(M.keysFor("ios")).toEqual(["Enter"]);
    expect(M.keysFor("web")).toEqual(["Enter"]);
  });
});

describe("tool call YAML", () => {
  const tapOnPoint = {
    name: "tapOnPoint",
    requiredParameters: [
      { name: "x", type: "Int" },
      { name: "y", type: "Int" },
    ],
    optionalParameters: [{ name: "longPress", type: "Boolean" }],
  };

  test("is a one-step unified trail recorded for the device's platform, so it replays without the LLM", () => {
    const doc = parse(M.toolYaml(tapOnPoint, { x: "1", y: "2" }, "ANDROID"));
    expect(doc).toEqual({
      config: { title: "Run tapOnPoint" },
      trail: [{ step: "Run tapOnPoint", recording: { android: [{ tapOnPoint: { x: 1, y: 2 } }] } }],
    });
  });

  test("writes required params with numbers bare and leaves blank optionals out", () => {
    const yaml = M.toolYaml(tapOnPoint, { x: "540", y: "1170", longPress: "" });
    expect(calls(yaml)).toEqual([{ tapOnPoint: { x: 540, y: 1170 } }]);
  });

  test("writes a filled-in optional boolean as a boolean", () => {
    const yaml = M.toolYaml(tapOnPoint, { x: "1", y: "2", longPress: "TRUE" });
    expect(calls(yaml)).toEqual([{ tapOnPoint: { x: 1, y: 2, longPress: true } }]);
  });

  test("quotes text that only looks like a number or boolean when the param is a string", () => {
    const tool = { name: "inputText", requiredParameters: [{ name: "text", type: "String" }] };
    expect(calls(M.toolYaml(tool, { text: "007" }))).toEqual([{ inputText: { text: "007" } }]);
    expect(calls(M.toolYaml(tool, { text: "true" }))).toEqual([{ inputText: { text: "true" } }]);
    expect(calls(M.toolYaml(tool, { text: 'say "hi"\nthen go' }))).toEqual([
      { inputText: { text: 'say "hi"\nthen go' } },
    ]);
  });

  test("a number param holding something that isn't a number is quoted, so the daemon reports it", () => {
    const yaml = M.toolYaml(tapOnPoint, { x: "abc", y: "2" });
    expect(calls(yaml)).toEqual([{ tapOnPoint: { x: "abc", y: 2 } }]);
  });

  test("nests dotted params under their parent", () => {
    const tool = {
      name: "tapOnElementBySelector",
      requiredParameters: [{ name: "selector.textRegex", type: "String" }],
      optionalParameters: [
        { name: "selector.index", type: "Int" },
        { name: "longPress", type: "Boolean" },
      ],
    };
    const yaml = M.toolYaml(tool, { "selector.textRegex": "Sign in", "selector.index": "1", longPress: "" });
    expect(calls(yaml)).toEqual([{ tapOnElementBySelector: { selector: { textRegex: "Sign in", index: 1 } } }]);
  });

  test("an object or list parameter given as JSON is written as that mapping or list", () => {
    const tool = {
      name: "tapOnElement",
      requiredParameters: [{ name: "selector", type: "OBJECT" }],
      optionalParameters: [{ name: "steps", type: "Array" }, { name: "label", type: "String" }],
    };
    const yaml = M.toolYaml(tool, { selector: '{"textRegex": "Sign in"}', steps: '["a", "b"]', label: '{"not": "parsed"}' });
    expect(calls(yaml)).toEqual([
      { tapOnElement: { selector: { textRegex: "Sign in" }, steps: ["a", "b"], label: '{"not": "parsed"}' } },
    ]);
  });

  test("an object parameter that isn't valid JSON stays the text typed, for the daemon to report", () => {
    const tool = { name: "t", requiredParameters: [{ name: "selector", type: "OBJECT" }] };
    expect(calls(M.toolYaml(tool, { selector: "{textRegex: Sign" }))).toEqual([{ t: { selector: "{textRegex: Sign" } }]);
  });

  test("a tool with nothing to set is written as an empty mapping", () => {
    expect(calls(M.toolYaml({ name: "hideKeyboard" }, {}))).toEqual([{ hideKeyboard: {} }]);
  });

  test("a param switched off by its sibling is not written even when it has a value", () => {
    const tool = {
      name: "swipe",
      requiredParameters: [{ name: "direction", type: "ENUM", validValues: ["UP", "DOWN"] }],
      optionalParameters: [
        { name: "distance", type: "Int", visibleWhen: { parameterName: "direction", values: ["DOWN"] } },
      ],
    };
    expect(calls(M.toolYaml(tool, { direction: "UP", distance: "300" }))).toEqual([{ swipe: { direction: "UP" } }]);
    expect(calls(M.toolYaml(tool, { direction: "DOWN", distance: "300" }))).toEqual([
      { swipe: { direction: "DOWN", distance: 300 } },
    ]);
  });
});

describe("tool form", () => {
  test("picks a control per parameter type", () => {
    expect(M.inputKindFor({ name: "d", type: "ENUM", validValues: ["UP"] })).toBe("select");
    expect(M.inputKindFor({ name: "b", type: "BOOLEAN" })).toBe("boolean");
    expect(M.inputKindFor({ name: "n", type: "Long" })).toBe("number");
    expect(M.inputKindFor({ name: "s", type: "string" })).toBe("text");
    expect(M.inputKindFor({ name: "o", type: "OBJECT" })).toBe("text");
  });

  test("leaves out the optional reasoning parameter, which only the LLM fills in", () => {
    const tool = {
      name: "web_click",
      optionalParameters: [{ name: "ref", type: "STRING" }, { name: "reasoning", type: "STRING" }],
    };
    expect(M.paramsOf(tool).map((p: { name: string }) => p.name)).toEqual(["ref"]);
    expect(calls(M.toolYaml(tool, { ref: "e5", reasoning: "because" }))).toEqual([{ web_click: { ref: "e5" } }]);
  });

  test("starts a required enum on its first value, a required boolean on false, everything else blank", () => {
    const tool = {
      name: "t",
      requiredParameters: [
        { name: "direction", type: "ENUM", validValues: ["UP", "DOWN"] },
        { name: "flag", type: "Boolean" },
        { name: "text", type: "String" },
      ],
      optionalParameters: [{ name: "mode", type: "ENUM", validValues: ["A"] }],
    };
    expect(M.initialValues(tool)).toEqual({ direction: "UP", flag: "false", text: "", mode: "" });
  });
});
