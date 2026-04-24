package xyz.block.trailblaze.docs

import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import com.google.gson.GsonBuilder
import xyz.block.trailblaze.config.ToolYamlConfig
import xyz.block.trailblaze.config.ToolYamlLoader
import xyz.block.trailblaze.config.toTrailblazeToolDescriptor
import xyz.block.trailblaze.toolcalls.ToolSetCatalogEntry
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolParameterDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeToolSetCatalog
import xyz.block.trailblaze.toolcalls.toKoogToolDescriptor
import xyz.block.trailblaze.toolcalls.toolName
import java.io.File
import kotlin.reflect.KClass

/**
 * Generates Documentation for [TrailblazeTool]s
 */
class DocsGenerator(
    private val generatedDir: File,
    private val generatedFunctionsDocsDir: File,
) {

    fun paramsString(params: List<ToolParameterDescriptor>, indent: Int = 0): String = buildString {
        val indentChars = "  ".repeat(indent)
        params.forEach { param ->
            val paramType = param.type
            when (paramType) {
                is ToolParameterType.Enum -> {
                    appendLine("$indentChars- `${param.name}`: `${gson.toJson(paramType.entries)}`")
                }

                is ToolParameterType.List -> {
                    appendLine("$indentChars- `${param.name}`: `${gson.toJson(paramType)}`")
                }

                is ToolParameterType.Object -> {
                    appendLine("$indentChars- `${param.name}`: ")
                    appendLine(
                        paramsString(paramType.properties, indent + 2).lines().joinToString("\n") {
                            indentChars + indentChars + it
                        },
                    )
                }

                is ToolParameterType.AnyOf,
                ToolParameterType.Boolean,
                ToolParameterType.Float,
                ToolParameterType.Integer,
                ToolParameterType.Null,
                ToolParameterType.String -> {
                    appendLine("- `${param.name}`: `${paramType}`")
                    if (param.description.isNotBlank() && (param.description != param.name)) {
                        appendLine("  " + param.description)
                    }
                }
            }
        }
    }

    fun createPageForCommand(
        toolKClass: KClass<out TrailblazeTool>,
    ) {
        val toolDescriptor = toolKClass.toKoogToolDescriptor() ?: return // Skip any null descriptors

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
        // Wipe custom tool pages so docs for removed/renamed tools don't linger. CI wipes the
        // whole `docs/generated/` tree before running the generator; doing the equivalent here
        // keeps local `./gradlew :docs:generator:run` in lockstep with CI.
        File(generatedFunctionsDocsDir, "custom").deleteRecursively()

        ToolYamlLoader.discoverAndLoadAll().values
            .forEach { toolClass: KClass<out TrailblazeTool> ->
                createPageForCommand(toolClass)
            }
        ToolYamlLoader.discoverYamlDefinedTools().values
            .forEach { config: ToolYamlConfig ->
                createPageForYamlDefinedTool(config)
            }
        createFunctionsIndexPage(TrailblazeToolSetCatalog.defaultEntries())
    }

    fun createPageForYamlDefinedTool(config: ToolYamlConfig) {
        val descriptor = config.toTrailblazeToolDescriptor()
        val pagePath = "custom/${descriptor.name}.md"

        val propertiesMarkdown = buildString {
            if (descriptor.requiredParameters.isNotEmpty()) {
                appendLine("### Required Parameters")
                appendLine(yamlParamsString(descriptor.requiredParameters))
            }
            if (descriptor.optionalParameters.isNotEmpty()) {
                appendLine("### Optional Parameters")
                appendLine(yamlParamsString(descriptor.optionalParameters))
            }
        }

        File(generatedFunctionsDocsDir, pagePath).also { file ->
            file.parentFile.mkdirs()
            file.writeText(
                """
## Tool `${descriptor.name}`

## Description
${descriptor.description}

### Source
YAML-defined tool (no Kotlin class). Expanded from `trailblaze-config/tools/${config.id}.yaml`.
$propertiesMarkdown

$THIS_DOC_IS_GENERATED_MESSAGE
          """.trimMargin()
            )
        }
    }

    private fun yamlParamsString(params: List<TrailblazeToolParameterDescriptor>): String = buildString {
        params.forEach { param ->
            appendLine("- `${param.name}`: `${yamlTypeDisplay(param.type)}`")
            if (!param.description.isNullOrBlank() && param.description != param.name) {
                appendLine("  ${param.description}")
            }
        }
    }

    private fun yamlTypeDisplay(type: String): String = when (type.trim().lowercase()) {
        "string" -> "String"
        "integer", "int", "long" -> "Integer"
        "number", "float", "double" -> "Float"
        "boolean", "bool" -> "Boolean"
        else -> type
    }

    private fun createFunctionsIndexPage(catalog: List<ToolSetCatalogEntry>) {
        val map = catalog.associate { entry ->
            val classToolNames = entry.toolClasses
                .filter { it.toKoogToolDescriptor() != null }
                .map { it.toolName().toolName }
            entry.id to (classToolNames + entry.yamlToolNames.map { it.toolName }).toSet()
        }

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
        private val gson by lazy {
            GsonBuilder().setPrettyPrinting().create()
        }
        val THIS_DOC_IS_GENERATED_MESSAGE = """
<hr/>

**NOTE**: THIS IS GENERATED DOCUMENTATION
      """.trimIndent()
    }
}
