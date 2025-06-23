package xyz.block.trailblaze

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
import xyz.block.trailblaze.toolcalls.commands.InputTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.LaunchAppTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.LaunchAppTrailblazeTool.LaunchMode
import xyz.block.trailblaze.toolcalls.commands.PressBackTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.TapOnElementWithTextTrailblazeTool
import xyz.block.trailblaze.yaml.TrailblazeYaml
import xyz.block.trailblaze.yaml.models.MaestroCommandList
import xyz.block.trailblaze.yaml.models.TrailYamlItem
import xyz.block.trailblaze.yaml.models.TrailYamlItem.PromptsTrailItem.PromptStep
import xyz.block.trailblaze.yaml.models.TrailYamlItem.PromptsTrailItem.PromptStep.ToolRecording
import xyz.block.trailblaze.yaml.models.TrailblazeToolYamlWrapper
import xyz.block.trailblaze.yaml.models.TrailblazeToolYamlWrapper.TrailblazeToolYamlWrapperSerializer
import xyz.block.trailblaze.yaml.models.TrailblazeYamlBuilder
import kotlin.test.assertEquals

class TrailSerializerTest {
  val trailblazeYaml = TrailblazeYaml(setOf(TotallyCustomTool::class))
  val trailblazeYamlInstance = trailblazeYaml.getInstance()

  @Test
  fun simpleTest() {
    val yaml = trailblazeYamlInstance.encodeToString(
      LaunchAppTrailblazeTool(
        "com.squareup",
        launchMode = LaunchMode.FORCE_RESTART,
      ),
    )
    println(yaml)
    val decoded = trailblazeYamlInstance.decodeFromString(LaunchAppTrailblazeTool.serializer(), yaml)
    println(decoded)
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
          PressBackTrailblazeTool,
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

    println("--- YAML ---\n$yaml\n---")

    val decoded: List<TrailYamlItem> = trailblazeYamlInstance.decodeFromString(
      ListSerializer(
        trailblazeYamlInstance.serializersModule.getContextual(TrailYamlItem::class)
          ?: error("Missing contextual serializer for TrailYamlItem"),
      ),
      yaml,
    )

    println(decoded)
  }

  @Test
  fun trailItemMaestro() {
    val originalValue = TrailYamlItem.MaestroTrailItem(
      MaestroCommandList(
        listOf(
          AssertConditionCommand(
            Condition(
              visible = ElementSelector(
                textRegex = "Hello World",
              ),
            ),
          ),
          SwipeCommand(SwipeDirection.UP),
          BackPressCommand(),
        ),
      ),
    )
    val yamlInstance = trailblazeYaml.getInstance()
    val yaml: String = yamlInstance.encodeToString(originalValue)
    println(yaml)

    @OptIn(ExperimentalSerializationApi::class)
    val deserializer = yamlInstance.serializersModule.getContextual(
      TrailYamlItem::class,
    ) as DeserializationStrategy<TrailYamlItem>
    val decoded: TrailYamlItem = yamlInstance.decodeFromString(deserializer, yaml)
    println(decoded)
  }

  @Test
  fun trailItemToolTest() {
    val trailToolItem = TrailYamlItem.ToolTrailItem(
      listOf(
        TrailblazeToolYamlWrapper.fromTrailblazeTool(
          InputTextTrailblazeTool("hi"),
        ),
      ),
    )
    val trailToolItemSerializer = TrailYamlItem.ToolTrailItem.serializer()
    val yaml = trailblazeYamlInstance.encodeToString(
      trailToolItemSerializer,
      trailToolItem,
    )
    println(yaml)

    val deserialized: TrailYamlItem.ToolTrailItem = trailblazeYamlInstance.decodeFromString(
      trailToolItemSerializer,
      yaml,
    )
    println(deserialized)

    assertEquals(trailToolItem, deserialized)
  }

