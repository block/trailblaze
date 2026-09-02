// __TRAILBLAZE_HEADER__
import * as __userModule from "__TRAILBLAZE_IMPORT_SOURCE__";

// __TRAILBLAZE_PRELUDE__
const __client = {
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
  },
};
__client.tools = new Proxy({}, {
  get: (_t, name) => {
    if (typeof name !== 'string') return undefined;
    if (name === 'then' || name === 'catch' || name === 'finally' ||
        name === 'constructor' || name === 'prototype' || name === '__proto__' ||
        name === 'toString' || name === 'valueOf' || name === 'toJSON') {
      return undefined;
    }
    return async (args) => {
      const envelope = await __client.callTool(name, args);
      if (envelope == null) return envelope;
      if (envelope.structuredContent !== undefined && envelope.structuredContent !== null) {
        return envelope.structuredContent;
      }
      if (envelope.textContent !== undefined && envelope.textContent !== null) {
        return envelope.textContent;
      }
      return envelope.message;
    };
  },
});

function __normalizeResult(result) {
  if (result == null) return { content: [] };
  // Author hand-rolled an MCP envelope — pass through, structuredContent included or not.
  if (typeof result === 'object' && Array.isArray(result.content)) return result;
  if (typeof result === 'string') return { content: [{ type: 'text', text: result }] };
  if (typeof result !== 'object') return { content: [{ type: 'text', text: JSON.stringify(result) }] };
  // Typed-overload return: send it as text AND as structuredContent, matching
  // `normalizeInlineToolResult` in InlineScriptToolServerSynthesizer. Text alone is what the
  // caller's proxy unwraps when structuredContent is absent, so a composing tool would receive
  // the JSON *string* cast as the declared type and read `undefined` off every field — and only
  // when running in-process, which is the on-device runtime.
  return { content: [{ type: 'text', text: JSON.stringify(result) }], structuredContent: result };
}

globalThis.__trailblazeTools = globalThis.__trailblazeTools || {};
// __TRAILBLAZE_REGISTRATION__
// __TRAILBLAZE_MULTI_EXPORT_REGISTRATION_BEGIN__
// Every function-valued export becomes a tool under its own export name. Type-only exports erase
// at bundle time; the typeof filter skips any non-tool export. `const` inside the loop gives each
// iteration its own binding, so the handler closure captures the right definition.
for (const __exportName of Object.keys(__userModule)) {
  const __def = __userModule[__exportName];
  if (typeof __def !== 'function') continue;
  globalThis.__trailblazeTools[__exportName] = {
    handler: async (args, ctx) => {
      const result = await __def(args, ctx, __client);
      return __normalizeResult(result);
    },
  };
}
// __TRAILBLAZE_MULTI_EXPORT_REGISTRATION_END__
