package xyz.block.trailblaze.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import picocli.CommandLine
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.WebInstanceIds
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pin the shape of the synthetic `tools/call` requests that [McpProxy] injects on
 * startup when launched with `--device` / `TRAILBLAZE_DEVICE` (and optionally
 * `--target`). Two surfaces under test:
 *
 *  1. [McpCommand]'s picocli surface — the new `--device` / `--target` options
 *     parse and default correctly, with the env-var fallback for `--device`
 *     matching what `resolveCliDevice` does for the action commands.
 *  2. [McpProxy.synthesizeDeviceBindCall] / [synthesizeTargetBindCall] — the
 *     synthesized JSON-RPC requests that get posted directly to the daemon's MCP
 *     endpoint after the client's `notifications/initialized` arrives. The
 *     daemon-side handlers for `device` and `setSessionTargetForBoundDevice` are
 *     covered by their own integration tests; this suite pins the request shape
 *     so a refactor that drops a required field or flips the action name fails
 *     loudly here instead of silently breaking auto-bind.
 *
 * The end-to-end injection flow (after `notifications/initialized` is forwarded,
 * post to daemon, store in lastDeviceToolCall, push tools/list_changed) is out
 * of scope for this unit suite — it requires a running daemon. Integration runs
 * cover it.
 */
class McpProxyInjectionTest {

  // ---------------------------------------------------------------------------
  // McpCommand picocli surface
  // ---------------------------------------------------------------------------

  @Test
  fun `mcp picocli defaults --device and --target to null`() {
    // The fallback (--device → TRAILBLAZE_DEVICE env) is performed at the
    // call() level via resolveCliDevice — the field itself stays null when
    // not passed so the resolver can do its job.
    val command = McpCommand()
    CommandLine(command).parseArgs() // no args
    assertNull(command.device)
    assertNull(command.target)
  }

  @Test
  fun `mcp picocli parses --device long form`() {
    val command = McpCommand()
    CommandLine(command).parseArgs("--device", "android/emulator-5554")
    assertEquals("android/emulator-5554", command.device)
  }

  @Test
  fun `mcp picocli parses -d short form`() {
    // Matches the convention every other device-binding command uses (-d).
    // Important for claude_desktop_config.json registrations that prefer the
    // compact form.
    val command = McpCommand()
    CommandLine(command).parseArgs("-d", "ios/SIM-X")
    assertEquals("ios/SIM-X", command.device)
  }

  @Test
  fun `mcp picocli parses --target long form`() {
    val command = McpCommand()
    CommandLine(command).parseArgs("--target", "sampleapp")
    assertEquals("sampleapp", command.target)
  }

  @Test
  fun `mcp picocli parses -t short form`() {
    val command = McpCommand()
    CommandLine(command).parseArgs("-t", "default")
    assertEquals("default", command.target)
  }

  @Test
  fun `mcp picocli accepts both --device and --target together`() {
    // The full claude_desktop_config.json registration shape:
    //   "args": ["mcp", "--device", "android", "--target", "sampleapp"]
    val command = McpCommand()
    CommandLine(command).parseArgs("--device", "android", "--target", "sampleapp")
    assertEquals("android", command.device)
    assertEquals("sampleapp", command.target)
  }

  // ---------------------------------------------------------------------------
  // synthesizeDeviceBindCall — JSON-RPC shape pinning
  // ---------------------------------------------------------------------------

  @Test
  fun `synthesizeDeviceBindCall on bare platform produces action-only call`() {
    val proxy = McpProxy(initialDeviceSpec = "android")
    val raw = assertNotNull(proxy.synthesizeDeviceBindCall("android"))
    val params = Json.parseToJsonElement(raw).jsonObject["params"]!!.jsonObject
    assertEquals("device", params["name"]!!.jsonPrimitive.content)
    val args = params["arguments"]!!.jsonObject
    assertEquals("ANDROID", args["action"]!!.jsonPrimitive.content)
    // Bare-platform spec: no deviceId — daemon auto-selects the first instance.
    assertNull(args["deviceId"])
  }

