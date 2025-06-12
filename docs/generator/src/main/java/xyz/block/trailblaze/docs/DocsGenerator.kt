package xyz.block.trailblaze.docs

import com.aallam.openai.api.chat.chatCompletionRequest
import com.aallam.openai.api.model.ModelId
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.toolcalls.DataClassToToolUtils
import xyz.block.trailblaze.toolcalls.TrailblazeToolAsLlmTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import java.io.File

/**
 * Generates Documentation for [xyz.block.trailblaze.toolcalls.TrailblazeTool]s
 */
class DocsGenerator(
  private val generatedDir: File,
  private val generatedFunctionsDocsDir: File,
) {

  fun createPageForCommand(
    trailblazeToolAsLlmTool: TrailblazeToolAsLlmTool,
  ) {

    val pagePath = "custom/${trailblazeToolAsLlmTool.name}.md"

    File(generatedFunctionsDocsDir, pagePath).also { file ->
      file.parentFile.mkdirs() // Ensure directory exists


      // Creating a fake chatCompletionRequest to register the tool and print out the result
      val registeredOpenAiToolCallFunction = chatCompletionRequest {
        model = ModelId("")
        messages = listOf()
        tools {
          DataClassToToolUtils.registerManualToolForDataClass(
            builder = this,
            clazz = trailblazeToolAsLlmTool.trailblazeToolClass,
            propertyFilter = { propertyName: String ->
              !trailblazeToolAsLlmTool.excludedProperties.contains(propertyName)
            }
          )
        }
      }.tools?.first {
        it.function.name == trailblazeToolAsLlmTool.name
      }!!.function

      val json = TrailblazeJsonInstance.encodeToString(registeredOpenAiToolCallFunction)
      file.writeText(
        """
# Function `${registeredOpenAiToolCallFunction.name}`

## Description
${trailblazeToolAsLlmTool.description}

### Command Class
`${trailblazeToolAsLlmTool.trailblazeToolClass.qualifiedName}`

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
    TrailblazeToolRepo.ALL
      .map { TrailblazeToolAsLlmTool(it) }
      .forEach { trailblazeToolAsLlmTool: TrailblazeToolAsLlmTool ->
        createPageForCommand(
          trailblazeToolAsLlmTool,
        )
      }

    val map = mutableMapOf<String, Set<String>>().apply {
      put(
        "Common",
        TrailblazeToolRepo.DEFAULT_COMMON_COMMAND_CLASSES.map { TrailblazeToolAsLlmTool(it).name }.toSet()
      )
      put(
        "Additional for Recording Enabled",
        TrailblazeToolRepo.RECORDING_ENABLED_COMMAND_CLASSES.map { TrailblazeToolAsLlmTool(it).name }.toSet()
      )
      put(
        "Additional for Recording Disabled",
        TrailblazeToolRepo.RECORDING_DISABLED_COMMAND_CLASSES.map { TrailblazeToolAsLlmTool(it).name }.toSet()
      )

    }
    createFunctionsIndexPage(map)
  }

  private fun createFunctionsIndexPage(map: Map<String, Set<String>>) {

    File(generatedDir, "FUNCTIONS.md").also { file ->
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
