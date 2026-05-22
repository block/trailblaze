const mockNoopLogger = {
  debug: () => {
  },
  info: () => {
  },
  warn: () => {
  },
  error: () => {
  }
};
function createMockClient() {
  const calls = [];
  const stubs = /* @__PURE__ */ new Map();
  const dispatch = async (name, args) => {
    calls.push({ tool: name, args });
    const stub = stubs.get(name);
    if (stub && stub.errorMessage && stub.errorMessage.length > 0) {
      throw new Error(
        `trailblaze.client.callTool("${name}") tool failed: ${stub.errorMessage}`
      );
    }
    return {
      success: true,
      textContent: stub?.textContent ?? "",
      errorMessage: ""
    };
  };
  const tools = createMockToolsProxy(dispatch);
  const mock = {
    tools,
    calls,
    stub(toolName, response) {
      if (toolName.trim() === "") {
        throw new Error(
          "createMockClient: stub() tool name must not be empty or whitespace-only."
        );
      }
      stubs.set(toolName, response);
    },
    reset() {
      calls.length = 0;
      stubs.clear();
    }
  };
  return mock;
}
const MOCK_TOOLS_PROXY_RESERVED_PROPS = /* @__PURE__ */ new Set([
  "then",
  "catch",
  "finally",
  "constructor",
  "prototype",
  "__proto__",
  "toString",
  "valueOf",
  "toJSON"
]);
function createMockToolsProxy(dispatch) {
  return new Proxy({}, {
    get(_target, prop, _receiver) {
      if (typeof prop !== "string") return void 0;
      if (MOCK_TOOLS_PROXY_RESERVED_PROPS.has(prop)) return void 0;
      if (prop.trim() === "") {
        throw new Error(
          `mockClient.tools[${JSON.stringify(prop)}]: tool name must not be empty or whitespace-only.`
        );
      }
      return (args) => dispatch(prop, args ?? {});
    }
  });
}
function createMockContext(opts) {
  const device = {
    platform: opts.platform,
    widthPixels: opts.device?.widthPixels ?? 1080,
    heightPixels: opts.device?.heightPixels ?? 2400,
    driverType: opts.device?.driverType ?? "mock-driver"
  };
  return {
    baseUrl: opts.baseUrl,
    runtime: opts.runtime === "ondevice" ? "ondevice" : void 0,
    sessionId: opts.sessionId ?? "mock-session",
    invocationId: opts.invocationId ?? "mock-invocation",
    device,
    target: opts.target,
    memory: opts.memory ?? {},
    logger: mockNoopLogger
  };
}
export {
  createMockClient,
  createMockContext
};
