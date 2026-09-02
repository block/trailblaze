package xyz.block.trailblaze.android.test.tools

import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.fail
import xyz.block.trailblaze.config.ToolYamlLoader
import xyz.block.trailblaze.llm.config.ClasspathConfigResourceSource
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.trailblazeToolClassAnnotation

/**
 * Naming and surface-area contract for this module's tools.
 *
 * `:trailblaze-common`'s `ToolNamingConventionTest` only iterates the tools reachable from
 * `TrailblazeToolSet.NonLlmTrailblazeTools ∪ DefaultLlmTrailblazeTools`. Driver modules register
 * their tools in their own trailmap toolsets, so they are invisible to that catalog and were
 * unenforced — which is how this module's names originally shipped as `android_view_assert_visible`
 * (snake_case verbNoun) rather than the convention's `{prefix}_{verbNoun}`. Depending on
 * `:trailblaze-android-test` from `:trailblaze-common`'s tests would invert the module dependency,
 * so the driver module enforces its own names here instead.
 *
 * Every tool here is prefixed `androidTest_` because the driver, not the backend, is the real
 * constraint: `AndroidTestExecutableTool.execute()` hard-errors unless `AndroidTestTrailblazeAgent`
 * dispatches it, so a name like `androidView_click` would advertise portability that does not
 * exist.
 */
class AndroidTestToolNamingConventionTest {

  /**
   * Stricter than `:trailblaze-common`'s historical `^[a-z][a-zA-Z0-9]*(_[a-z][a-zA-Z0-9]*)*$`,
   * which accepted unlimited segments and therefore passed a fully snake_case name.
   *
   * The convention table tops out at three segments (`{app}_{platform}_{verbNoun}`, e.g.
   * `org_ios_configureTestUser`) with an optional `_v2`-style version suffix, so capping the
   * segment count forces the verbNoun to stay a single camelCase token. This driver's tools are
   * two segments: a prefix naming the driver, and the intent.
   */
  private val conventionRegex = Regex("^[a-z][a-zA-Z0-9]*(_[a-z][a-zA-Z0-9]*){0,2}(_v[0-9]+)?$")

  @Test
  fun `every tool name matches the naming convention`() {
    val violations =
      allTools().map { it.trailblazeToolClassAnnotation().name }
        .filterNot { conventionRegex.matches(it) }
        .sorted()

    if (violations.isNotEmpty()) {
      fail(
        buildString {
          appendLine("Tool name(s) violate docs/devlog/2026-01-14-tool-naming-convention.md:")
          appendLine()
          violations.forEach { appendLine("  - $it") }
          appendLine()
          appendLine("Use `{prefix}_{verbNoun}` with a camelCase verbNoun — `androidTest_assertVisible`,")
          appendLine("not `android_test_assert_visible`. At most three underscore-separated segments,")
          appendLine("plus an optional `_v2` version suffix.")
        },
      )
    }
  }

  @Test
  fun `every tool name is prefixed with the driver it is bound to`() {
    val unprefixed =
      allTools().map { it.trailblazeToolClassAnnotation().name }
        .filterNot { it.startsWith("androidTest_") }
        .sorted()

    if (unprefixed.isNotEmpty()) {
      fail(
        buildString {
          appendLine("Tool name(s) do not name the driver that must dispatch them: $unprefixed")
          appendLine()
          appendLine("These tools only run under AndroidTestTrailblazeAgent — execute() hard-errors")
          appendLine("otherwise. A backend-flavoured prefix like `androidView_` or `androidCompose_`")
          appendLine("advertises portability to other Android drivers that does not exist.")
        },
      )
    }
  }

  @Test
  fun `tool names are unique`() {
    val names = allTools().map { it.trailblazeToolClassAnnotation().name }
    val duplicates = names.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.sorted()
    if (duplicates.isNotEmpty()) {
      fail("Tool names must be globally unique; duplicated here: $duplicates")
    }
  }

  /**
   * The bundled toolsets are what a consumer's trailmap actually exposes, so they define the
   * driver's public surface. Cross-checking both directions catches a trail-facing tool that was
   * written but never exposed, and a toolset entry whose class was renamed or deleted (which would
   * otherwise fail at trailmap load time on device instead of here).
   */
  @Test
  fun `toolset yaml exposes exactly the trail-facing tools`() {
    val surfaced = TRAIL_FACING_TOOLS.map { it.trailblazeToolClassAnnotation().name }.toSet()
    val inToolsets = toolsetToolNames()

    val onlyInToolsets = (inToolsets - surfaced).sorted()
    val onlyInClasses = (surfaced - inToolsets).sorted()
    if (onlyInToolsets.isNotEmpty() || onlyInClasses.isNotEmpty()) {
      fail(
        buildString {
          appendLine("Toolset YAML and trail-facing tool classes disagree:")
          if (onlyInToolsets.isNotEmpty()) {
            appendLine("  in a toolset but not declared trail-facing: $onlyInToolsets")
          }
          if (onlyInClasses.isNotEmpty()) {
            appendLine("  declared trail-facing but in no toolset: $onlyInClasses")
          }
        },
      )
    }
  }

