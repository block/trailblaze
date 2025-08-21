package xyz.block.trailblaze

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import maestro.orchestra.ApplyConfigurationCommand
import maestro.orchestra.AssertConditionCommand
import maestro.orchestra.Condition
import maestro.orchestra.ElementSelector
import xyz.block.trailblaze.TrailSerializerTest.TotallyCustomTool
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.TrailYamlItem.MaestroTrailItem
import xyz.block.trailblaze.yaml.TrailblazeYaml
import kotlin.test.Test

class MaestroTrailSerializerTest {
  private val trailblazeYaml = TrailblazeYaml(setOf(TotallyCustomTool::class))
  private val yamlInstance = trailblazeYaml.getInstance()
  private val listSerializer = ListSerializer(TrailYamlItem.MaestroTrailItem.serializer())

  @Test
  fun assertVisibleText() {
    val yaml = """
- maestro:
  - assertVisible:
      text: "Hello"
    """.trimIndent()
    val trailItems = trailblazeYaml.decodeTrail(yaml)
    with(trailItems) {
      assertThat(size).isEqualTo(1)
      with(get(0) as MaestroTrailItem) {
        assertThat(maestro.maestroCommands.size).isEqualTo(2)
        with(maestro.maestroCommands) {
          assertThat(get(0)).isInstanceOf(ApplyConfigurationCommand::class)
          assertThat(get(1)).isEqualTo(
            AssertConditionCommand(
              condition = Condition(
                visible = ElementSelector(
                  textRegex = "Hello",
                ),
              ),
            ),
          )
        }
      }
    }
  }

  @Test
  fun assertVisibleChecked() {
    val yaml = """
- maestro:
  - assertVisible:
      checked: false
  - assertVisible:
      checked: true
    """.trimIndent()
    val trailItems = trailblazeYaml.decodeTrail(yaml)
    with(trailItems) {
      assertThat(size).isEqualTo(1)
      with(get(0) as MaestroTrailItem) {
        assertThat(maestro.maestroCommands.size).isEqualTo(3)
        with(maestro.maestroCommands) {
          assertThat(get(0)).isInstanceOf(ApplyConfigurationCommand::class)
          assertThat(get(1)).isEqualTo(
            AssertConditionCommand(
              condition = Condition(
                visible = ElementSelector(
                  checked = false,
                ),
              ),
            ),
          )
          assertThat(get(2)).isEqualTo(
            AssertConditionCommand(
              condition = Condition(
                visible = ElementSelector(
                  checked = true,
                ),
              ),
            ),
          )
        }
      }
    }
  }

  // This test can be fragile if the input yaml does not exactly match the output
  // As long as the spacing matches then it's fine
  @Test
  fun assertVisibleMultipleProperties() {
    val yaml = """
- maestro:
  - assertVisible:
      checked: false
      containsChild:
        text: Sample
    """.trimIndent()
    val trailItems = trailblazeYaml.decodeTrail(yaml)
    with(trailItems) {
      assertThat(size).isEqualTo(1)
      with(get(0) as MaestroTrailItem) {
        assertThat(maestro.maestroCommands.size).isEqualTo(2)
        with(maestro.maestroCommands) {
          assertThat(get(0)).isInstanceOf(ApplyConfigurationCommand::class)
          assertThat(get(1)).isEqualTo(
            AssertConditionCommand(
              condition = Condition(
                visible = ElementSelector(
                  checked = false,
                  containsChild = ElementSelector(
                    textRegex = "Sample",
                  ),
                ),
              ),
            ),
          )
        }
      }
    }
    val outputYaml = yamlInstance.encodeToString(listSerializer, trailItems as List<MaestroTrailItem>)
    assertThat(outputYaml).isEqualTo(yaml)
  }
}
