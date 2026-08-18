package xyz.block.trailblaze.scripting

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.scripting.callback.JsScriptingCallbackArgumentValidator
import xyz.block.trailblaze.toolcalls.REDACTED_TOOL_ARG_PLACEHOLDER
import xyz.block.trailblaze.toolcalls.RawArgumentTrailblazeTool
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.getIsRecordableFromAnnotation
import xyz.block.trailblaze.toolcalls.toLogPayload
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import xyz.block.trailblaze.yaml.fromTrailblazeTool
import java.io.File
import java.nio.file.Files

/**
 * Unit tests for [InProcessScriptedToolLauncher], the one in-process QuickJS launch path shared by
 * the host runner and the MCP daemon. Exercises the no-engine [InProcessScriptedToolLauncher.describe]
 * path and the full launch → resolve → YAML-round-trip path against the framework `openUrl` scripted
 * tool, whose descriptor YAML + pre-compiled `.bundle.js` ship on the classpath via :trailblaze-common.
 */
class InProcessScriptedToolLauncherTest {

  private val openUrl = ToolName("openUrl")

  @Test
  fun `describe builds a descriptor for a framework scripted tool without launching an engine`() {
    val descriptors = InProcessScriptedToolLauncher.describe(setOf(openUrl))
    val descriptor = descriptors.firstOrNull { it.name == "openUrl" }
    assertNotNull(descriptor, "openUrl descriptor should be built from the catalog YAML")
    val params = descriptor.requiredParameters + descriptor.optionalParameters
    assertTrue(params.any { it.name == "url" }, "openUrl descriptor should expose its `url` parameter")
  }

  @Test
  fun `describe skips names with no catalog descriptor`() {
    val descriptors = InProcessScriptedToolLauncher.describe(setOf(ToolName("definitelyNotARealScriptedTool")))
    assertTrue(descriptors.isEmpty(), "unknown scripted tool names produce no descriptors")
  }

  @Test
  fun `describe returns nothing for an empty name set`() {
    assertTrue(InProcessScriptedToolLauncher.describe(emptySet()).isEmpty())
  }

  @Test
  fun `resolveInProcessScriptedTools resolves a framework tool to its config and bundle path`() {
    val resolved = InProcessScriptedToolLauncher.resolveInProcessScriptedTools(setOf(openUrl))
    val one = resolved.firstOrNull { it.name == openUrl }
    assertNotNull(one, "openUrl should resolve from the catalog as an in-process tool")
    assertEquals("openUrl", one.config.name)
    assertTrue(one.bundleResourcePath.isNotBlank(), "resolved tool must carry its bundle resource path")
  }

  @Test
  fun `resolveInProcessScriptedTools skips unknown names and honors skipNames`() {
    assertTrue(
      InProcessScriptedToolLauncher.resolveInProcessScriptedTools(setOf(ToolName("definitelyNotARealScriptedTool"))).isEmpty(),
      "unknown names resolve to nothing",
    )
    assertTrue(
      InProcessScriptedToolLauncher.resolveInProcessScriptedTools(setOf(openUrl), skipNames = setOf(openUrl)).isEmpty(),
      "skipNames drops the tool before resolution",
    )
  }

  /**
   * Regression test for the host-driver YAML round-trip arg drop: the resolved scripted tool is a
   * `ContextSettingScriptedTool`, and the host-driver dispatch (e.g. iOS-host) YAML-encodes it and
   * re-runs it via `runYaml`. Before the wrapper implemented `RawArgumentTrailblazeTool`, the YAML
   * serializer hit its generic `::class.serializer()` fallback (the wrapper isn't `@Serializable`)
   * and dropped every argument — so `openUrl` re-decoded + executed with an empty `url`. This pins
   * that the round-trip now preserves the arguments.
   */
  @Test
  fun `a launched scripted tool survives a YAML round-trip with its args`() = runBlocking {
    val toolRepo = TrailblazeToolRepo.withDynamicToolSets()
    val sessionDir = Files.createTempDirectory("inproc-launcher-test").toFile()
    var registrations: List<LazyYamlScriptedToolRegistration> = emptyList()
    try {
      registrations = InProcessScriptedToolLauncher.launch(
        toolRepo = toolRepo,
        sessionId = SessionId.sanitized("inproc-launcher-test"),
        sessionDir = sessionDir,
        toolNames = setOf(openUrl),
      )
      assertTrue(
        registrations.any { it.name == openUrl },
        "openUrl should launch in-process from its classpath bundle",
      )

      val resolved = toolRepo.toolCallToTrailblazeTool("openUrl", """{"url":"https://example.com"}""")

      // Encode exactly like the host-driver dispatch (TrailblazeMcpBridgeImpl) does before re-running.
      val yaml = createTrailblazeYaml().encodeTools(listOf(fromTrailblazeTool(resolved)))
      assertTrue(
        yaml.contains("https://example.com"),
        "YAML round-trip must preserve the scripted tool's `url` arg; got:\n$yaml",
      )
    } finally {
      registrations.forEach { runCatching { it.dispose() } }
      sessionDir.deleteRecursively()
    }
  }