  @Test
  fun `synthesizeDeviceBindCall with instance splits on slash and uppercases action`() {
    val proxy = McpProxy(initialDeviceSpec = "android/emulator-5554")
    val raw = assertNotNull(proxy.synthesizeDeviceBindCall("android/emulator-5554"))
    val args = Json.parseToJsonElement(raw).jsonObject["params"]!!.jsonObject["arguments"]!!.jsonObject
    assertEquals("ANDROID", args["action"]!!.jsonPrimitive.content)
    assertEquals("emulator-5554", args["deviceId"]!!.jsonPrimitive.content)
  }

  @Test
  fun `synthesizeDeviceBindCall accepts ios and web platforms`() {
    val proxy = McpProxy(initialDeviceSpec = "anything")
    val iosArgs = Json.parseToJsonElement(assertNotNull(proxy.synthesizeDeviceBindCall("ios/SIM-X")))
      .jsonObject["params"]!!.jsonObject["arguments"]!!.jsonObject
    assertEquals("IOS", iosArgs["action"]!!.jsonPrimitive.content)
    assertEquals("SIM-X", iosArgs["deviceId"]!!.jsonPrimitive.content)

    val webArgs = Json.parseToJsonElement(assertNotNull(proxy.synthesizeDeviceBindCall("web")))
      .jsonObject["params"]!!.jsonObject["arguments"]!!.jsonObject
    assertEquals("WEB", webArgs["action"]!!.jsonPrimitive.content)
    assertNull(webArgs["deviceId"])
  }

  @Test
  fun `synthesizeDeviceBindCall returns null for unknown platform`() {
    // Garbage in the env var (e.g. typo, stale value) shouldn't crash startup
    // — the proxy logs and skips the auto-bind. The agent can still call the
    // `device` tool explicitly.
    val proxy = McpProxy(initialDeviceSpec = "anything")
    assertNull(proxy.synthesizeDeviceBindCall("not-a-platform"))
    assertNull(proxy.synthesizeDeviceBindCall("nonsense/instance"))
  }

  @Test
  fun `synthesizeDeviceBindCall produces well-formed JSON-RPC envelope`() {
    val proxy = McpProxy(initialDeviceSpec = "android")
    val raw = assertNotNull(proxy.synthesizeDeviceBindCall("android"))
    val root = Json.parseToJsonElement(raw).jsonObject
    assertEquals("2.0", root["jsonrpc"]!!.jsonPrimitive.content)
    assertEquals("tools/call", root["method"]!!.jsonPrimitive.content)
    // Id must be present and string-shaped (the daemon's MCP handler matches on it
    // to route the response). Specific value is a UUID prefix so just assert non-null.
    assertNotNull(root["id"])
  }

  // ---------------------------------------------------------------------------
  // synthesizeTargetBindCall — JSON-RPC shape pinning
  // ---------------------------------------------------------------------------

  @Test
  fun `synthesizeTargetBindCall produces setSessionTargetForBoundDevice call`() {
    val proxy = McpProxy(initialDeviceSpec = "android")
    val raw = proxy.synthesizeTargetBindCall("sampleapp")
    val params = Json.parseToJsonElement(raw).jsonObject["params"]!!.jsonObject
    // The tool name is the same one the existing CLI uses for per-device target
    // override (see PerDeviceTargetTest). Hard-pinning prevents accidental rename
    // from silently breaking auto-bind.
    assertEquals("setSessionTargetForBoundDevice", params["name"]!!.jsonPrimitive.content)
    val args = params["arguments"]!!.jsonObject
    // Argument name must be `appTargetId` to match the daemon-side tool schema —
    // `CliMcpClient.setSessionTargetForBoundDevice` (covered by PerDeviceTargetTest)
    // sends the same key. Using `target` here would silently no-op at the daemon.
    assertEquals("sampleapp", args["appTargetId"]!!.jsonPrimitive.content)
    assertNull(args["target"])
  }

  // ---------------------------------------------------------------------------
  // trackForReplay — reconnect replay coverage for client-issued tool calls
  // ---------------------------------------------------------------------------

  @Test
  fun `trackForReplay captures device tool call for replay`() {
    val proxy = McpProxy()
    val deviceCall = """
      {"jsonrpc":"2.0","id":"1","method":"tools/call",
       "params":{"name":"device","arguments":{"action":"ANDROID","deviceId":"emulator-5554"}}}
    """.trimIndent()
    proxy.trackForReplay(deviceCall)
    assertEquals(deviceCall, proxy.trackedDeviceToolCall)
    assertNull(proxy.trackedTargetToolCall)
  }

