package xyz.block.trailblaze.toolcalls

import org.junit.Test
import xyz.block.trailblaze.api.TrailblazeElementSelector
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.config.ToolYamlLoader
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.primaryConstructor
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The LLM picks elements by snapshot ref and never writes a selector: it cannot compute one
 * reliably. Selectors belong to recorded and hand-written trails, which an LLM-facing tool reaches
 * by resolving a ref (`tap` → `tapOnElementBySelector`, `type` → `inputText`).
 *
 * A tool the LLM sees may still carry a selector param, as long as the LLM's schema of it leaves
 * that param out. This test holds that for the class-backed tools on
 * this classpath, by param type ([TrailblazeNodeSelector], [TrailblazeElementSelector]). It does not
 * see YAML-composed, scripted, or MCP tools, or a selector passed as some other type.
 */
class LlmToolsTakeNoSelectorsTest {

  @Test
  fun `no tool the LLM sees offers it a selector`() {
    val llmTools = ToolYamlLoader.discoverAndLoadAll().values.toSet()
      .mapNotNull { kClass -> kClass.toKoogToolDescriptor()?.let { kClass to it } }
    assertTrue(llmTools.size >= 20, "expected the built-in LLM tools on this classpath, found ${llmTools.size}")

    val leaks = llmTools.flatMap { (kClass, descriptor) ->
      val offered = (descriptor.requiredParameters + descriptor.optionalParameters).map { it.name }.toSet()
      kClass.selectorParamNames().filter { it in offered }.map { "${descriptor.name}.$it" }
    }
    if (leaks.isNotEmpty()) {
      fail(
        "These LLM tool params are selectors: ${leaks.sorted()}. Take a snapshot `ref` instead and " +
          "resolve it to a selector for the recording, as `tap` and `type` do.",
      )
    }
  }

  @Test
  fun `the check recognizes a selector param, so it cannot pass vacuously`() {
    val inputText = ToolYamlLoader.discoverAndLoadAll().values.single { it.toolName().toolName == "inputText" }
    assertTrue("selector" in inputText.selectorParamNames())
  }

  private fun KClass<out TrailblazeTool>.selectorParamNames(): List<String> =
    primaryConstructor?.parameters.orEmpty()
      .filter { it.type.isSelectorType() || it.type.arguments.any { arg -> arg.type?.isSelectorType() == true } }
      .mapNotNull { it.name }

  private fun KType.isSelectorType(): Boolean =
    classifier == TrailblazeNodeSelector::class || classifier == TrailblazeElementSelector::class
}
