package xyz.block.trailblaze.trailrunner

import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import xyz.block.trailblaze.yaml.unified.TrailDocument
import xyz.block.trailblaze.yaml.unified.UnifiedTrailStep
import java.io.File

object TrailDetailBuilder {

  private val TRAIL_SUFFIX = ".trail.yaml"

  fun build(root: File, file: File): TrailDetailResponse {
    val relative = file.relativeTo(root).invariantSeparatorsPath
    val derivedTitle = file.name.removeSuffix(TRAIL_SUFFIX).replace('-', ' ').replace('_', ' ')

    val rawYaml = try {
      file.readText()
    } catch (e: Exception) {
      Console.log("[TrailDetailBuilder] could not read ${file.absolutePath}: ${e.message}")
      return TrailDetailResponse(
        id = relative.removeSuffix(TRAIL_SUFFIX),
        path = relative,
        title = derivedTitle,
        yaml = "",
        steps = emptyList(),
      )
    }

    val tbYaml = createTrailblazeYaml()

    val config = try {
      tbYaml.extractTrailConfig(rawYaml)
    } catch (e: Exception) {
      Console.log("[TrailDetailBuilder] config parse failed for ${file.name}: ${e.message}")
      null
    }

    val steps = try {
      parseSteps(tbYaml, rawYaml)
    } catch (e: Exception) {
      Console.log("[TrailDetailBuilder] step parse failed for ${file.name}: ${e.message}")
      emptyList()
    }

    return TrailDetailResponse(
      id = relative.removeSuffix(TRAIL_SUFFIX),
      path = relative,
      title = config?.title ?: derivedTitle,
      yaml = rawYaml,
      steps = steps,
    )
  }

  private fun parseSteps(tbYaml: xyz.block.trailblaze.yaml.TrailblazeYaml, rawYaml: String): List<TrailStepEntry> {
    val unified = when (val doc = tbYaml.decodeTrailDocument(rawYaml)) {
      is TrailDocument.Unified -> doc.trail
    }
    val steps = mutableListOf<TrailStepEntry>()
    // The trailhead is a real (deterministically-executed) step 0; the UI renders it above the
    // trail steps with its own kind so recorded trails don't read as "No recorded steps".
    unified.trailhead?.let { th ->
      steps.add(TrailStepEntry(kind = "trailhead", text = th.step.trim(), tools = unionToolNames(th)))
    }
    for (step in unified.trail) {
      steps.add(
        TrailStepEntry(
          kind = if (step.verify) "verify" else "step",
          text = step.step.trim(),
          tools = unionToolNames(step),
        ),
      )
    }
    return steps
  }

  /**
   * Recorded tool names for a step, device-agnostic. The detail view has no device under test, so
   * it surfaces the UNION of every classifier's recorded tools (order-preserving, de-duplicated)
   * rather than one device's closest-wins slot — a reviewer sees every tool the trail replays on
   * any device.
   */
  private fun unionToolNames(step: UnifiedTrailStep): List<String> =
    step.recordings.values.flatten().map { it.name }.distinct()
}