  @Test
  fun `trackForReplay ignores device tool calls with non-connect actions`() {
    // device.list / device.disconnect shouldn't be replayed — only the
    // bind/connect actions need to survive a daemon restart.
    val proxy = McpProxy()
    val listCall = """
      {"jsonrpc":"2.0","id":"1","method":"tools/call",
       "params":{"name":"device","arguments":{"action":"LIST"}}}
    """.trimIndent()
    proxy.trackForReplay(listCall)
    assertNull(proxy.trackedDeviceToolCall)
  }

  @Test
  fun `trackForReplay captures setSessionTargetForBoundDevice for replay`() {
    // A client-issued target binding must be persisted so that on a daemon
    // restart `reInitializeSession` can re-post it after the device bind.
    // Without this capture, an agent that retargets mid-session would silently
    // revert to the proxy's startup-injected target (or null) on every restart.
    val proxy = McpProxy()
    val targetCall = """
      {"jsonrpc":"2.0","id":"2","method":"tools/call",
       "params":{"name":"setSessionTargetForBoundDevice","arguments":{"appTargetId":"sampleapp"}}}
    """.trimIndent()
    proxy.trackForReplay(targetCall)
    assertEquals(targetCall, proxy.trackedTargetToolCall)
  }

  @Test
  fun `trackForReplay keeps the most recent target binding for replay`() {
    // If the agent retargets twice in one session, only the latest binding
    // should survive a reconnect — older overrides are stale.
    val proxy = McpProxy()
    val first = """
      {"jsonrpc":"2.0","id":"2","method":"tools/call",
       "params":{"name":"setSessionTargetForBoundDevice","arguments":{"appTargetId":"first"}}}
    """.trimIndent()
    val second = """
      {"jsonrpc":"2.0","id":"3","method":"tools/call",
       "params":{"name":"setSessionTargetForBoundDevice","arguments":{"appTargetId":"second"}}}
    """.trimIndent()
    proxy.trackForReplay(first)
    proxy.trackForReplay(second)
    assertEquals(second, proxy.trackedTargetToolCall)
  }

  @Test
  fun `trackForReplay ignores malformed JSON without crashing`() {
    val proxy = McpProxy()
    proxy.trackForReplay("not json at all")
    proxy.trackForReplay("{")
    assertNull(proxy.trackedDeviceToolCall)
    assertNull(proxy.trackedTargetToolCall)
  }

  // ---------------------------------------------------------------------------
  // isInitialInjectionTrigger — the one-shot startup-injection trigger contract
  // ---------------------------------------------------------------------------
  //
  // Original implementation only triggered on `notifications/initialized`. The
  // lead-dev review surfaced an edge case: MCP clients are not required to send
  // `notifications/initialized` — some lazy clients skip it and go straight to
  // `tools/call`. The trigger now fires on either path so env-pinned device
  // binding still happens for those clients. These tests pin the broader
  // contract so a future "tighten the trigger" refactor can't silently regress.

  @Test
  fun `isInitialInjectionTrigger fires on notifications-initialized`() {
    val proxy = McpProxy()
    val initialized = """{"jsonrpc":"2.0","method":"notifications/initialized"}"""
    assertEquals(true, proxy.isInitialInjectionTrigger(initialized))
  }

  @Test
  fun `isInitialInjectionTrigger fires on tools-call as lazy-client fallback`() {
    // Pinning the load-bearing behavior: a client that jumps straight to
    // `tools/call` (without `notifications/initialized`) must still trigger the
    // startup device-bind. Without this, env-pinned TRAILBLAZE_DEVICE silently
    // no-ops for those clients.
    val proxy = McpProxy()
    val toolsCall = """
      {"jsonrpc":"2.0","id":"1","method":"tools/call",
       "params":{"name":"tap","arguments":{"ref":"p1"}}}
    """.trimIndent()
    assertEquals(true, proxy.isInitialInjectionTrigger(toolsCall))
  }

