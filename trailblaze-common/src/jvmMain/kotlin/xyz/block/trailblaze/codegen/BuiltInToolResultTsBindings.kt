package xyz.block.trailblaze.codegen

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.serializer
import xyz.block.trailblaze.config.ToolYamlLoader
import xyz.block.trailblaze.toolcalls.trailblazeToolClassAnnotation
import java.io.File
import kotlin.reflect.full.starProjectedType

/**
 * Generates TypeScript result types for every Kotlin tool that declares
 * `@TrailblazeToolClass(resultType = ...)`. Discovery is dynamic via [ToolYamlLoader]
 * (classpath scan for `.tool.yaml`), so adding `resultType` to a new tool is enough —
 * nothing to register here.
 *
 * Run via `./gradlew :trailblaze-common:generateDtoTs`; `verifyDtoTs` fails CI on drift.
 */
internal object BuiltInToolResultTsBindings {

  @OptIn(ExperimentalSerializationApi::class)
  fun generate(): String {
    val tools = ToolYamlLoader.discoverAndLoadAll()
      .toList()
      .filter { (_, kClass) -> kClass.trailblazeToolClassAnnotation().resultType != Unit::class }
      .sortedBy { (name, _) -> name.toolName }

    require(tools.isNotEmpty()) {
      "No @TrailblazeToolClass declares a resultType — nothing to generate."
    }

    val roots = tools.map { (_, kClass) ->
      serializer(kClass.trailblazeToolClassAnnotation().resultType.starProjectedType).descriptor
    }
    var generated = SerialDescriptorTsCodegen.generate(roots, header = HEADER)

    // JSDoc naming the source tool(s) + the Kotlin resultType class above each `export
    // interface`, so hover shows where it came from — SerialDescriptorTsCodegen can't do this
    // itself (it walks compiled descriptors, not KDoc). Grouped by resultType in case two tools
    // ever share one.
    tools.groupBy { (_, kClass) -> kClass.trailblazeToolClassAnnotation().resultType }
      .forEach { (resultType, toolsForType) ->
        val simpleName = resultType.simpleName ?: return@forEach
        val doc = buildString {
          append("/**\n")
          toolsForType.forEach { (name, _) -> append(" * ${name.toolName}\n") }
          append(" * ${resultType.qualifiedName}\n")
          append(" */\n")
        }
        generated = generated.replace("export interface $simpleName {", doc + "export interface $simpleName {")
      }
    return generated
  }

  private const val HEADER: String =
    "// AUTO-GENERATED — do not edit by hand. Regenerate: ./gradlew :trailblaze-common:generateDtoTs\n" +
      "// CI's verifyDtoTs fails the build on drift.\n"
}

/** `args[0]` is the output file path. */
internal fun main(args: Array<String>) {
  val outFile = File(args.firstOrNull() ?: error("usage: BuiltInToolResultTsBindingsKt <output-file.ts>"))
  outFile.parentFile?.mkdirs()
  outFile.writeText(BuiltInToolResultTsBindings.generate(), Charsets.UTF_8)
  println("Wrote ${outFile.absolutePath}")
}
