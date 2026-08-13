package xyz.block.trailblaze.yaml

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import org.junit.Test
import xyz.block.trailblaze.toolcalls.commands.InputTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.LaunchAppTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.LaunchAppTrailblazeTool.LaunchMode
import xyz.block.trailblaze.toolcalls.commands.TapOnElementWithTextTrailblazeTool
import kotlin.test.assertEquals
import xyz.block.trailblaze.util.Console

@OptIn(ExperimentalSerializationApi::class)
class TrailSerializerTest {
  private val trailblazeYaml = createTrailblazeYaml()
  private val trailblazeYamlInstance = trailblazeYaml.getInstance()

  @Test
  fun simpleTest() {
    val yaml = trailblazeYamlInstance.encodeToString(
      LaunchAppTrailblazeTool(
        "com.example.app",
        launchMode = LaunchMode.FORCE_RESTART,
      ),
    )
    Console.log(yaml)
    val decoded = trailblazeYamlInstance.decodeFromString(LaunchAppTrailblazeTool.serializer(), yaml)
    Console.log(decoded.toString())
  }

  @Test
  fun trailItemToolTest() {
    val trailToolItem = TrailYamlItem.ToolTrailItem(
      listOf(
        fromTrailblazeTool(
          InputTextTrailblazeTool("hi"),
        ),
      ),
    )
    val trailToolItemSerializer = TrailYamlItem.ToolTrailItem.serializer()
    val yaml = trailblazeYamlInstance.encodeToString(
      trailToolItemSerializer,
      trailToolItem,
    )
    Console.log(yaml)

    val deserialized: TrailYamlItem.ToolTrailItem = trailblazeYamlInstance.decodeFromString(
      trailToolItemSerializer,
      yaml,
    )
    Console.log(deserialized.toString())

    assertEquals(trailToolItem, deserialized)
  }

  @Test
  fun singleToolTest() {
    val trailblazeTool = TapOnElementWithTextTrailblazeTool("Email")
    val toolWrapperSerializer = trailblazeYamlInstance.serializersModule.getContextual(TrailblazeToolYamlWrapper::class)
      ?: error("Missing contextual serializer for TrailblazeToolYamlWrapper")
    val yaml = trailblazeYamlInstance.encodeToString(
      toolWrapperSerializer,
      fromTrailblazeTool(trailblazeTool),
    )
    Console.log(yaml)

    val deserialized: TrailblazeToolYamlWrapper =
      trailblazeYamlInstance.decodeFromString(
        toolWrapperSerializer,
        yaml,
      )
    Console.log(deserialized.toString())

    assertEquals(trailblazeTool, deserialized.trailblazeTool)
  }

  @Test
  fun toolListTest() {
    val toolWrapperSerializer = trailblazeYamlInstance.serializersModule.getContextual(TrailblazeToolYamlWrapper::class)
      ?: error("Missing contextual serializer for TrailblazeToolYamlWrapper")
    val listOfToolsSerializer = ListSerializer(toolWrapperSerializer)

    val trailblazeTools = listOf(
      TapOnElementWithTextTrailblazeTool("ONE"),
      TapOnElementWithTextTrailblazeTool("TWO"),
    )
    val yaml = trailblazeYamlInstance.encodeToString(
      listOfToolsSerializer,
      trailblazeTools.map { fromTrailblazeTool(it) },
    )
    Console.log(yaml)

    val deserialized: List<TrailblazeToolYamlWrapper> = trailblazeYamlInstance.decodeFromString(
      listOfToolsSerializer,
      yaml,
    )
    Console.log(deserialized.toString())
  }

}