  /**
   * Idempotent launch: a second `launch` against a repo that already has the tool registered must
   * skip it rather than crash. Pre-fix, the second pass hit `addDynamicTools`'s duplicate-name check
   * and threw "Dynamic tool 'openUrl' is already registered by another dynamic source" — which
   * crashed any iOS-host daemon session that ensures the session's scripted tools (registering
   * catalog tools) and then re-runs the resolved tool via `runYaml`, reaching this launcher again on
   * the same repo. The first launch must still register; the second must register nothing new.
   */
  @Test
  fun `launch is idempotent — a tool already on the repo is skipped, not re-registered`() = runBlocking {
    val toolRepo = TrailblazeToolRepo.withDynamicToolSets()
    val sessionDir = Files.createTempDirectory("inproc-idempotent-test").toFile()
    var first: List<LazyYamlScriptedToolRegistration> = emptyList()
    var second: List<LazyYamlScriptedToolRegistration> = emptyList()
    try {
      first = InProcessScriptedToolLauncher.launch(
        toolRepo = toolRepo,
        sessionId = SessionId.sanitized("inproc-idempotent-first"),
        sessionDir = sessionDir,
        toolNames = setOf(openUrl),
      )
      assertTrue(first.any { it.name == openUrl }, "first launch registers openUrl")

      // Second launch on the SAME repo must not throw and must register nothing new.
      second = InProcessScriptedToolLauncher.launch(
        toolRepo = toolRepo,
        sessionId = SessionId.sanitized("inproc-idempotent-second"),
        sessionDir = sessionDir,
        toolNames = setOf(openUrl),
      )
      assertTrue(second.isEmpty(), "second launch skips the already-registered openUrl")
    } finally {
      (first + second).forEach { runCatching { it.dispose() } }
      sessionDir.deleteRecursively()
    }
  }

  /**
   * The host in-process recording gate: a registration whose config carries `isRecordable = false`
   * must produce a decoded tool that reports `getIsRecordableFromAnnotation() == false`, so
   * `logToolExecution` keeps the invocation out of the replayable `.trail.yaml`. The default
   * (`isRecordable = true`) must stay recordable — the QuickJS tool has no class annotation, so the
   * gate falls through to the `true` default. Also pins the `surfaceToLlm` registration override.
   *
   * Reuses the framework `openUrl` bundle (ships on the classpath) but rebuilds the config with the
   * flag flipped, since `openUrl` itself is a normal recordable + LLM-visible tool.
   */
  @Test
  fun `isRecordable=false config threads onto the decoded tool's recordable bit`() = runBlocking {
    val resolved = InProcessScriptedToolLauncher.resolveInProcessScriptedTools(setOf(openUrl)).single()
    val bundleJs = assertNotNull(
      this@InProcessScriptedToolLauncherTest::class.java.classLoader
        .getResourceAsStream(resolved.bundleResourcePath),
      "openUrl bundle should be on the test classpath at ${resolved.bundleResourcePath}",
    ).use { it.readBytes().decodeToString() }

    val sessionDir = Files.createTempDirectory("inproc-recordable-test").toFile()
    val bundleFile = File(sessionDir, "openUrl.bundle.js").apply { writeText(bundleJs) }
    val args = """{"url":"https://example.com"}"""

    // Default config: recordable + surfaced (no-regression).
    val recordableRepo = TrailblazeToolRepo.withDynamicToolSets()
    val recordableReg = LazyYamlScriptedToolRegistration.create(
      toolConfig = resolved.config,
      bundlePath = bundleFile,
      toolRepo = recordableRepo,
      sessionId = SessionId.sanitized("inproc-recordable-default"),
    )

    // Opt-out config: same bundle, flags flipped to false.
    val hiddenRepo = TrailblazeToolRepo.withDynamicToolSets()
    val hiddenReg = LazyYamlScriptedToolRegistration.create(
      toolConfig = resolved.config.copy(surfaceToLlm = false, isRecordable = false),
      bundlePath = bundleFile,
      toolRepo = hiddenRepo,
      sessionId = SessionId.sanitized("inproc-recordable-optout"),
    )

    try {
      assertTrue(recordableReg.surfaceToLlm, "default config must keep the registration surfaced")
      assertTrue(
        recordableReg.decodeToolCall(args).getIsRecordableFromAnnotation(),
        "a recordable (default) scripted tool must stay recordable",
      )

      assertFalse(hiddenReg.surfaceToLlm, "surfaceToLlm=false must drop the registration from advertisement")
      assertFalse(
        hiddenReg.decodeToolCall(args).getIsRecordableFromAnnotation(),
        "isRecordable=false must thread onto the decoded tool so the recording gate skips it",
      )
    } finally {
      runCatching { recordableReg.dispose() }
      runCatching { hiddenReg.dispose() }
      sessionDir.deleteRecursively()
    }
  }

