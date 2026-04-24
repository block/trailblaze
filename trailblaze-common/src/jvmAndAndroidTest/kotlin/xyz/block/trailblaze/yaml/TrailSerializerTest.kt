package xyz.block.trailblaze.yaml

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import maestro.SwipeDirection
import maestro.orchestra.AssertConditionCommand
import maestro.orchestra.BackPressCommand
import maestro.orchestra.Condition
import maestro.orchestra.ElementSelector
import maestro.orchestra.SwipeCommand
import org.junit.Test
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.commands.AssertVisibleWithTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.InputTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.LaunchAppTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.LaunchAppTrailblazeTool.LaunchMode
import xyz.block.trailblaze.toolcalls.commands.PasteClipboardTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.TapOnElementWithTextTrailblazeTool
import xyz.block.trailblaze.utils.Ext.asJsonObject
import xyz.block.trailblaze.yaml.models.TrailblazeYamlBuilder
import kotlin.test.assertEquals
import xyz.block.trailblaze.util.Console

@OptIn(ExperimentalSerializationApi::class)
class TrailSerializerTest {
  private val trailblazeYaml = createTrailblazeYaml(setOf(TotallyCustomTool::class))
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

  @Serializable
  @TrailblazeToolClass("customToolForTestSetup")
  @LLMDescription("This is a test custom tool.")
  data class TotallyCustomTool(
    val str: String,
    val strList: List<String>,
  ) : TrailblazeTool

  @Test
  fun trailItemList() {
    val originalValue = TrailblazeYamlBuilder()
      .tools(
        listOf(
          LaunchAppTrailblazeTool(
            "com.example",
            launchMode = LaunchMode.FORCE_RESTART,
          ),
          TotallyCustomTool(
            str = "Testing Testing 123",
            strList = listOf("Testing 1", "Testing 2"),
          ),
        ),
      )
      .prompt(
        text = "This is a prompt",
        recordable = true,
        recording = listOf(
          InputTextTrailblazeTool("Hello World"),
          PasteClipboardTrailblazeTool,
        ),
      )
      .prompt(
        text = "This is a non-recordable prompt",
        recordable = false,
      )
      .prompt(
        text = "This is a prompt but doesn't have a recording yet, but could.",
      )
      .maestro(
        listOf(
          SwipeCommand(SwipeDirection.UP),
          BackPressCommand(),
        ),
      ).build()

    val yaml: String = trailblazeYamlInstance.encodeToString(
      ListSerializer(
        trailblazeYamlInstance.serializersModule.getContextual(TrailYamlItem::class)
          ?: error("Missing contextual serializer for TrailYamlItem"),
      ),
      originalValue,
    )

    Console.log("--- YAML ---\n$yaml\n---")

    val decoded: List<TrailYamlItem> = trailblazeYamlInstance.decodeFromString(
      ListSerializer(
        trailblazeYamlInstance.serializersModule.getContextual(TrailYamlItem::class)
          ?: error("Missing contextual serializer for TrailYamlItem"),
      ),
      yaml,
    )

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
  fun trailItemPromptTest() {
    val trailToolItem = TrailYamlItem.PromptsTrailItem(
      promptSteps = listOf(
        DirectionStep(
          step = "This is a prompt",
          recordable = true,
          recording = ToolRecording(
            tools = listOf(
              fromTrailblazeTool(InputTextTrailblazeTool("Hello World")),
              fromTrailblazeTool(PasteClipboardTrailblazeTool),
            ),
          ),
        ),
        VerificationStep(
          verify = "Check a thing",
          recordable = true,
          recording = ToolRecording(
            tools = listOf(
              fromTrailblazeTool(AssertVisibleWithTextTrailblazeTool("Hello World")),
            ),
          ),
        ),
      ),
    )
    val trailToolItemSerializer: KSerializer<TrailYamlItem>? =
      trailblazeYamlInstance.serializersModule.getContextual(TrailYamlItem::class)
    val yaml = trailblazeYamlInstance.encodeToString(
      trailToolItemSerializer as SerializationStrategy<TrailYamlItem>,
      trailToolItem,
    )
    Console.log(yaml)

    val deserialized: TrailYamlItem = trailblazeYamlInstance.decodeFromString(
      trailToolItemSerializer,
      yaml,
    )
    Console.log(deserialized.toString())
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