  @Test
  fun trailItemPromptTest() {
    val trailToolItem = TrailYamlItem.PromptsTrailItem(
      promptStep = PromptStep(
        text = "This is a prompt",
        recordable = true,
        recording = ToolRecording(
          tools = listOf(
            TrailblazeToolYamlWrapper.fromTrailblazeTool(InputTextTrailblazeTool("Hello World")),
            TrailblazeToolYamlWrapper.fromTrailblazeTool(PressBackTrailblazeTool),
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
    println(yaml)

    val deserialized: TrailYamlItem = trailblazeYamlInstance.decodeFromString(
      trailToolItemSerializer,
      yaml,
    )
    println(deserialized)
  }

  @Test
  fun singleToolTest() {
    val trailblazeTool = TapOnElementWithTextTrailblazeTool("Email")
    val yaml = trailblazeYamlInstance.encodeToString(
      trailblazeYaml.trailblazeToolYamlWrapperSerializer,
      TrailblazeToolYamlWrapper.fromTrailblazeTool(trailblazeTool),
    )
    println(yaml)

    val deserialized: TrailblazeToolYamlWrapper =
      trailblazeYamlInstance.decodeFromString(
        TrailblazeToolYamlWrapperSerializer(
          allTrailblazeToolClasses = trailblazeYaml.allTrailblazeToolClasses,
        ),
        yaml,
      )
    println(deserialized)

    assertEquals(trailblazeTool, deserialized.trailblazeTool)
  }

  @Test
  fun toolListTest() {
    val listOfToolsSerializer = ListSerializer(trailblazeYaml.trailblazeToolYamlWrapperSerializer)

    val trailblazeTools = listOf(
      TapOnElementWithTextTrailblazeTool("ONE"),
      TapOnElementWithTextTrailblazeTool("TWO"),
    )
    val yaml = trailblazeYamlInstance.encodeToString(
      listOfToolsSerializer,
      trailblazeTools.map { TrailblazeToolYamlWrapper.fromTrailblazeTool(it) },
    )
    println(yaml)

    val deserialized: List<TrailblazeToolYamlWrapper> = trailblazeYamlInstance.decodeFromString(
      listOfToolsSerializer,
      yaml,
    )
    println(deserialized)

    assertEquals(trailblazeTools, deserialized.map { it.trailblazeTool })
  }

  @Test
  fun promptStepTest() {
    val promptStep = PromptStep(
      text = "This is some prompt",
      recordable = false,
      recording = ToolRecording(
        tools = listOf(
          TrailblazeToolYamlWrapper.fromTrailblazeTool(InputTextTrailblazeTool("Hello World")),
          TrailblazeToolYamlWrapper.fromTrailblazeTool(PressBackTrailblazeTool),
        ),
      ),
    )
    val yaml = trailblazeYamlInstance.encodeToString(
      PromptStep.serializer(),
      promptStep,
    )
    println("yaml:\n---\n$yaml\n---")
    val deserialized: PromptStep = trailblazeYamlInstance.decodeFromString(PromptStep.serializer(), yaml)
    println(deserialized)
    assertEquals(promptStep, deserialized)
  }

  @Test
  fun toolRecordingTest() {
    val trailblazeTools = listOf(
      TapOnElementWithTextTrailblazeTool("ONE"),
      LaunchAppTrailblazeTool("com.something"),
    )
    val toolRecording = ToolRecording(
      tools = trailblazeTools.map {
        TrailblazeToolYamlWrapper.fromTrailblazeTool(it)
      },
    )

    val yaml = trailblazeYamlInstance.encodeToString(
      ToolRecording.serializer(),
      toolRecording,
    )
    println(yaml)

    val deserialized: ToolRecording = trailblazeYamlInstance.decodeFromString(ToolRecording.serializer(), yaml)
    println(deserialized)

    assertEquals(trailblazeTools, deserialized.tools.map { it.trailblazeTool })
  }
}
