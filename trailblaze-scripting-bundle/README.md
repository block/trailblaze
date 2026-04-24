# trailblaze-scripting-bundle

On-device runtime for TypeScript-authored MCP tools. Loads a pre-bundled `.js` inside
QuickJS and exposes its tools to Trailblaze's tool registry.

## Using it

```kotlin
val rule = AndroidTrailblazeRule(
  mcpServers = listOf(
    McpServerConfig(script = "trailblaze-config/my-tools.bundle.js"),
  ),
)
```

The rule starts the bundle at session start, registers its tools, tears down at end.
`script:` resolves to an Android asset on-device.

Need to use the runtime without the rule (e.g. a framework test)?

```kotlin
val session = McpBundleSession.connect(BundleJsSource.FromFile("tools.bundle.js"))
try {
  session.client.listTools(ListToolsRequest())
  session.client.callTool(CallToolRequest(...))
} finally {
  session.shutdown()
}
```

## File map

**Start here:** [`BundleJsSource`](src/jvmAndAndroid/kotlin/xyz/block/trailblaze/scripting/bundle/BundleJsSource.kt),
[`McpBundleSession`](src/jvmAndAndroid/kotlin/xyz/block/trailblaze/scripting/bundle/McpBundleSession.kt).

That's the whole user surface. Everything else is internal plumbing — the launcher
(called by `AndroidTrailblazeRule` for you), the tool-registration glue, the
transport + JS bridge. You shouldn't need to touch them unless you're extending the
runtime itself.

## How it fits together

```
 AndroidTrailblazeRule  ─▶  McpBundleRuntimeLauncher  ─▶  McpBundleSession ─┐
                                                                            │
                                                               QuickJS + JS │
                                                               bundle ──────┘
```

## Testing

- **`:trailblaze-scripting-bundle:check`** runs the JVM round-trip test
  (`InProcessBundleRoundTripTest`) — transport + handshake + filter + `tools/call`
  against a hand-crafted MCP fixture.
- **`OnDeviceBundleRoundTripTest`** ships in the sample-app androidTest APK. CI
  runs it on a cloud device farm on every PR; a regression here blocks merge.

## Not in scope

- **Author writes `.ts` → we compile to `.js`.** This module only loads `.js`; QuickJS
  can't parse TypeScript. Today authors run `esbuild` themselves and drop the bundled
  `.js` where `script:` points. Automation is a follow-up.
- **On-device daemon callback channel** (tools that compose `client.callTool(…)` need
  this). Tracked as a follow-up.

See the design doc for the full scope boundaries:
`docs/devlog/2026-04-20-scripted-tools-on-device-bundle.md`.
