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
function createMockMemory(snapshot) {
  const map = /* @__PURE__ */ new Map();
  if (snapshot) {
    for (const [k, v] of Object.entries(snapshot)) {
      if (typeof v === "string") map.set(k, v);
    }
  }
  const get = (key) => map.get(key);
  const memory = {
    get,
    set(key, value) {
      map.set(key, value);
    },
    has(key) {
      return map.has(key);
    },
    keys() {
      return [...map.keys()];
    },
    delete(key) {
      map.delete(key);
    },
    interpolate(template) {
      let result = template;
      for (const re of [/\$\{([^}]+)\}/g, /\{\{([^}]+)\}\}/g]) {
        result = result.replace(re, (_match, name) => get(name) ?? "");
      }
      return result;
    },
    setJson(key, value) {
      map.set(key, JSON.stringify(value));
    },
    getJson(key) {
      const raw = map.get(key);
      if (raw === void 0) return void 0;
      try {
        return JSON.parse(raw);
      } catch {
        return void 0;
      }
    },
    toJSON() {
      return Object.fromEntries(map);
    }
  };
  return memory;
}
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
      errorMessage: "",
      structuredContent: stub?.structuredContent
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
const MOCK_SCRIPT_OVERLOAD_TOOLS = /* @__PURE__ */ new Set(["web_evaluate"]);
function mockBuildScriptOverloadArgs(toolName, firstArg, rest) {
  if (typeof firstArg === "function") {
    const fnSrc = firstArg.toString();
    const argsJson = JSON.stringify(rest);
    return { script: `(${fnSrc}).apply(null, ${argsJson})` };
  }
  if (typeof firstArg === "string") {
    return { script: firstArg };
  }
  if (firstArg !== null && typeof firstArg === "object") {
    return firstArg;
  }
  throw new Error(
    `mockClient.tools.${toolName}: expected a function, script string, or { script } object as the first argument; got ${firstArg === null ? "null" : typeof firstArg}.`
  );
}
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
      if (MOCK_SCRIPT_OVERLOAD_TOOLS.has(prop)) {
        const toolName = prop;
        return async (firstArg, ...rest) => {
          const args = mockBuildScriptOverloadArgs(toolName, firstArg, rest);
          const envelope = await dispatch(toolName, args);
          return mockUnwrapToolResult(envelope);
        };
      }
      return async (args) => {
        const envelope = await dispatch(prop, args ?? {});
        return mockUnwrapToolResult(envelope);
      };
    }
  });
}
function mockUnwrapToolResult(envelope) {
  if (envelope.structuredContent !== void 0 && envelope.structuredContent !== null) {
    return envelope.structuredContent;
  }
  return envelope.textContent;
}
function createQueuedFindMatchesClient() {
  const calls = [];
  const findMatchesQueue = [];
  const dispatch = (name, args) => {
    calls.push({ tool: name, args });
    if (name === "findMatches") {
      if (findMatchesQueue.length === 0) {
        throw new Error(
          `createQueuedFindMatchesClient: no more findMatches responses queued; received call with args=${JSON.stringify(args)}. Did the test forget a queueFindMatches?`
        );
      }
      return findMatchesQueue.shift();
    }
    return "";
  };
  const tools = new Proxy({}, {
    get(_target, prop, _receiver) {
      if (typeof prop !== "string") return void 0;
      if (prop === "then" || prop === "catch" || prop === "finally") return void 0;
      return async (args) => dispatch(prop, args ?? {});
    }
  });
  return {
    tools,
    calls,
    queueFindMatches(responses) {
      findMatchesQueue.push(...responses);
    }
  };
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
    memory: createMockMemory(opts.memory),
    logger: mockNoopLogger
  };
}
export {
  createMockClient,
  createMockContext,
  createQueuedFindMatchesClient
};
