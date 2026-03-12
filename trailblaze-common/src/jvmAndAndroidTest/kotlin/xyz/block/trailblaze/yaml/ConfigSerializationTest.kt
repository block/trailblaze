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

}