  /**
   * Regression pin for a device-farm launch break: a scripted tool resolved through the catalog
   * path — no analyzer, so its config comes from
   * `TrailmapScriptedToolFile.toInlineScriptToolConfigs()` — must keep accepting the arguments its
   * caller passes when the descriptor deliberately omits `inputSchema:` (the real schema lives in
   * the `.ts` `<I>` generic, a common shape for app launch/sign-in steps).
   *
   * That YAML translation still synthesizes `{type: object, properties: {}}`, byte-identical to a
   * genuine analyzer no-arg schema — so the argument-shape gate must decide on the config's
   * `inputSchemaExhaustive` provenance flag, NOT the schema's shape. Reading the shape rejected
   * every argument (`"was called with unknown argument keys: [...]. This tool accepts no
   * arguments."`) and broke the nested launch compose.
   *
   * Drives the real registration + repo + validator rather than asserting the flag directly, so the
   * pin survives a refactor that moves where the decision is made.
   */
  @Test
  fun `catalog-resolved tool without a declared inputSchema still accepts its arguments`(): Unit = runBlocking {
    val resolved = InProcessScriptedToolLauncher.resolveInProcessScriptedTools(setOf(openUrl)).single()
    val bundleJs = assertNotNull(
      this@InProcessScriptedToolLauncherTest::class.java.classLoader
        .getResourceAsStream(resolved.bundleResourcePath),
    ).use { it.readBytes().decodeToString() }
    val sessionDir = Files.createTempDirectory("inproc-arg-gate-test").toFile()
    val bundleFile = File(sessionDir, "openUrl.bundle.js").apply { writeText(bundleJs) }

    // Rebuild the config the way a descriptor with NO `inputSchema:` block resolves on the catalog
    // path: empty properties, provenance flag false. (openUrl's own descriptor declares `url`, so
    // it can't reproduce the shape as-shipped — hence both fields set explicitly. `copy` carrying
    // the flag is itself load-bearing: it's why the flag lives in the primary constructor.)
    val emptySchema = buildJsonObject {
      put("type", JsonPrimitive("object"))
      put("properties", buildJsonObject { })
    }
    val derivedSchemaRepo = TrailblazeToolRepo.withDynamicToolSets()
    val derivedSchemaReg = LazyYamlScriptedToolRegistration.create(
      toolConfig = resolved.config.copy(inputSchema = emptySchema, inputSchemaExhaustive = false),
      bundlePath = bundleFile,
      toolRepo = derivedSchemaRepo,
      sessionId = SessionId.sanitized("inproc-arg-gate-derived"),
    )
    // The mirror: same empty schema, but analyzer-sourced (exhaustive) — a stray key IS a typo there.
    val exhaustiveRepo = TrailblazeToolRepo.withDynamicToolSets()
    val exhaustiveReg = LazyYamlScriptedToolRegistration.create(
      toolConfig = resolved.config.copy(inputSchema = emptySchema, inputSchemaExhaustive = true),
      bundlePath = bundleFile,
      toolRepo = exhaustiveRepo,
      sessionId = SessionId.sanitized("inproc-arg-gate-exhaustive"),
    )

    try {
      derivedSchemaRepo.addDynamicTools(listOf(derivedSchemaReg))
      exhaustiveRepo.addDynamicTools(listOf(exhaustiveReg))
      val args = """{"url":"https://example.com"}"""

      assertNull(
        JsScriptingCallbackArgumentValidator.validate(derivedSchemaRepo, openUrl.toolName, args),
        "a catalog-resolved tool whose descriptor omits inputSchema must not have its arguments rejected",
      )
      assertNotNull(
        JsScriptingCallbackArgumentValidator.validate(exhaustiveRepo, openUrl.toolName, args),
        "an analyzer-sourced no-arg schema IS exhaustive, so a stray key must still be rejected",
      )
    } finally {
      runCatching { derivedSchemaReg.dispose() }
      runCatching { exhaustiveReg.dispose() }
      sessionDir.deleteRecursively()
    }
  }

