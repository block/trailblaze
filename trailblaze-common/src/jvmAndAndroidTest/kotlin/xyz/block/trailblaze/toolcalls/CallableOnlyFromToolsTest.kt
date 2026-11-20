package xyz.block.trailblaze.toolcalls

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.toolcalls.commands.ExecTrailblazeTool
import xyz.block.trailblaze.yaml.createTrailblazeYaml

/**
 * `@TrailblazeToolClass(callableOnlyFromTools = true)`: a tool that runs a caller-chosen process on
 * the host is reachable only from another tool (a scripted tool's `ctx.tools.<name>(...)`, Kotlin
 * composition). A trail step, `trailblaze tool`, MCP `step`, and the agent all refuse it.
 */
class CallableOnlyFromToolsTest {

  @Test
  fun `a trail step naming a tool-only tool is refused, saying only another tool may call it`() {
    // The target-only fixture is passed explicitly because only a target lists it; exec is bundled.
    val yaml = createTrailblazeYaml(setOf(TargetOnlyToolOnlyTool::class))
    for ((name, args) in TOOL_ONLY_CALLS) {
      val trail = "config: {}\ntrail:\n  - step: run it\n    recording:\n      android:\n" +
        "        - $name:\n            $args\n"
      val error = assertFailsWith<Exception> {
        yaml.decodeTrail(trail, deviceClassifiers = listOf(TrailblazeDeviceClassifier("android")))
      }
      val refusal = generateSequence<Throwable>(error) { it.cause }
        .filterIsInstance<CallableOnlyFromToolsException>()
        .firstOrNull() ?: fail("Expected $name to be refused at decode, got: $error")
      assertEquals(name, refusal.toolName)
      assertTrue("`$name` can only be called from another tool" in refusal.message.orEmpty(), refusal.message)
    }
  }

  @Test
  fun `a tool can still call a tool-only class that only the target supplies`() {
    // The fixture is not in the global registry, so a target that lists it is the only source a
    // composition lookup has. The session's own lookup still refuses it.
    val repo = TrailblazeToolRepo(
      TrailblazeToolSet.DynamicTrailblazeToolSet(name = "lists-it", toolClasses = setOf(TargetOnlyToolOnlyTool::class)),
    )

    assertEquals(
      TargetOnlyToolOnlyTool(command = "hi"),
      repo.toolCallToTrailblazeToolUnfiltered(TARGET_ONLY_TOOL, """{"command":"hi"}"""),
    )
    assertTrue("command" in repo.expectedArgumentKeysFor(TARGET_ONLY_TOOL).orEmpty())
    assertFailsWith<IllegalStateException> { repo.toolCallToTrailblazeTool(TARGET_ONLY_TOOL, """{"command":"hi"}""") }
  }

  @Test
  fun `a session never registers exec or offers it to the LLM, even when a target lists it`() {
    val repo = TrailblazeToolRepo(
      TrailblazeToolSet.DynamicTrailblazeToolSet(
        name = "lists-exec",
        toolClasses = setOf(ExecTrailblazeTool::class, TargetOnlyToolOnlyTool::class),
      ),
    )

    assertFalse(ExecTrailblazeTool::class in repo.getRegisteredTrailblazeTools())
    assertFalse(TargetOnlyToolOnlyTool::class in repo.getRegisteredTrailblazeTools())
    assertNull(ExecTrailblazeTool::class.toKoogToolDescriptor())
    assertNull(TargetOnlyToolOnlyTool::class.toKoogToolDescriptor())
  }

  @Test
  fun `every framework tool that starts a host process is callable only from tools`() {
    val spawning = frameworkToolSources().filter { (_, code) -> PROCESS_SPAWN.containsMatchIn(code) }

    // Floor: the known spawner must be found, or the scan stopped reaching source.
    val names = spawning.map { it.first.name }.toSet()
    assertTrue(
      "ExecTrailblazeTool.kt" in names,
      "Scan found $names. Did the walk stop reaching source?",
    )
    val unflagged = spawning.filterNot { (_, code) -> CALLABLE_ONLY_FROM_TOOLS.containsMatchIn(code) }
    assertTrue(
      unflagged.isEmpty(),
      "These tools start a host process but lack `callableOnlyFromTools = true` on their " +
        "@TrailblazeToolClass, so a trail, `trailblaze tool`, or the agent can run them: " +
        unflagged.joinToString { it.first.path },
    )
  }

  /** Non-test Kotlin sources under the framework root that declare a tool, comments stripped. */
  private fun frameworkToolSources(): List<Pair<File, String>> {
    val frameworkRoot = findFrameworkRoot()
    return frameworkRoot
      .walkTopDown()
      .onEnter { it == frameworkRoot || it.name !in PRUNED_DIRECTORY_NAMES && !it.name.startsWith(".") }
      .filter { it.isFile && it.extension == "kt" }
      .filterNot { TEST_SOURCE_SET.containsMatchIn(it.invariantSeparatorsPath) }
      .map { it to it.readText().replace(BLOCK_COMMENT, "").replace(LINE_COMMENT, "") }
      .filter { (_, code) -> "@TrailblazeToolClass(" in code }
      .toList()
  }

  private fun findFrameworkRoot(): File {
    var dir: File? = File(System.getProperty("user.dir")).absoluteFile
    while (dir != null) {
      if (File(dir, FRAMEWORK_SOURCE_ANCHOR).isDirectory) return dir
      dir = dir.parentFile
    }
    fail("Could not locate $FRAMEWORK_SOURCE_ANCHOR above ${System.getProperty("user.dir")}.")
  }

  /** A tool-only class no global registry holds: only a target that lists it supplies it. */
  @Serializable
  @TrailblazeToolClass(name = TARGET_ONLY_TOOL, surfaceToLlm = false, callableOnlyFromTools = true)
  private data class TargetOnlyToolOnlyTool(val command: String) : ExecutableTrailblazeTool {
    override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext): TrailblazeToolResult =
      TrailblazeToolResult.Success(message = command)
  }

  private companion object {
    const val TARGET_ONLY_TOOL = "test_targetOnlyToolOnly"

    /** Every tool-only tool, with arguments a trail step would give it. */
    val TOOL_ONLY_CALLS = listOf(
      "exec" to "argv: [printf, hi]",
      TARGET_ONLY_TOOL to "command: hi",
      RegisterCleanupTrailblazeTool.TOOL_NAME to "tool: exec",
    )
    const val FRAMEWORK_SOURCE_ANCHOR = "trailblaze-common/src/jvmAndAndroid/kotlin/xyz/block/trailblaze"
    val PRUNED_DIRECTORY_NAMES = setOf("build", "node_modules", "out", "vendor")
    val TEST_SOURCE_SET = Regex("/src/[^/]*[Tt]est[^/]*/")
    val PROCESS_SPAWN = Regex("""\bProcessBuilder\s*\(|\bcreateProcessBuilder\s*\(|Runtime\.getRuntime\(\)\s*\.exec""")
    val CALLABLE_ONLY_FROM_TOOLS = Regex("""\bcallableOnlyFromTools\s*=\s*true\b""")
    val LINE_COMMENT = Regex("""(?m)^\s*//.*$|\s//[^\n"]*$""")
    val BLOCK_COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
  }
}
