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
  if (typeof result === 'object' && Array.isArray(result.content)) return result;
  if (typeof result === 'string') return { content: [{ type: 'text', text: result }] };
  return { content: [{ type: 'text', text: JSON.stringify(result) }] };
}

globalThis.__trailblazeTools = globalThis.__trailblazeTools || {};
// __TRAILBLAZE_REGISTRATION__