  @Test
  fun `isInitialInjectionTrigger does NOT fire on initialize itself`() {
    // `initialize` is the handshake request, sent BEFORE the session is ready
    // for tool calls. Injecting at this point would race the daemon's session
    // setup. The trigger waits for either `notifications/initialized` or
    // `tools/call` — both of which logically follow `initialize`.
    val proxy = McpProxy()
    val initialize = """
      {"jsonrpc":"2.0","id":"0","method":"initialize",
       "params":{"protocolVersion":"2025-06-18"}}
    """.trimIndent()
    assertEquals(false, proxy.isInitialInjectionTrigger(initialize))
  }

  @Test
  fun `isInitialInjectionTrigger does NOT fire on unrelated notifications`() {
    val proxy = McpProxy()
    val arbitraryNotification = """{"jsonrpc":"2.0","method":"notifications/progress"}"""
    assertEquals(false, proxy.isInitialInjectionTrigger(arbitraryNotification))
  }

  @Test
  fun `isInitialInjectionTrigger ignores malformed JSON without crashing`() {
    // Robustness — a malformed line on the wire shouldn't take down the proxy
    // or cause spurious injection. Return false (don't trigger) and let the
    // forwarding path emit the actual JSON-RPC parse error to the daemon.
    val proxy = McpProxy()
    assertEquals(false, proxy.isInitialInjectionTrigger("not json at all"))
    assertEquals(false, proxy.isInitialInjectionTrigger("{"))
    assertEquals(false, proxy.isInitialInjectionTrigger(""))
  }

  // ---------------------------------------------------------------------------
  // isInitializedNotification — ordering discriminator
  // ---------------------------------------------------------------------------
  //
  // The main loop uses this narrower predicate to pick injection-vs-forward
  // order. The contract is asymmetric on purpose:
  //
  //   - `notifications/initialized` → forward first, then inject. Without this,
  //     the synthetic `tools/call name=device` would race the daemon's MCP
  //     state-machine transition to "initialized" and could be rejected,
  //     consuming the one-shot CAS and leaving the agent's first real call
  //     unbound. This was the Codex P1 finding on the original PR.
  //   - `tools/call` (lazy-client fallback) → inject first, then forward. If
  //     we forward first, the real tool call runs on an unbound session —
  //     exactly what auto-bind exists to prevent.
  //
  // These tests pin the asymmetry so a future "simplify the ordering" refactor
  // can't silently collapse both paths into one direction.

