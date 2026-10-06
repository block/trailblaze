package xyz.block.trailblaze.scripting.host

import kotlin.reflect.KClass
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import xyz.block.trailblaze.config.TrailblazeConfigYaml
import xyz.block.trailblaze.llm.config.ConfigResourceSource
import xyz.block.trailblaze.llm.config.TrailblazeConfigPaths
import xyz.block.trailblaze.llm.config.bundledConfigResourceSource
import xyz.block.trailblaze.util.Console

/**
 * Every [TrailblazeHostFunction] the runtime can call, discovered from
 * `trails/config/trailmaps/<trailmap>/host/<name>.host.yaml` descriptors.
 *
 * Host functions are Kotlin, so they only ever ship on the classpath (the CLI jar, or a test APK's
 * assets) — never in a workspace — and [bundled] reads only that source. A descriptor that doesn't
 * resolve is logged and skipped rather than failing discovery, the same containment the tool
 * loader applies to `.tool.yaml`.
 */
class TrailblazeHostFunctionRegistry(private val resourceSource: ConfigResourceSource) {

  /**
   * One registered host function, with the trailmap whose `host/` directory declares it. Both
   * serializers are resolved at discovery, so a class that can't decode its arguments or encode its
   * result is skipped there instead of failing every call.
   */
  data class Entry(
    val name: String,
    val trailmapId: String,
    val functionClass: KClass<out TrailblazeHostFunction<*>>,
    val annotation: TrailblazeHostFunctionClass,
    val argsSerializer: KSerializer<out TrailblazeHostFunction<*>>,
    val resultSerializer: KSerializer<Any>,
  )

  /** All host functions by name. */
  val all: Map<String, Entry> by lazy { discover() }

  fun resolve(name: String): Entry? = all[name]

  private fun discover(): Map<String, Entry> {
    val found = sortedMapOf<String, Entry>()
    val descriptors = try {
      resourceSource.discoverAndLoadRecursive(TrailblazeConfigPaths.TRAILMAPS_DIR, DESCRIPTOR_SUFFIX)
    } catch (e: Exception) {
      Console.error("Host function discovery failed: ${e::class.simpleName}: ${e.message}")
      return found
    }
    descriptors.toSortedMap().forEach { (relPath, content) ->
      // `relPath` is `<trailmap>/host/<name>.host.yaml`, the `trails/config/trailmaps/` prefix
      // already stripped by the resource-source contract.
      val segments = relPath.split('/')
      if (segments.size != 3 || segments[1] != HOST_DIR) {
        Console.log("Warning: Skipping '$relPath' — host function descriptors live at '<trailmap>/$HOST_DIR/<name>$DESCRIPTOR_SUFFIX'.")
        return@forEach
      }
      val entry = try {
        load(trailmapId = segments[0], content = content)
      } catch (e: Exception) {
        Console.log("Warning: Skipping host function '$relPath': ${e::class.simpleName}: ${e.message}")
        return@forEach
      }
      val existing = found[entry.name]
      if (existing != null) {
        Console.log(
          "Warning: Host function '${entry.name}' is declared by both '${existing.trailmapId}' and " +
            "'${entry.trailmapId}'; keeping '${existing.trailmapId}'.",
        )
        return@forEach
      }
      found[entry.name] = entry
    }
    return found
  }

  @OptIn(InternalSerializationApi::class)
  private fun load(trailmapId: String, content: String): Entry {
    val descriptor = TrailblazeConfigYaml.instance.decodeFromString(Descriptor.serializer(), content)
    val clazz = Class.forName(descriptor.className)
    require(TrailblazeHostFunction::class.java.isAssignableFrom(clazz)) {
      "${descriptor.className} does not implement TrailblazeHostFunction"
    }
    val annotation = requireNotNull(clazz.getAnnotation(TrailblazeHostFunctionClass::class.java)) {
      "${descriptor.className} is missing @TrailblazeHostFunctionClass"
    }
    require(annotation.name == descriptor.id) {
      "descriptor id '${descriptor.id}' does not match @TrailblazeHostFunctionClass name '${annotation.name}'"
    }
    @Suppress("UNCHECKED_CAST")
    val functionClass = clazz.kotlin as KClass<out TrailblazeHostFunction<*>>
    @Suppress("UNCHECKED_CAST")
    return Entry(
      name = descriptor.id,
      trailmapId = trailmapId,
      functionClass = functionClass,
      annotation = annotation,
      // `serializer()` throws for a class that isn't @Serializable — skipped by the caller.
      argsSerializer = functionClass.serializer(),
      resultSerializer = annotation.result.serializer() as KSerializer<Any>,
    )
  }

  @Serializable
  private data class Descriptor(
    val id: String,
    @SerialName("class") val className: String,
  )

  companion object {
    const val HOST_DIR = "host"
    const val DESCRIPTOR_SUFFIX = ".host.yaml"

    /** The classpath (JVM) or asset (Android) registry; discovered once per process. */
    val bundled: TrailblazeHostFunctionRegistry by lazy {
      TrailblazeHostFunctionRegistry(bundledConfigResourceSource())
    }
  }
}
