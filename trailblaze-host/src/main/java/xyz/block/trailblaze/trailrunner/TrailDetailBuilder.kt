package xyz.block.trailblaze.trailrunner

import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.yaml.DirectionStep
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.VerificationStep
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import xyz.block.trailblaze.yaml.unified.TrailDocument
import xyz.block.trailblaze.yaml.unified.UnifiedTrailAdapter
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
    val items: List<TrailYamlItem> = when (val doc = tbYaml.decodeTrailDocument(rawYaml)) {
      is TrailDocument.V1 -> doc.items
      is TrailDocument.Unified -> UnifiedTrailAdapter.lowerToTrailItems(doc.trail, emptyList())
    }
    val steps = mutableListOf<TrailStepEntry>()
    for (item in items) {
      if (item !is TrailYamlItem.PromptsTrailItem) continue
      for (promptStep in item.promptSteps) {
        val tools = promptStep.recording?.tools?.map { it.name }.orEmpty()
        when (promptStep) {
          is DirectionStep -> steps.add(
            TrailStepEntry(kind = "step", text = promptStep.step.trim(), tools = tools),
          )
          is VerificationStep -> steps.add(
            TrailStepEntry(kind = "verify", text = promptStep.verify.trim(), tools = tools),
          )
          else -> steps.add(
            TrailStepEntry(kind = "step", text = promptStep.prompt.trim(), tools = tools),
          )
        }
      }
    }
    return steps
  }
}
