(() => {
  var __defProp = Object.defineProperty;
  var __export = (target, all) => {
    for (var name in all)
      __defProp(target, name, { get: all[name], enumerable: true });
  };

  // openUrl.ts
  var openUrl_exports = {};
  __export(openUrl_exports, {
    openUrl: () => openUrl
  });

  // ../../../../../../../../../sdks/typescript/src/memory.ts
  var DRAIN_DELTA = /* @__PURE__ */ Symbol.for("trailblaze.memory.drainDelta");
  var INTERPOLATE_PATTERNS = [
    /\$\{([^}]+)\}/g,
    /\{\{([^}]+)\}\}/g
  ];
  function createMemory(snapshot) {
    const frozenSnapshot = new Map(
      snapshot ? Object.entries(snapshot).filter(
        // Defensive — the snapshot is supposed to be `Record<string, string>` from the
        // Kotlin side, but `fromMeta` receives raw JSON. Skip non-string values rather
        // than coercing so a producer-side bug surfaces as a missing key instead of a
        // silently corrupted value.
        ([, v]) => typeof v === "string"
      ) : []
    );
    const DELETED = /* @__PURE__ */ Symbol("deleted");
    const buffer = /* @__PURE__ */ new Map();
    const get = (key) => {
      if (buffer.has(key)) {
        const v = buffer.get(key);
        return v === DELETED ? void 0 : v;
      }
      return frozenSnapshot.get(key);
    };
    const has = (key) => {
      if (buffer.has(key)) return buffer.get(key) !== DELETED;
      return frozenSnapshot.has(key);
    };
    const set = (key, value) => {
      buffer.set(key, value);
    };
    const del = (key) => {
      buffer.set(key, DELETED);
    };
    const keys = () => {
      const visible = new Set(frozenSnapshot.keys());
      buffer.forEach((v, k) => {
        if (v === DELETED) visible.delete(k);
        else visible.add(k);
      });
      return [...visible];
    };
    const interpolate = (template) => {
      let result = template;
      for (const pattern of INTERPOLATE_PATTERNS) {
        const re = new RegExp(pattern.source, "g");
        result = result.replace(re, (_match, name) => get(name) ?? "");
      }
      return result;
    };
    const setJson = (key, value) => {
      const serialized = JSON.stringify(value);
      if (serialized === void 0) {
        del(key);
        return;
      }
      set(key, serialized);
    };
    const getJson = (key) => {
      const raw = get(key);
      if (raw === void 0) return void 0;
      try {
        return JSON.parse(raw);
      } catch {
        return void 0;
      }
    };
    const drainDelta = () => {
      const sets = /* @__PURE__ */ Object.create(null);
      const deletions = [];
      buffer.forEach((v, k) => {
        if (v === DELETED) {
          if (frozenSnapshot.has(k)) deletions.push(k);
        } else if (frozenSnapshot.get(k) !== v) {
          sets[k] = v;
        }
      });
      return { sets, deletions };
    };
    const toJSON = () => {
      const out = {};
      frozenSnapshot.forEach((v, k) => {
        out[k] = v;
      });
      buffer.forEach((v, k) => {
        if (v === DELETED) delete out[k];
        else out[k] = v;
      });
      return out;
    };
    const memory = {
      get,
      set,
      has,
      keys,
      delete: del,
      interpolate,
      setJson,
      getJson,
      [DRAIN_DELTA]: drainDelta,
      toJSON
    };
    return memory;
  }

  // ../../../../../../../../../sdks/typescript/src/tool-core.ts
  var TypedToolValidationError = class extends Error {
    constructor(ajvErrors) {
      super(`Invalid arguments: ${formatAjvErrors(ajvErrors)}`);
      this.ajvErrors = ajvErrors;
      this.name = "ValidationError";
    }
  };
  function formatAjvErrors(errors) {
    return errors.map((e) => {
      const path = e.instancePath.length > 0 ? e.instancePath : "(root)";
      return `${path}: ${e.message ?? "validation failed"}`;
    }).join("; ");
  }
  function defineTypedTool(handler, inputSchema, compileValidator) {
    if (typeof handler !== "function") {
      throw new TypeError(
        "trailblaze.tool<I, O>(handler): argument must be a function. Got: " + (handler == null ? String(handler) : typeof handler) + "."
      );
    }
    let validator = null;
    if (inputSchema != null && compileValidator != null) {
      try {
        validator = compileValidator(inputSchema);
      } catch (e) {
        const reason = e instanceof Error ? e.message : String(e);
        console.warn(
          `[trailblaze.tool] typed tool: ajv schema compile failed (${reason}). The tool will dispatch without input validation. Fix the inputSchema in the spec and re-run.`
        );
      }
    }
    return async (args, legacyCtx, client) => {
      const validatedArgs = args != null && typeof args === "object" && !Array.isArray(args) ? args : {};
      if (validator != null && !validator(validatedArgs)) {
        throw new TypedToolValidationError(validator.errors ?? []);
      }
      const memory = legacyCtx?.memory ?? createMemory(void 0);
      const toolContext = { tools: client.tools, memory, target: legacyCtx?.target };
      return handler(validatedArgs, toolContext);
    };
  }

  // ../../../../../../../../../sdks/typescript/src/in-process.ts
  function tool(arg0, arg1) {
    if (typeof arg0 === "function") {
      return defineTypedTool(
        arg0
      );
    }
    if (typeof arg0 === "object" && arg0 !== null && !Array.isArray(arg0) && typeof arg1 === "function") {
      const specObj = arg0;
      const inlineSchema = specObj.inputSchema && typeof specObj.inputSchema === "object" && !Array.isArray(specObj.inputSchema) ? specObj.inputSchema : void 0;
      return defineTypedTool(
        arg1,
        inlineSchema
      );
    }
    throw new Error(
      "imperative trailblaze.tool(name, spec, handler) is unavailable in the in-process runtime; author tools as `export const x = trailblaze.tool<I>(...)`, or declare `runtime: subprocess` for tools that need the full MCP runtime."
    );
  }
  async function run() {
    throw new Error(
      "trailblaze.run() is unavailable in the in-process runtime; in-process tools are registered by the synthesized bundle wrapper. Declare `runtime: subprocess` if you need the MCP server."
    );
  }
  var trailblaze = {
    tool,
    run
  };

  // openUrl.ts
  var openUrl = trailblaze.tool(
    { supportedPlatforms: ["android", "ios"] },
    async (input, ctx) => {
      const url = String(input.url ?? "").trim();
      if (!url) {
        throw new Error("openUrl requires a non-empty `url` argument.");
      }
      await ctx.tools.maestro({ commands: [{ openLink: url }] });
      return `Opened ${url}`;
    }
  );

  // .trailblaze-wrapper-openUrl.ts
  var __client = {
    callTool: async (name, args) => {
      const argsJson = JSON.stringify(args == null ? {} : args);
      const resultJson = await __trailblazeCall(name, argsJson);
      const result = JSON.parse(resultJson);
      if (result && result.isError === true) {
        throw new Error("client.callTool('" + name + "') failed: " + (result.error || result.errorMessage || "(no error message)"));
      }
      if (result && typeof result.type === "string" && result.type.indexOf("Error") >= 0) {
        throw new Error("client.callTool('" + name + "') failed: " + (result.errorMessage || result.message || result.type));
      }
      return result;
    }
  };
  __client.tools = new Proxy({}, {
    get: (_t, name) => {
      if (typeof name !== "string") return void 0;
      if (name === "then" || name === "catch" || name === "finally" || name === "constructor" || name === "prototype" || name === "__proto__" || name === "toString" || name === "valueOf" || name === "toJSON") {
        return void 0;
      }
      return async (args) => {
        const envelope = await __client.callTool(name, args);
        if (envelope == null) return envelope;
        if (envelope.structuredContent !== void 0 && envelope.structuredContent !== null) {
          return envelope.structuredContent;
        }
        if (envelope.textContent !== void 0 && envelope.textContent !== null) {
          return envelope.textContent;
        }
        return envelope.message;
      };
    }
  });
  function __normalizeResult(result) {
    if (result == null) return { content: [] };
    if (typeof result === "object" && Array.isArray(result.content)) return result;
    if (typeof result === "string") return { content: [{ type: "text", text: result }] };
    return { content: [{ type: "text", text: JSON.stringify(result) }] };
  }
  globalThis.__trailblazeTools = globalThis.__trailblazeTools || {};
  for (const __exportName of Object.keys(openUrl_exports)) {
    const __def = openUrl_exports[__exportName];
    if (typeof __def !== "function") continue;
    globalThis.__trailblazeTools[__exportName] = {
      handler: async (args, ctx) => {
        const result = await __def(args, ctx, __client);
        return __normalizeResult(result);
      }
    };
  }
})();