  /**
   * Host-path `_meta` consistency: a descriptor can opt out via the raw namespaced `_meta` key
   * (`trailblaze/surfaceToLlm` / `trailblaze/isRecordable`) WITHOUT the typed shortcut field. The
   * on-device QuickJS launcher reads `_meta`, so the host in-process registration must honor it too
   * (opt-out AND with the typed field) — otherwise the host would advertise + record a tool the
   * on-device path hides. Pins that the host path consults `_meta`, not just the typed slot.
   */
  @Test
  fun `host registration honors raw _meta opt-out without the typed shortcut field`() = runBlocking {
    val resolved = InProcessScriptedToolLauncher.resolveInProcessScriptedTools(setOf(openUrl)).single()
    val bundleJs = assertNotNull(
      this@InProcessScriptedToolLauncherTest::class.java.classLoader
        .getResourceAsStream(resolved.bundleResourcePath),
    ).use { it.readBytes().decodeToString() }
    val sessionDir = Files.createTempDirectory("inproc-meta-optout-test").toFile()
    val bundleFile = File(sessionDir, "openUrl.bundle.js").apply { writeText(bundleJs) }

    // Typed fields left at their defaults (true); opt-out expressed ONLY via the namespaced `_meta`.
    val metaOnlyOptOut = resolved.config.copy(
      meta = buildJsonObject {
        put("trailblaze/surfaceToLlm", JsonPrimitive(false))
        put("trailblaze/isRecordable", JsonPrimitive(false))
      },
    )
    val repo = TrailblazeToolRepo.withDynamicToolSets()
    val reg = LazyYamlScriptedToolRegistration.create(
      toolConfig = metaOnlyOptOut,
      bundlePath = bundleFile,
      toolRepo = repo,
      sessionId = SessionId.sanitized("inproc-meta-optout"),
    )
    try {
      assertFalse(reg.surfaceToLlm, "host registration must honor `_meta.trailblaze/surfaceToLlm: false`")
      assertFalse(
        reg.decodeToolCall("""{"url":"https://example.com"}""").getIsRecordableFromAnnotation(),
        "host registration must honor `_meta.trailblaze/isRecordable: false`",
      )
    } finally {
      runCatching { reg.dispose() }
      sessionDir.deleteRecursively()
    }
  }

  @Test
  fun `host registration masks declared sensitive args in the log payload`() = runBlocking {
    // `decodeToolCall` returns a WRAPPER (ContextSettingScriptedTool), and the log-encode boundary
    // resolves the sensitive-args marker off the object it is handed. A wrapper that dropped the
    // marker would mask on-device and leak the credential on the host path, where the wrapper
    // surfaces `rawToolArguments` verbatim.
    val resolved = InProcessScriptedToolLauncher.resolveInProcessScriptedTools(setOf(openUrl)).single()
    val bundleJs = assertNotNull(
      this@InProcessScriptedToolLauncherTest::class.java.classLoader
        .getResourceAsStream(resolved.bundleResourcePath),
    ).use { it.readBytes().decodeToString() }
    val sessionDir = Files.createTempDirectory("inproc-sensitive-args-test").toFile()
    val bundleFile = File(sessionDir, "openUrl.bundle.js").apply { writeText(bundleJs) }

    val withSensitiveArg = resolved.config.copy(
      meta = buildJsonObject {
        put(
          "trailblaze/sensitiveArgNames",
          buildJsonArray { add(JsonPrimitive("url")) },
        )
      },
    )
    val reg = LazyYamlScriptedToolRegistration.create(
      toolConfig = withSensitiveArg,
      bundlePath = bundleFile,
      toolRepo = TrailblazeToolRepo.withDynamicToolSets(),
      sessionId = SessionId.sanitized("inproc-sensitive-args"),
    )
    try {
      val decoded = reg.decodeToolCall("""{"url":"https://example.com/secret-token"}""")
      assertEquals(
        JsonPrimitive(REDACTED_TOOL_ARG_PLACEHOLDER),
        decoded.toLogPayload().raw["url"],
        "host registration must honor `_meta.trailblaze/sensitiveArgNames`",
      )
      assertEquals(
        JsonPrimitive("https://example.com/secret-token"),
        (decoded as RawArgumentTrailblazeTool).rawToolArguments["url"],
        "the wrapper must still dispatch the real value to the bundle",
      )
    } finally {
      runCatching { reg.dispose() }
      sessionDir.deleteRecursively()
    }
  }
}
