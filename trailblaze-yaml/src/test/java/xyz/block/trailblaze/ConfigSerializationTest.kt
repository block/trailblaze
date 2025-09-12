package xyz.block.trailblaze

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import maestro.orchestra.LaunchAppCommand
import org.junit.Test
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.TrailblazeYaml

class ConfigSerializationTest {
  private val trailblazeYaml = TrailblazeYaml()

  // Config serialization
  @Test
  fun canDeserializeNullContext() {
    val yaml = """
- config: {}
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    with(trailItems) {
      assertThat(size).isEqualTo(1)
      with(get(0) as TrailYamlItem.ConfigTrailItem) {
        assertThat(config.context).isNull()
      }
    }
  }

  @Test
  fun canDeserializeSingleLineContext() {
    val yaml = """
- config:
    context: This is some custom context
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    with(trailItems) {
      assertThat(size).isEqualTo(1)
      with(get(0) as TrailYamlItem.ConfigTrailItem) {
        assertThat(config.context).isEqualTo("This is some custom context")
      }
    }
  }

  @Test
  fun canDeserializeMultiLineContext() {
    val yaml = """
- config:
    context: |
      This is
      some multiline
      content
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    with(trailItems) {
      assertThat(size).isEqualTo(1)
      with(get(0) as TrailYamlItem.ConfigTrailItem) {
        assertThat(config.context).isEqualTo("This is\nsome multiline\ncontent")
      }
    }
  }

  @Test
  fun canDeserializeMaestro() {
    val yaml = """
- maestro:
  - launchApp:
      appId: com.android.settings
      stopApp: false
      clearState: false
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    with(trailItems) {
      assertThat(size).isEqualTo(1)
      with(get(0) as TrailYamlItem.MaestroTrailItem) {
        assertThat(this.maestro.maestroCommands).contains(
          LaunchAppCommand(appId = "com.android.settings", stopApp = false, clearState = false),
        )
      }
    }
  }
}
