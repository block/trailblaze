package xyz.block.trailblaze.host

import kotlin.test.assertTrue
import org.junit.Test
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.host.recording.RecordingLlmService
import xyz.block.trailblaze.ui.models.TrailblazeServerState
import xyz.block.trailblaze.ui.tabs.home.ADD_CONTACT_YAML
import xyz.block.trailblaze.ui.tabs.home.EXPLORE_TRAILBLAZE_RELEASES_YAML
import xyz.block.trailblaze.ui.tabs.home.SEARCH_WIKIPEDIA_YAML
import xyz.block.trailblaze.ui.tabs.home.SET_ALARM_YAML
import xyz.block.trailblaze.yaml.createTrailblazeYaml

/**
 * Decode-sweep over every trail YAML the desktop app EMBEDS as a string constant — the Quick
 * Start samples, the editor's default `yamlContent`, and the format exemplar the "Generate
 * Trail" LLM prompt teaches. Each must parse through the production unified-trail decoder:
 * these strings reach `decodeUnifiedTrail` at runtime (Quick Start card → run, fresh install →
 * run, LLM output → save/run), so an embedded constant left in a dead format is a shipped
 * feature that throws on first click — exactly what happened when the v1 list-root parser was
 * removed (#5043) and no test decoded these constants (#5056).
 */
class EmbeddedTrailYamlDecodeTest {

  @Test
  fun `every embedded trail YAML decodes as a unified trail`() {
    val yaml = createTrailblazeYaml()
    val embedded = mapOf(
      "QuickStart SET_ALARM_YAML" to SET_ALARM_YAML,
      "QuickStart ADD_CONTACT_YAML" to ADD_CONTACT_YAML,
      "QuickStart EXPLORE_TRAILBLAZE_RELEASES_YAML" to EXPLORE_TRAILBLAZE_RELEASES_YAML,
      "QuickStart SEARCH_WIKIPEDIA_YAML" to SEARCH_WIKIPEDIA_YAML,
      "default editor yamlContent" to
        TrailblazeServerState.SavedTrailblazeAppConfig(
          selectedTrailblazeDriverTypes = emptyMap(),
        ).yamlContent,
    ) + TrailblazeDevicePlatform.entries.associate { platform ->
      val classifier = platform.asTrailblazeDeviceClassifier().classifier
      "Generate Trail LLM exemplar ($classifier)" to
        RecordingLlmService.unifiedTrailFormatExample(classifier)
    }

    val failures = embedded.mapNotNull { (name, content) ->
      runCatching { yaml.decodeUnifiedTrail(content) }
        .exceptionOrNull()
        ?.let { "$name: ${it.message}" }
    }
    assertTrue(
      failures.isEmpty(),
      "Embedded trail YAML constants must decode with the production unified-trail decoder " +
        "(they are run/saved verbatim at runtime), but these failed:\n" +
        failures.joinToString("\n"),
    )
  }
}
