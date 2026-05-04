package xyz.block.trailblaze.waypoint

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import xyz.block.trailblaze.api.waypoint.WaypointDefinition
import java.io.File

object WaypointLoader {

  private const val FILE_SUFFIX = ".waypoint.yaml"

  internal val yaml = Yaml(
    configuration = YamlConfiguration(
      strictMode = false,
      encodeDefaults = false,
    ),
  )

  /** Result of [loadAllResilient]: successfully parsed definitions plus per-file failures. */
  data class LoadResult(
    val definitions: List<WaypointDefinition>,
    val failures: List<Failure>,
  ) {
    data class Failure(val file: File, val cause: Throwable)
  }

  fun loadFile(file: File): WaypointDefinition {
    require(file.exists()) { "Waypoint file not found: $file" }
    return yaml.decodeFromString(WaypointDefinition.serializer(), file.readText())
  }

  fun discover(root: File): List<File> {
    if (!root.exists()) return emptyList()
    return root.walkTopDown().filter { it.isFile && it.name.endsWith(FILE_SUFFIX) }.toList()
  }

  /**
   * Strict variant: aborts on the first parse failure. Useful in tests and when a single
   * malformed file should fail the operation. Most CLI callers should use [loadAllResilient]
   * so one bad file doesn't take down `locate` / `validate`.
   */
  fun loadAll(root: File): List<WaypointDefinition> = discover(root).map(::loadFile)

  /**
   * Resilient variant: returns successfully parsed definitions plus a list of per-file
   * failures. Callers can surface failures to the user without aborting the whole operation.
   */
  fun loadAllResilient(root: File): LoadResult {
    val definitions = mutableListOf<WaypointDefinition>()
    val failures = mutableListOf<LoadResult.Failure>()
    for (file in discover(root)) {
      try {
        definitions += loadFile(file)
      } catch (e: Exception) {
        failures += LoadResult.Failure(file, e)
      }
    }
    return LoadResult(definitions, failures)
  }
}
