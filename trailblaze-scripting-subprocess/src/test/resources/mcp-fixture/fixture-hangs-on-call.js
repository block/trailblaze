#!/usr/bin/env node
// Fixture for SubprocessCallToolTimeoutTest. Handshakes and advertises normally so the Kotlin
// client gets all the way to dispatch, then ACCEPTS a `tools/call` and never answers it — the
// shape a scripted tool takes when it wedges (awaiting something that never settles) rather than
// crashing. It keeps draining stdin, so the client's request write succeeds and no transport
// error fires: nothing but the host's own dispatch deadline can end this call.
//
// Deliberately separate from `fixture-hangs.js`, which parks the `initialize` handshake instead
// (that wedge is the session watchdog's job, not the dispatch budget's).

const tools = [
  {
    name: 'hang_on_call',
    description: 'Accepts a call and never answers. Test fixture only.',
    inputSchema: { type: 'object', properties: {}, required: [] },
  },
];

function respond(id, result) {
  process.stdout.write(JSON.stringify({ jsonrpc: '2.0', id, result }) + '\n');
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
    if (method === 'initialize') {
      respond(id, {
        protocolVersion: params?.protocolVersion ?? '2025-06-18',
        capabilities: { tools: {} },
        serverInfo: { name: 'trailblaze-hang-on-call-fixture', version: '0.0.1' },
      });
    } else if (method === 'tools/list') {
      respond(id, { tools });
    }
    // `tools/call` falls through with no response, on purpose.
  }
});
// Keep the event loop alive so the process stays healthy while it withholds the answer — a
// process that exited would surface as a crash envelope instead of a timeout.
setInterval(() => {}, 1 << 30);
process.stdin.on('end', () => { process.exit(0); });
