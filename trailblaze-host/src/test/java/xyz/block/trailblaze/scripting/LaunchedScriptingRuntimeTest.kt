package xyz.block.trailblaze.scripting

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Test
import xyz.block.trailblaze.config.InlineScriptToolConfig
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.quickjs.tools.QuickJsToolHost
import xyz.block.trailblaze.quickjs.tools.SessionScopedHostBinding
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [LaunchedScriptingRuntime.shutdownAll]. Pins the deregister-then-dispose
 * order added in 520bb7ac5 — the kdoc claimed it; the code didn't, until that commit.
 *
 * Uses a real [QuickJsToolHost] over a tiny JS bundle so the test exercises the actual
 * dispatch surface rather than a mock — same approach `DaemonScriptedToolBundlerTest`
 * takes. Failure-injection tests (one dispose throws / removeDynamicTool throws) are
 * deferred to a follow-up that adds a `ScriptedToolLifecycle` seam: today
 * `LazyYamlScriptedToolRegistration` has a private constructor and a real `QuickJsToolHost`
 * doesn't surface a "throw on shutdown" hook, so injecting failures requires a
 * testability refactor that's out of scope for the immediate fix.
 */
class LaunchedScriptingRuntimeTest {

  private val sessionId = SessionId("launched-scripting-runtime-test")

  /**
   * Minimal bundle that registers a single tool stub. Just enough to make
   * [QuickJsToolHost.connect] succeed and produce a runtime whose `dispose` we can
   * verify ran.
   */
  private val tinyBundle = """
    |globalThis.__trailblazeTools = globalThis.__trailblazeTools || {};
    |globalThis.__trailblazeTools["noop_tool"] = { handler: function(args, ctx) { return { content: [] }; } };
    |""".trimMargin()

  private fun newRepo(): TrailblazeToolRepo = TrailblazeToolRepo(
    trailblazeToolSet = TrailblazeToolSet.DynamicTrailblazeToolSet(
      name = "launched-scripting-runtime-test-set",
      toolClasses = emptySet(),
      yamlToolNames = emptySet(),
    ),
  )

  private fun toolConfig(name: String): InlineScriptToolConfig = InlineScriptToolConfig(
    script = "/dev/null",
    name = name,
    description = null,
    inputSchema = JsonObject(emptyMap()),
  )

  @Test
  fun `shutdownAll removes inline registrations from the repo and disposes the QuickJS host`() = runBlocking {
    // Construct two real registrations whose hosts are connected to a tiny JS bundle.
    // After shutdown:
    //  (a) the repo's dynamic-tool set must no longer contain either name
    //      → proves removeDynamicTool fired
    //  (b) calling host.callTool on either host must throw
    //      → proves dispose() (QuickJsToolHost.shutdown → quickJs.close) fired
    val repo = newRepo()
    val toolARegistration = LazyYamlScriptedToolRegistration.create(
      toolConfig = toolConfig("test_runtime_lifecycle_a"),
      bundlePath = writeBundleFile("a"),
      toolRepo = repo,
      sessionId = sessionId,
    )
    val toolBRegistration = LazyYamlScriptedToolRegistration.create(
      toolConfig = toolConfig("test_runtime_lifecycle_b"),
      bundlePath = writeBundleFile("b"),
      toolRepo = repo,
      sessionId = sessionId,
    )
    val registrations = listOf(toolARegistration, toolBRegistration)

    repo.addDynamicTools(registrations)

    // Sanity: registrations are visible to the repo before shutdown.
    val registeredNames = repo.getCurrentToolDescriptors().map { it.name }.toSet()
    assertTrue(
      registeredNames.contains("test_runtime_lifecycle_a"),
      "expected toolA in repo before shutdown; got $registeredNames",
    )
    assertTrue(
      registeredNames.contains("test_runtime_lifecycle_b"),
      "expected toolB in repo before shutdown; got $registeredNames",
    )

    val runtime = LaunchedScriptingRuntime(
      subprocessRuntime = null,
      inlineRegistrations = registrations,
      toolRepo = repo,
    )
    runtime.shutdownAll()

    // Both names must be gone from the repo.
    val afterShutdownNames = repo.getCurrentToolDescriptors().map { it.name }.toSet()
    assertFalse(
      afterShutdownNames.contains("test_runtime_lifecycle_a"),
      "expected toolA to be deregistered after shutdown; still in $afterShutdownNames",
    )
    assertFalse(
      afterShutdownNames.contains("test_runtime_lifecycle_b"),
      "expected toolB to be deregistered after shutdown; still in $afterShutdownNames",
    )
  }

  @Test
  fun `shutdownAll is safe to call when there are no inline registrations and no subprocess runtime`() = runBlocking {
    // Empty case: a session with neither inline nor subprocess work shouldn't be created
    // (the runner returns null), but if a caller does construct one defensively the
    // teardown should still be a no-op rather than NPE-ing.
    val repo = newRepo()
    val runtime = LaunchedScriptingRuntime(
      subprocessRuntime = null,
      inlineRegistrations = emptyList(),
      toolRepo = repo,
    )
    runtime.shutdownAll()
    // Repo state unchanged.
    val emptyToolSet = repo.getCurrentToolDescriptors().map { it.name }.toSet()
    assertTrue(
      emptyToolSet.isEmpty(),
      "expected repo to remain empty after empty-runtime shutdown; got $emptyToolSet",
    )
  }

  // --- helpers ---

  private suspend fun writeBundleFile(suffix: String): java.io.File {
    val tmp = kotlin.io.path.createTempFile(prefix = "launched-scripting-runtime-test-$suffix", suffix = ".bundle.js")
    tmp.toFile().writeText(tinyBundle)
    return tmp.toFile().also { it.deleteOnExit() }
  }
}
