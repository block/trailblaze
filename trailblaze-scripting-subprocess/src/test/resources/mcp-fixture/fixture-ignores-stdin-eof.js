#!/usr/bin/env node
// MCP server that completes the handshake normally and then IGNORES stdin EOF, staying alive and
// silent forever. `fixture.js` exits on `stdin.on('end')`, which is what a well-behaved tool does —
// and why it can never reach the teardown ordering this fixture exists to pin.
//
// The hazard: `StdioClientTransport.close()` joins its reader coroutine, and that reader is parked
// in a blocking read on THIS process's stdout that returns only at EOF. Closing the client before
// killing the process therefore waits on a subprocess that is waiting for nothing — and teardown
// runs under `NonCancellable`, so a regression is an unbreakable hang, not a slow test.
//
// Deliberately: no `end` handler, no exit path, nothing written to stdout after the handshake.

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
        serverInfo: { name: 'trailblaze-fixture-ignores-eof', version: '0.0.1' },
      });
    } else if (method === 'tools/list') {
      respond(id, { tools: [] });
    }
    // Everything else, including the stdin EOF that follows, gets no answer at all.
  }
});

setInterval(() => {}, 1 << 30); // keep the event loop alive indefinitely
