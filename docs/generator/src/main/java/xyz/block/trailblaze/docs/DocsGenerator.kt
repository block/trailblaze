package xyz.block.trailblaze.docs

import com.aallam.openai.api.chat.chatCompletionRequest
import com.aallam.openai.api.model.ModelId
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.toolcalls.DataClassToToolUtils
import xyz.block.trailblaze.toolcalls.TrailblazeKoogTool.Companion.toKoogToolDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet
import java.io.File
import kotlin.reflect.KClass

/**
 * Generates Documentation for [xyz.block.trailblaze.toolcalls.TrailblazeTool]s
 */
class DocsGenerator(
  private val generatedDir: File,
  private val generatedFunctionsDocsDir: File,
) {

  fun createPageForCommand(
    toolKClass: KClass<out TrailblazeTool>,
  ) {
    val toolDescriptor = toolKClass.toKoogToolDescriptor()

    val pagePath = "custom/${toolDescriptor.name}.md"

    File(generatedFunctionsDocsDir, pagePath).also { file ->
      file.parentFile.mkdirs() // Ensure directory exists

      // Creating a fake chatCompletionRequest to register the tool and print out the result
      val registeredOpenAiToolCallFunction = chatCompletionRequest {
        model = ModelId("")
        messages = listOf()
        tools {
          DataClassToToolUtils.registerManualToolForDataClass(
            builder = this,
            clazz = toolKClass,
          )
        }
      }.tools?.first {
        it.function.name == toolDescriptor.name
      }!!.function

      val json = TrailblazeJsonInstance.encodeToString(registeredOpenAiToolCallFunction)
      file.writeText(
        """
# Function `${registeredOpenAiToolCallFunction.name}`

## Description
${toolDescriptor.description}

### Command Class
`${toolKClass.qualifiedName}`

## Registered Tool Call to Open AI
```json
$json
```

$THIS_DOC_IS_GENERATED_MESSAGE
          """.trimMargin()
      )
    }
  }

  fun generate() {
    TrailblazeToolSet.BuiltInTrailblazeTools
      .forEach { toolClass: KClass<out TrailblazeTool> ->
        createPageForCommand(toolClass)
      }

    val map = mutableMapOf<String, Set<String>>().apply {
      put(
        "DefaultUiToolSet",
        TrailblazeToolSet.DefaultUiToolSet.asTools()
          .map { it.toKoogToolDescriptor().name }
          .toSet()
      )
      put(
        "InteractWithElementsByPropertyToolSet",
        TrailblazeToolSet.InteractWithElementsByPropertyToolSet.asTools()
          .map { it.toKoogToolDescriptor().name }
          .toSet()
      )
      put(
        "InteractWithElementsByNodeIdToolSet",
        TrailblazeToolSet.InteractWithElementsByNodeIdToolSet.asTools()
          .map { it.toKoogToolDescriptor().name }
          .toSet()
      )
      put(
        "NonDefaultUiToolSet",
        TrailblazeToolSet.NonDefaultUiToolSet.asTools()
          .map { it.toKoogToolDescriptor().name }
          .toSet()
      )

    }
    createFunctionsIndexPage(map)
  }

  private fun createFunctionsIndexPage(map: Map<String, Set<String>>) {

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
