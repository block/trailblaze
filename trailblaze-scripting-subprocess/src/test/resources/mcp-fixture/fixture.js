#!/usr/bin/env node
// Hand-rolled MCP server used by SubprocessRuntimeEndToEndTest. Speaks line-delimited
// JSON-RPC over stdio so the test doesn't depend on `@modelcontextprotocol/sdk` or an
// npm install step — keeps CI free of `node_modules` bootstrap.
//
// Tools advertised:
//   echo        — tier 1, no Trailblaze metadata. Returns whatever `message` it was given.
//   hostOnly    — tier 2, _meta["trailblaze/requiresHost"]: true. Filtered out for
//                 preferHostAgent=false sessions.
//   memoryTap   — tier 2. Reads `_trailblazeContext` from arguments and returns the
//                 session's device.platform + memory['probe'] so the test can verify
//                 envelope plumbing end-to-end.
//
// Intentional: no stateful initialization beyond the stdio pump. No external deps.

const tools = [
  {
    name: 'echo',
    description: 'Echo back the message argument',
    inputSchema: {
      type: 'object',
      properties: { message: { type: 'string', description: 'text to echo' } },
      required: ['message'],
    },
  },
  {
    name: 'hostOnly',
    description: 'Host-agent-only demo tool',
    inputSchema: { type: 'object', properties: {}, required: [] },
    _meta: { 'trailblaze/requiresHost': true },
  },
  {
    name: 'memoryTap',
    description: 'Reads _trailblazeContext and returns a summary',
    inputSchema: { type: 'object', properties: {}, required: [] },
  },
];

function respond(id, result) {
  process.stdout.write(JSON.stringify({ jsonrpc: '2.0', id, result }) + '\n');
}

function errorResponse(id, code, message) {
  process.stdout.write(JSON.stringify({ jsonrpc: '2.0', id, error: { code, message } }) + '\n');
}

function callTool(name, args) {
  if (name === 'echo') {
    return { content: [{ type: 'text', text: String(args.message ?? '') }], isError: false };
  }
  if (name === 'hostOnly') {
    return { content: [{ type: 'text', text: 'host tool ran' }], isError: false };
  }
  if (name === 'memoryTap') {
    const ctx = args._trailblazeContext;
    if (!ctx) {
      return { content: [{ type: 'text', text: 'no _trailblazeContext' }], isError: true };
    }
    const text = `platform=${ctx.device.platform} probe=${ctx.memory?.probe ?? '<missing>'}`;
    return { content: [{ type: 'text', text }], isError: false };
  }
  return { content: [{ type: 'text', text: `unknown tool: ${name}` }], isError: true };
}

let buffer = '';
process.stdin.setEncoding('utf8');
process.stdin.on('data', (chunk) => {
  buffer += chunk;
  let idx;
  while ((idx = buffer.indexOf('\n')) !== -1) {
    const line = buffer.slice(0, idx).trim();
    buffer = buffer.slice(idx + 1);
    if (!line) continue;
    let msg;
    try { msg = JSON.parse(line); } catch (_) { continue; }
    const { id, method, params } = msg;
    try {
      if (method === 'initialize') {
        respond(id, {
          protocolVersion: params?.protocolVersion ?? '2025-06-18',
          capabilities: { tools: {} },
          serverInfo: { name: 'trailblaze-fixture', version: '0.0.1' },
        });
      } else if (method === 'tools/list') {
        respond(id, { tools });
      } else if (method === 'tools/call') {
        const { name, arguments: args } = params ?? {};
        respond(id, callTool(name, args ?? {}));
      } else if (id !== undefined) {
        errorResponse(id, -32601, `method not found: ${method}`);
      }
      // notifications (no id) are ignored silently
    } catch (e) {
      if (id !== undefined) errorResponse(id, -32603, String(e?.message ?? e));
    }
  }
});
process.stdin.on('end', () => { process.exit(0); });