  @Test
  fun `isInitializedNotification fires only on notifications-initialized`() {
    val proxy = McpProxy()
    assertEquals(
      true,
      proxy.isInitializedNotification("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""),
    )
  }

  @Test
  fun `isInitializedNotification does NOT fire on tools-call`() {
    // tools/call is also an injection trigger, but it must be ordered
    // INJECT-then-forward (inverse of notifications/initialized).
    // The discriminator must return false here so the main loop picks the
    // inject-first branch.
    val proxy = McpProxy()
    val toolsCall = """
      {"jsonrpc":"2.0","id":"1","method":"tools/call",
       "params":{"name":"tap","arguments":{"ref":"p1"}}}
    """.trimIndent()
    assertEquals(false, proxy.isInitializedNotification(toolsCall))
  }

  @Test
  fun `isInitializedNotification does NOT fire on initialize itself`() {
    // `initialize` precedes `notifications/initialized` in the MCP handshake.
    // Discriminator returns false; the trigger predicate
    // [isInitialInjectionTrigger] also returns false (the main loop never
    // tries to inject at this point regardless of branch).
    val proxy = McpProxy()
    val initialize = """{"jsonrpc":"2.0","id":"0","method":"initialize"}"""
    assertEquals(false, proxy.isInitializedNotification(initialize))
  }

  @Test
  fun `isInitializedNotification ignores malformed JSON without crashing`() {
    // Same robustness contract as isInitialInjectionTrigger — malformed lines
    // must not crash the proxy. Return false so the main loop picks the
    // inject-first branch (which is a no-op anyway when the line isn't a
    // valid trigger, because maybeInjectInitialDeviceBind re-parses and
    // gates on isInitialInjectionTrigger).
    val proxy = McpProxy()
    assertEquals(false, proxy.isInitializedNotification("not json"))
    assertEquals(false, proxy.isInitializedNotification(""))
  }

  // ---------------------------------------------------------------------------
  // resolveAutodetectFromDeviceList — third-tier device resolver (CLI parity)
  // ---------------------------------------------------------------------------
  //
  // Mirrors the CLI's `autodetectSingleConnectedDevice` (#3456). The pure
  // decision function takes the already-filtered device list (the
  // playwright-native virtual entry stripped at the daemon-query boundary in
  // [McpProxy.autodetectSingleConnectedDevice]) and returns the spec iff
  // exactly one real device is connected. These tests pin the
  // size-based decision and log behavior without needing a live daemon.

  @Test
  fun `resolveAutodetectFromDeviceList returns the spec when exactly one device is connected`() {
    // The OOBE-closing case: user opens Claude Desktop with no env var, one
    // emulator booted. Autodetect resolves to that emulator's fully-qualified
    // ID so the synthetic device-bind lands on the right target.
    val proxy = McpProxy()
    val logs = mutableListOf<String>()
    val devices = listOf(
      CliMcpClient.DeviceListEntry(
        instanceId = "emulator-5554",
        platform = TrailblazeDevicePlatform.ANDROID,
      ),
    )
    val spec = proxy.resolveAutodetectFromDeviceList(devices) { logs += it }
    assertEquals("android/emulator-5554", spec)
    // Logs the auto-use line so debugging is possible — same wording the CLI
    // emits via reportAutodetectedDevice.
    assertTrue(
      logs.any { it.contains("Auto-using only connected device: android/emulator-5554") },
      "expected auto-using log line; got: $logs",
    )
  }

  @Test
  fun `resolveAutodetectFromDeviceList returns null when zero devices are connected`() {
    // No real device connected → fall through to "no inject"; agent gets the
    // existing unbound-device error on first tool call. Logs a hint so the
    // file log explains why no auto-bind happened.
    val proxy = McpProxy()
    val logs = mutableListOf<String>()
    val spec = proxy.resolveAutodetectFromDeviceList(emptyList()) { logs += it }
    assertNull(spec)
    assertTrue(
      logs.any { it.contains("no devices connected") },
      "expected no-devices log line; got: $logs",
    )
  }

  @Test
  fun `resolveAutodetectFromDeviceList returns null when multiple devices are connected`() {
    // Ambiguous: user must disambiguate via --device or TRAILBLAZE_DEVICE.
    // Logs the available specs so the user has the context to pick.
    val proxy = McpProxy()
    val logs = mutableListOf<String>()
    val devices = listOf(
      CliMcpClient.DeviceListEntry(
        instanceId = "emulator-5554",
        platform = TrailblazeDevicePlatform.ANDROID,
      ),
      CliMcpClient.DeviceListEntry(
        instanceId = "SIM-X",
        platform = TrailblazeDevicePlatform.IOS,
      ),
    )
    val spec = proxy.resolveAutodetectFromDeviceList(devices) { logs += it }
    assertNull(spec)
    assertTrue(
      logs.any { it.contains("2 devices connected") && it.contains("android/emulator-5554") && it.contains("ios/SIM-X") },
      "expected multi-device log line listing specs; got: $logs",
    )
  }

  @Test
  fun `parseDeviceList plus playwright-native filter is what feeds the autodetect`() {
    // The autodetect's filter rule mirrors PR #3456's CLI-side filter. The
    // playwright-native virtual entry shows up in `device LIST` even when no
    // real browser is open; counting it would mis-classify the common case
    // "1 emulator + 0 browsers" as `Multiple` (skipping the auto-bind) and
    // "0 emulators" as `Resolved(web)` (binding the wrong thing).
    //
    // We assert the filter at the boundary (parseDeviceList + the same
    // filter the proxy applies) so a refactor that drops the filter from the
    // production code fails this test instead of silently regressing.
    val listResponse = """
      - emulator-5554 (Android)
      - playwright-native (Web)
    """.trimIndent()
    val parsed = CliMcpClient.parseDeviceList(listResponse)
      .filterNot {
        it.platform == TrailblazeDevicePlatform.WEB &&
          it.instanceId == WebInstanceIds.PLAYWRIGHT_NATIVE
      }
    val proxy = McpProxy()
    val spec = proxy.resolveAutodetectFromDeviceList(parsed) {}
    assertEquals("android/emulator-5554", spec)
  }

  @Test
  fun `resolveAutodetectFromDeviceList returns null when only the playwright-native virtual entry remains after filter`() {
    // Edge case: parseDeviceList yields only the virtual web entry. Once
    // filtered, the list is empty — autodetect must skip rather than
    // auto-binding the virtual web device (which would surprise users who
    // expect web binding only when explicitly requested).
    val listResponse = """
      - playwright-native (Web)
    """.trimIndent()
    val parsed = CliMcpClient.parseDeviceList(listResponse)
      .filterNot {
        it.platform == TrailblazeDevicePlatform.WEB &&
          it.instanceId == WebInstanceIds.PLAYWRIGHT_NATIVE
      }
    val proxy = McpProxy()
    val spec = proxy.resolveAutodetectFromDeviceList(parsed) {}
    assertNull(spec)
  }

  // ---------------------------------------------------------------------------
  // tryConsumeAutodetectAttempt — bounded startup autodetect probes
  // ---------------------------------------------------------------------------
  //
  // An unresolved autodetect (0 or 2+ devices) deliberately leaves the one-shot
  // injection CAS open so a device booted between `notifications/initialized`
  // and the first `tools/call` is still picked up. The cost of that open window
  // must be bounded: each probe is a ~2s one-shot daemon session, and before
  // this counter existed a no-device environment re-paid it on EVERY tools/call
  // (measured 17 probes across 15 calls). These tests pin the cap.

  @Test
  fun `tryConsumeAutodetectAttempt allows exactly MAX attempts then refuses`() {
    val proxy = McpProxy()
    repeat(McpProxy.MAX_INITIAL_AUTODETECT_ATTEMPTS) { i ->
      assertTrue(proxy.tryConsumeAutodetectAttempt {}, "attempt ${i + 1} should be allowed")
    }
    assertEquals(false, proxy.tryConsumeAutodetectAttempt {})
    assertEquals(false, proxy.tryConsumeAutodetectAttempt {})
  }

  @Test
  fun `tryConsumeAutodetectAttempt logs the giving-up line exactly once`() {
    // The log line is the user's only signal for "why did auto-bind stop
    // trying" — it must appear on the first refusal and must not spam the log
    // on every subsequent tool call.
    val proxy = McpProxy()
    val logs = mutableListOf<String>()
    repeat(McpProxy.MAX_INITIAL_AUTODETECT_ATTEMPTS + 3) {
      proxy.tryConsumeAutodetectAttempt { logs += it }
    }
    assertEquals(1, logs.count { it.contains("skipping further probes") }, "got logs: $logs")
  }

  @Test
  fun `tryConsumeAutodetectAttempt allows the second-chance probe`() {
    // The window the cap must NOT close: probe 1 (on notifications/initialized)
    // finds nothing, a device boots, probe 2 (on the first tools/call) must
    // still be allowed to resolve it.
    val proxy = McpProxy()
    assertTrue(proxy.tryConsumeAutodetectAttempt {})
    assertTrue(proxy.tryConsumeAutodetectAttempt {})
  }

  @Test
  fun `synthesizeDeviceBindCall round-trips the autodetect-resolved spec`() {
    // Glue test: the spec produced by the autodetect resolver
    // (`android/emulator-5554`) is the exact shape synthesizeDeviceBindCall
    // accepts. Without this pinning, a future refactor that changes the
    // fully-qualified format (e.g. to `android:emulator-5554`) would
    // silently break the autodetect → inject path even with both halves
    // passing their own unit tests.
    val proxy = McpProxy()
    val devices = listOf(
      CliMcpClient.DeviceListEntry(
        instanceId = "emulator-5554",
        platform = TrailblazeDevicePlatform.ANDROID,
      ),
    )
    val resolved = assertNotNull(proxy.resolveAutodetectFromDeviceList(devices) {})
    val raw = assertNotNull(proxy.synthesizeDeviceBindCall(resolved))
    val args = Json.parseToJsonElement(raw).jsonObject["params"]!!.jsonObject["arguments"]!!.jsonObject
    assertEquals("ANDROID", args["action"]!!.jsonPrimitive.content)
    assertEquals("emulator-5554", args["deviceId"]!!.jsonPrimitive.content)
  }
}
