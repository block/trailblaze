package xyz.block.trailblaze.yaml

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import org.junit.Test
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.createTrailblazeYaml

class ConfigSerializationTest {
  private val trailblazeYaml = createTrailblazeYaml()

  // Config serialization
  //
  // These are config-only unified docs (a `config:` block with no `trail:`/`trailhead:`), the
  // valid stepless metadata shape. Lowering always emits a ConfigTrailItem, so we read the config
  // off it via filterIsInstance rather than positionally.
  private fun decodeConfig(yaml: String): TrailConfig =
    trailblazeYaml.decodeTrail(yaml)
      .filterIsInstance<TrailYamlItem.ConfigTrailItem>()
      .single()
      .config

  @Test
  fun canDeserializeNullContext() {
    val yaml = """
config: {}
    """.trimIndent()

    assertThat(decodeConfig(yaml).context).isNull()
  }

  @Test
  fun canDeserializeSingleLineContext() {
    val yaml = """
config:
  context: This is some custom context
    """.trimIndent()

    assertThat(decodeConfig(yaml).context).isEqualTo("This is some custom context")
  }

  @Test
  fun canDeserializeMultiLineContext() {
    val yaml = """
config:
  context: |
    This is
    some multiline
    content
    """.trimIndent()

    assertThat(decodeConfig(yaml).context).isEqualTo("This is\nsome multiline\ncontent")
  }

}
