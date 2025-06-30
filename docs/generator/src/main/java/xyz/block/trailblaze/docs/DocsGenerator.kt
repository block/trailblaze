package xyz.block.trailblaze.docs

import ai.koog.agents.core.tools.ToolParameterDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeKoogTool.Companion.toKoogToolDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet
import java.io.File
import kotlin.reflect.KClass

/**
 * Generates Documentation for [TrailblazeTool]s
 */
class DocsGenerator(
  private val generatedDir: File,
  private val generatedFunctionsDocsDir: File,
) {

  fun paramsString(params: List<ToolParameterDescriptor>): String = buildString {
    params.forEach { param ->
      appendLine("- `${param.name}`: `${param.type}`")
      if (param.description.isNotBlank()) {
        appendLine("  " + param.description)
      }
    }
  }

  fun createPageForCommand(
    toolKClass: KClass<out TrailblazeTool>,
  ) {
    val toolDescriptor = toolKClass.toKoogToolDescriptor()

    val pagePath = "custom/${toolDescriptor.name}.md"

    val propertiesMarkdown = buildString {
      if (toolDescriptor.requiredParameters.isNotEmpty()) {
        appendLine("### Required Parameters")
        appendLine(paramsString(toolDescriptor.requiredParameters))
      }
      if (toolDescriptor.optionalParameters.isNotEmpty()) {
        appendLine("### Optional Parameters")
        appendLine(paramsString(toolDescriptor.optionalParameters))
      }
    }

    File(generatedFunctionsDocsDir, pagePath).also { file ->
      file.parentFile.mkdirs() // Ensure directory exists

      file.writeText(
        """
## Tool `${toolDescriptor.name}`

## Description
${toolDescriptor.description}

### Command Class
`${toolKClass.qualifiedName}`

### Registered `${toolKClass.simpleName}` in `ToolRegistry`
$propertiesMarkdown

$THIS_DOC_IS_GENERATED_MESSAGE
          """.trimMargin()
      )
    }
  }

  fun generate() {
    TrailblazeToolSet.AllBuiltInTrailblazeTools
      .forEach { toolClass: KClass<out TrailblazeTool> ->
        createPageForCommand(toolClass)
      }
    createFunctionsIndexPage(TrailblazeToolSet.AllBuiltInTrailblazeToolSets)
  }

  private fun createFunctionsIndexPage(toolSets: Set<TrailblazeToolSet>) {
    val map = toolSets.map { it.name to it.tools.map { it.toKoogToolDescriptor().name }.toSet() }.toMap()

    File(generatedDir, "TOOLS.md").also { file ->
      val text = buildString {
        appendLine("# Trailblaze Tools")
        appendLine()
        map.forEach { groupName, trailblazeToolNames ->
          appendLine(
            """
## $groupName
${trailblazeToolNames.sorted().joinToString(separator = "\n") { "- [$it](functions/custom/$it.md)" }}
        """.trimIndent()
          )
          appendLine()
        }
        appendLine(THIS_DOC_IS_GENERATED_MESSAGE)
      }

      file.writeText(text)
    }
  }

  companion object {
    val THIS_DOC_IS_GENERATED_MESSAGE = """
<hr/>

**NOTE**: THIS IS GENERATED DOCUMENTATION
      """.trimIndent()
  }
}