  /**
   * A tool name only resolves to a class if a `tools/<name>.tool.yaml` descriptor says so — there
   * is no annotation scanner. Without one, `ToolNameResolver` reports the name as unknown, the
   * toolset drops it with a warning, and a recorded `- androidTest_tap:` step fails to
   * deserialize at replay. Nothing else in this module catches that: the on-device tests
   * construct the tool classes directly, so they pass with no descriptor at all.
   *
   * Goes through the real discovery path (classpath scan + reflection) rather than reading the
   * YAML by hand, so a descriptor naming a class that was since renamed fails here too.
   */
  @Test
  fun `every tool has a descriptor that resolves its name back to its class`() {
    val discovered = ToolYamlLoader.discoverAndLoadAll(ClasspathConfigResourceSource)

    val broken =
      TRAIL_FACING_TOOLS.mapNotNull { toolClass ->
        val name = toolClass.trailblazeToolClassAnnotation().name
        when (val resolved = discovered[ToolName(name)]) {
          toolClass -> null
          null -> "  - $name has no tools/$name.tool.yaml descriptor"
          else -> "  - $name resolves to ${resolved.simpleName}, not ${toolClass.simpleName}"
        }
      }.sorted()

    if (broken.isNotEmpty()) {
      fail(
        buildString {
          appendLine("Tool name(s) do not resolve to their implementation:")
          appendLine()
          broken.forEach(::appendLine)
          appendLine()
          appendLine("Add trails/config/trailmaps/androidTest/tools/<name>.tool.yaml with `id:` and")
          appendLine("`class:`. The @TrailblazeToolClass annotation only supplies metadata once the")
          appendLine("class has been resolved from a descriptor; it does not register the tool.")
        },
      )
    }
  }

  /**
   * The point of this driver's surface is that a trail names an intent, not a backend. There is no
   * `androidTest_view_*` or `androidTest_compose_*` tool to hide any more — the backends are plain
   * internal functions — so what needs guarding is that none reappears: a tool named after Espresso
   * or Compose would let a trail hard-code which backend owns a node, freezing a decision the
   * hybrid hierarchy makes at run time.
   */
  @Test
  fun `no tool names a native backend`() {
    // Segment equality, not substring: the backend used to occupy the middle slot
    // (`androidTest_view_click`), while an intent can legitimately end in one of these words —
    // `androidTest_scrollIntoView` names what the trail wants, not who carries it out.
    val backendNamed =
      allTools().map { it.trailblazeToolClassAnnotation().name }
        .filter { name -> name.split("_").drop(1).any { it.lowercase() in BACKEND_WORDS } }
        .sorted()

    if (backendNamed.isNotEmpty()) {
      fail(
        buildString {
          appendLine("Tool name(s) name a native backend: $backendNamed")
          appendLine()
          appendLine("Act through androidTest_tap / androidTest_type / androidTest_assertVisible,")
          appendLine("which resolve a selector and dispatch on the matched node's provenance. A")
          appendLine("backend-named tool binds the trail to Views or to Compose, so the trail stops")
          appendLine("replaying when that screen is re-laid-out in the other toolkit.")
        },
      )
    }
  }

  /**
   * Every tool here is trail-facing, so every tool must be recordable and offered to the LLM. This
   * is the inverse of the check it replaces: while the backend primitives existed they had to be
   * suppressed, and a tool that lost `surfaceToLlm = false` would have leaked a backend. Now a
   * suppressed tool is the bug — it would be a surface a trail can name but nothing can produce.
   */
  @Test
  fun `every tool is recordable and offered to the llm`() {
    val suppressed =
      allTools().map { it.trailblazeToolClassAnnotation() }
        .filterNot { it.isRecordable && it.surfaceToLlm }
        .map { it.name }
        .sorted()

    if (suppressed.isNotEmpty()) {
      fail(
        "Tool(s) are hidden from recording or from the LLM: $suppressed. Every tool this driver " +
          "ships is trail-facing; a hidden one is a surface a trail can name but no recording " +
          "or agent run can ever produce.",
      )
    }
  }

  private fun toolsetToolNames(): Set<String> =
    TOOLSET_RESOURCES.flatMap { resource ->
      val yaml =
        checkNotNull(javaClass.getResourceAsStream(resource)) { "Missing toolset resource $resource" }
          .bufferedReader()
          .readText()
      Regex("""^\s*-\s+([A-Za-z][A-Za-z0-9_]*)\s*$""", RegexOption.MULTILINE)
        .findAll(yaml.substringAfter("tools:"))
        .map { it.groupValues[1] }
    }.toSet()

  private fun allTools() = TRAIL_FACING_TOOLS

  companion object {
    private val TOOLSET_RESOURCES =
      listOf("/trails/config/trailmaps/androidTest/toolsets/android_test.yaml")

    /** The driver's entire tool surface. Must equal the union of the bundled toolsets. */
    private val TRAIL_FACING_TOOLS: List<KClass<out TrailblazeTool>> =
      listOf(
        AndroidTestTapTool::class,
        AndroidTestTypeTool::class,
        AndroidTestAssertVisibleTool::class,
        AndroidTestScrollUntilVisibleTool::class,
      )

    /** Words that would name a native backend rather than the intent a trail expresses. */
    private val BACKEND_WORDS = setOf("view", "compose", "espresso", "semantics")
  }
}
