#!/usr/bin/env node
// Fixture for SubprocessCrashEnvelopeTest. Handshakes normally so the Kotlin client gets
// through `initialize`, then on the FIRST `tools/call` writes a recognizable diagnostic
// line to stderr and exits with a distinctive non-zero code. That trips the production
// crash-detection path in SubprocessTrailblazeTool.execute (the `mapDispatchFailure(...
// isAlive = false ...)` branch → FatalError).
//
// Intentionally separate from `fixture.js` (the well-behaved happy-path fixture used by
// `SubprocessRuntimeEndToEndTest`) so neither test's wire-protocol expectations leak into
// the other.

const tools = [
  {
    name: 'crash_on_call',
    description: 'Crashes the subprocess on first call. Test fixture only.',
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
        serverInfo: { name: 'trailblaze-crash-fixture', version: '0.0.1' },
      });
    } else if (method === 'tools/list') {
      respond(id, { tools });
    } else if (method === 'tools/call') {
      // Write two distinct stderr lines so the test can assert the tail capture grabbed
      // both — guards against an off-by-one in the StderrCapture ring buffer or the
      // ordering of "flush capture vs build error message" on the crash path.
      //
      // `process.exit` is NOT guaranteed to drain buffered stdio writes before terminating
      // — the Node docs explicitly call out this race. Use the write callback on the LAST
      // line so we only call `process.exit(42)` after the writes have hit the OS. Without
      // this, the parent's StderrCapture can race the exit and snapshot an empty tail in
      // busy CI environments. Distinctive exit code so the test's `exit code: 42`
      // assertion can't accidentally match a generic `exit code: 1` from an unrelated
      // runtime failure.
      process.stderr.write('intentional crash diagnostic line one\n');
      process.stderr.write('intentional crash diagnostic line two\n', () => process.exit(42));
    }
  }
});
process.stdin.on('end', () => { process.exit(0); });
