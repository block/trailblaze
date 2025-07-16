package xyz.block.trailblaze

import ai.koog.agents.core.tools.annotations.LLMDescription
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
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
import xyz.block.trailblaze.toolcalls.commands.EraseTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.HideKeyboardTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.InputTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.LaunchAppTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.LaunchAppTrailblazeTool.LaunchMode
import xyz.block.trailblaze.toolcalls.commands.LongPressElementWithAccessibilityTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.LongPressOnElementWithTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.PressBackTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.SwipeTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.TapOnElementWithAccessiblityTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.TapOnElementWithTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.TapOnPointTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.WaitForIdleSyncTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.memory.AssertEqualsTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.memory.AssertMathTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.memory.AssertNotEqualsTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.memory.RememberNumberTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.memory.RememberTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.memory.RememberWithAiTrailblazeTool
import xyz.block.trailblaze.yaml.MaestroCommandList
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.TrailYamlItem.PromptsTrailItem.PromptStep
import xyz.block.trailblaze.yaml.TrailYamlItem.PromptsTrailItem.PromptStep.ToolRecording
import xyz.block.trailblaze.yaml.TrailblazeToolYamlWrapper
import xyz.block.trailblaze.yaml.TrailblazeYaml
import xyz.block.trailblaze.yaml.models.TrailblazeYamlBuilder
import xyz.block.trailblaze.yaml.serializers.TrailblazeToolYamlWrapperSerializer
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
      promptSteps = listOf(
        PromptStep(
          text = "This is a prompt",
          recordable = true,
          recording = ToolRecording(
            tools = listOf(
              TrailblazeToolYamlWrapper.fromTrailblazeTool(InputTextTrailblazeTool("Hello World")),
              TrailblazeToolYamlWrapper.fromTrailblazeTool(PressBackTrailblazeTool),
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

  // Deserialization tests
  @Test
  fun deserializeBasicPrompt() {
    val yaml = """
- prompts:
    - text: This is a prompt but doesn't have a recording yet, but could.
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    with(trailItems) {
      assertThat(size).isEqualTo(1)
      val actual = trailItems[0]
      assertThat(actual).isInstanceOf(TrailYamlItem.PromptsTrailItem::class)
      with(actual as TrailYamlItem.PromptsTrailItem) {
        assertThat(promptSteps).isEqualTo(
          listOf(
            PromptStep(
              text = "This is a prompt but doesn't have a recording yet, but could.",
              recordable = true,
              recording = null,
            ),
          ),
        )
      }
    }
  }

  @Test
  fun deserializeRecordedPrompt() {
    val yaml = """
- prompts:
    - text: This is a prompt
      recording:
        tools:
        - inputText:
            text: Hello World
        - pressBack: {}
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    with(trailItems) {
      assertThat(size).isEqualTo(1)
      val actual = trailItems[0]
      assertThat(actual).isInstanceOf(TrailYamlItem.PromptsTrailItem::class)
      with(actual as TrailYamlItem.PromptsTrailItem) {
        assertThat(promptSteps).isEqualTo(
          listOf(
            PromptStep(
              text = "This is a prompt",
              recordable = true,
              recording = ToolRecording(
                tools = listOf(
                  TrailblazeToolYamlWrapper(
                    name = "inputText",
                    trailblazeTool = InputTextTrailblazeTool(text = "Hello World"),
                  ),
                  TrailblazeToolYamlWrapper(
                    name = "pressBack",
                    trailblazeTool = PressBackTrailblazeTool,
                  ),
                ),
              ),
            ),
          ),
        )
      }
    }
  }

  @Test
  fun deserializeNonRecordablePrompt() {
    val yaml = """
- prompts:
    - text: This is a non-recordable prompt
      recordable: false
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    with(trailItems) {
      assertThat(size).isEqualTo(1)
      val actual = trailItems[0]
      assertThat(actual).isInstanceOf(TrailYamlItem.PromptsTrailItem::class)
      with(actual as TrailYamlItem.PromptsTrailItem) {
        assertThat(promptSteps).isEqualTo(
          listOf(
            PromptStep(
              text = "This is a non-recordable prompt",
              recordable = false,
              recording = null,
            ),
          ),
        )
      }
    }
  }

  @Test
  fun deserializeManyPrompts() {
    val yaml = """
- prompts:
    - text: This is a prompt but doesn't have a recording yet, but could.
    - text: This is a prompt
      recording:
          tools:
          - inputText:
              text: Hello World
          - pressBack: {}
    - text: This is a non-recordable prompt
      recordable: false
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    with(trailItems) {
      assertThat(size).isEqualTo(1)
      with(trailItems[0] as TrailYamlItem.PromptsTrailItem) {
        assertThat(promptSteps).isEqualTo(
          listOf(
            PromptStep(
              text = "This is a prompt but doesn't have a recording yet, but could.",
              recordable = true,
              recording = null,
            ),
            PromptStep(
              text = "This is a prompt",
              recordable = true,
              recording = ToolRecording(
                tools = listOf(
                  TrailblazeToolYamlWrapper(
                    name = "inputText",
                    trailblazeTool = InputTextTrailblazeTool(text = "Hello World"),
                  ),
                  TrailblazeToolYamlWrapper(
                    name = "pressBack",
                    trailblazeTool = PressBackTrailblazeTool,
                  ),
                ),
              ),
            ),
            PromptStep(
              text = "This is a non-recordable prompt",
              recordable = false,
              recording = null,
            ),
          ),
        )
      }
    }
  }

  // YAML allows defining the mappings on the line after the - so it can work if
  // the tight spacing is hard to read
  @Test
  fun deserializeManyPromptsWithSpacing() {
    val yaml = """
- prompts:
    - 
      text: This is a prompt but doesn't have a recording yet, but could.
    - 
      text: This is a prompt
      recording:
          tools:
          - inputText:
              text: Hello World
          - pressBack: {}
    - 
      text: This is a non-recordable prompt
      recordable: false
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    with(trailItems) {
      assertThat(size).isEqualTo(1)
      with(trailItems[0] as TrailYamlItem.PromptsTrailItem) {
        assertThat(promptSteps).isEqualTo(
          listOf(
            PromptStep(
              text = "This is a prompt but doesn't have a recording yet, but could.",
              recordable = true,
              recording = null,
            ),
            PromptStep(
              text = "This is a prompt",
              recordable = true,
              recording = ToolRecording(
                tools = listOf(
                  TrailblazeToolYamlWrapper(
                    name = "inputText",
                    trailblazeTool = InputTextTrailblazeTool(text = "Hello World"),
                  ),
                  TrailblazeToolYamlWrapper(
                    name = "pressBack",
                    trailblazeTool = PressBackTrailblazeTool,
                  ),
                ),
              ),
            ),
            PromptStep(
              text = "This is a non-recordable prompt",
              recordable = false,
              recording = null,
            ),
          ),
        )
      }
    }
  }

  // This is still valid yaml but needlessly verbose due to the extra '- prompts:' lines
  @Test
  fun deserializeManyPromptsVerbose() {
    val yaml = """
- prompts:
    - text: This is a prompt but doesn't have a recording yet, but could.
- prompts:
    - text: This is a prompt
      recording:
        tools:
            - inputText:
                text: Hello World
            - pressBack: {}
- prompts:
    - text: This is a non-recordable prompt
      recordable: false
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    with(trailItems) {
      assertThat(size).isEqualTo(3)
      with(trailItems[0] as TrailYamlItem.PromptsTrailItem) {
        assertThat(promptSteps).isEqualTo(
          listOf(
            PromptStep(
              text = "This is a prompt but doesn't have a recording yet, but could.",
              recordable = true,
              recording = null,
            ),
          ),
        )
      }
      with(trailItems[1] as TrailYamlItem.PromptsTrailItem) {
        assertThat(promptSteps).isEqualTo(
          listOf(
            PromptStep(
              text = "This is a prompt",
              recordable = true,
              recording = ToolRecording(
                tools = listOf(
                  TrailblazeToolYamlWrapper(
                    name = "inputText",
                    trailblazeTool = InputTextTrailblazeTool(text = "Hello World"),
                  ),
                  TrailblazeToolYamlWrapper(
                    name = "pressBack",
                    trailblazeTool = PressBackTrailblazeTool,
                  ),
                ),
              ),
            ),
          ),
        )
      }
      with(trailItems[2] as TrailYamlItem.PromptsTrailItem) {
        assertThat(promptSteps).isEqualTo(
          listOf(
            PromptStep(
              text = "This is a non-recordable prompt",
              recordable = false,
              recording = null,
            ),
          ),
        )
      }
    }
  }

  // Tool deserialization tests
  @Test
  fun deserializeRememberTextTool() {
    val yaml = """
- tools:
    - rememberText:
        prompt: here is a prompt
        variable: promptVar
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    with(trailItems) {
      assertThat(size).isEqualTo(1)
      with(get(0) as TrailYamlItem.ToolTrailItem) {
        assertThat(tools.size).isEqualTo(1)
        assertThat(tools[0]).isEqualTo(
          TrailblazeToolYamlWrapper(
            name = "rememberText",
            trailblazeTool = RememberTextTrailblazeTool(
              prompt = "here is a prompt",
              variable = "promptVar",
            ),
          ),
        )
      }
    }
  }

  @Test
  fun deserializeRememberNumberTool() {
    val yaml = """
- tools:
    - rememberNumber:
        prompt: here is a prompt
        variable: promptVar
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    with(trailItems) {
      assertThat(size).isEqualTo(1)
      with(get(0) as TrailYamlItem.ToolTrailItem) {
        assertThat(tools.size).isEqualTo(1)
        assertThat(tools[0]).isEqualTo(
          TrailblazeToolYamlWrapper(
            name = "rememberNumber",
            trailblazeTool = RememberNumberTrailblazeTool(
              prompt = "here is a prompt",
              variable = "promptVar",
            ),
          ),
        )
      }
    }
  }

  @Test
  fun deserializeRememberWithAiTool() {
    val yaml = """
- tools:
    - rememberWithAi:
        prompt: here is a prompt
        variable: promptVar
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    with(trailItems) {
      assertThat(size).isEqualTo(1)
      with(get(0) as TrailYamlItem.ToolTrailItem) {
        assertThat(tools.size).isEqualTo(1)
        assertThat(tools[0]).isEqualTo(
          TrailblazeToolYamlWrapper(
            name = "rememberWithAi",
            trailblazeTool = RememberWithAiTrailblazeTool(
              prompt = "here is a prompt",
              variable = "promptVar",
            ),
          ),
        )
      }
    }
  }

  @Test
  fun deserializeAssertMathTool() {
    val yaml = """
- tools:
    - assertMath:
        expression: "[[number of water bottles available]] - {{stockCount}}"
        expected: 1
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    with(trailItems) {
      assertThat(size).isEqualTo(1)
      with(get(0) as TrailYamlItem.ToolTrailItem) {
        assertThat(tools.size).isEqualTo(1)
        assertThat(tools[0]).isEqualTo(
          TrailblazeToolYamlWrapper(
            name = "assertMath",
            trailblazeTool = AssertMathTrailblazeTool(
              expression = "[[number of water bottles available]] - {{stockCount}}",
              expected = "1",
            ),
          ),
        )
      }
    }
  }

  @Test
  fun deserializeAssertEqualsTool() {
    val yaml = """
- tools:
    - assertEquals:
        actual: "some actual value"
        expected: "some expected value"
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    with(trailItems) {
      assertThat(size).isEqualTo(1)
      with(get(0) as TrailYamlItem.ToolTrailItem) {
        assertThat(tools.size).isEqualTo(1)
        assertThat(tools[0]).isEqualTo(
          TrailblazeToolYamlWrapper(
            name = "assertEquals",
            trailblazeTool = AssertEqualsTrailblazeTool(
              actual = "some actual value",
              expected = "some expected value",
            ),
          ),
        )
      }
    }
  }

  @Test
  fun deserializeAssertNotEqualsTool() {
    val yaml = """
- tools:
    - assertNotEquals:
        actual: "some actual value"
        expected: "some expected value"
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    with(trailItems) {
      assertThat(size).isEqualTo(1)
      with(get(0) as TrailYamlItem.ToolTrailItem) {
        assertThat(tools.size).isEqualTo(1)
        assertThat(tools[0]).isEqualTo(
          TrailblazeToolYamlWrapper(
            name = "assertNotEquals",
            trailblazeTool = AssertNotEqualsTrailblazeTool(
              actual = "some actual value",
              expected = "some expected value",
            ),
          ),
        )
      }
    }
  }

  @Test
  fun deserializeHideKeyboardTool() {
    val yaml = """
- tools:
    - hideKeyboard: {}
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    with(trailItems) {
      assertThat(size).isEqualTo(1)
      with(get(0) as TrailYamlItem.ToolTrailItem) {
        assertThat(tools.size).isEqualTo(1)
        with(tools[0]) {
          assertThat(name).isEqualTo("hideKeyboard")
          assertThat(trailblazeTool).isInstanceOf(HideKeyboardTrailblazeTool::class)
        }
      }
    }
  }

  @Test
  fun deserializeInputTextTool() {
    val yaml = """
- tools:
    - inputText:
        text: Text to enter
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    with(trailItems) {
      assertThat(size).isEqualTo(1)
      with(get(0) as TrailYamlItem.ToolTrailItem) {
        assertThat(tools.size).isEqualTo(1)
        TrailblazeToolYamlWrapper(
          name = "inputText",
          trailblazeTool = InputTextTrailblazeTool(
            text = "Text to enter",
          ),
        )
      }
    }
  }

  @Test
  fun deserializeEmptyEraseTextTool() {
    val yaml = """
- tools:
    - eraseText: {}
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    with(trailItems) {
      assertThat(size).isEqualTo(1)
      with(get(0) as TrailYamlItem.ToolTrailItem) {
        assertThat(tools.size).isEqualTo(1)
        TrailblazeToolYamlWrapper(
          name = "inputText",
          trailblazeTool = EraseTextTrailblazeTool(
            charactersToErase = null,
          ),
        )
      }
    }
  }

  @Test
  fun deserializeEraseTextTool() {
    val yaml = """
- tools:
    - eraseText:
        charactersToErase: 10
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    with(trailItems) {
      assertThat(size).isEqualTo(1)
      with(get(0) as TrailYamlItem.ToolTrailItem) {
        assertThat(tools.size).isEqualTo(1)
        TrailblazeToolYamlWrapper(
          name = "inputText",
          trailblazeTool = EraseTextTrailblazeTool(
            charactersToErase = 10,
          ),
        )
      }
    }
  }

  @Test
  fun deserializePressBackTool() {
    val yaml = """
- tools:
    - pressBack: {}
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    with(trailItems) {
      assertThat(size).isEqualTo(1)
      with(get(0) as TrailYamlItem.ToolTrailItem) {
        assertThat(tools.size).isEqualTo(1)
        TrailblazeToolYamlWrapper(
          name = "inputText",
          trailblazeTool = PressBackTrailblazeTool,
        )
      }
    }
  }

  @Test
  fun deserializeSwipeUpTool() {
    val yaml = """
- tools:
    - swipe:
        direction: UP
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    with(trailItems) {
      assertThat(size).isEqualTo(1)
      with(get(0) as TrailYamlItem.ToolTrailItem) {
        assertThat(tools.size).isEqualTo(1)
        TrailblazeToolYamlWrapper(
          name = "swipe",
          trailblazeTool = SwipeTrailblazeTool(
            direction = "UP",
          ),
        )
      }
    }
  }

  @Test
  fun deserializeSwipeDownTool() {
    val yaml = """
- tools:
    - swipe:
        direction: DOWN
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    with(trailItems) {
      assertThat(size).isEqualTo(1)
      with(get(0) as TrailYamlItem.ToolTrailItem) {
        assertThat(tools.size).isEqualTo(1)
        TrailblazeToolYamlWrapper(
          name = "swipe",
          trailblazeTool = SwipeTrailblazeTool(
            direction = "DOWN",
          ),
        )
      }
    }
  }

  @Test
  fun deserializeSwipeLeftTool() {
    val yaml = """
- tools:
    - swipe:
        direction: LEFT
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    with(trailItems) {
      assertThat(size).isEqualTo(1)
      with(get(0) as TrailYamlItem.ToolTrailItem) {
        assertThat(tools.size).isEqualTo(1)
        TrailblazeToolYamlWrapper(
          name = "swipe",
          trailblazeTool = SwipeTrailblazeTool(
            direction = "LEFT",
          ),
        )
      }
    }
  }

  @Test
  fun deserializeSwipeRightTool() {
    val yaml = """
- tools:
    - swipe:
        direction: RIGHT
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    with(trailItems) {
      assertThat(size).isEqualTo(1)
      with(get(0) as TrailYamlItem.ToolTrailItem) {
        assertThat(tools.size).isEqualTo(1)
        TrailblazeToolYamlWrapper(
          name = "swipe",
          trailblazeTool = SwipeTrailblazeTool(
            direction = "RIGHT",
          ),
        )
      }
    }
  }

  @Test
  fun deserializeSwipeUpToolWithText() {
    val yaml = """
- tools:
    - swipe:
        direction: UP
        swipeOnElementText: Text
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    with(trailItems) {
      assertThat(size).isEqualTo(1)
      with(get(0) as TrailYamlItem.ToolTrailItem) {
        assertThat(tools.size).isEqualTo(1)
        TrailblazeToolYamlWrapper(
          name = "swipe",
          trailblazeTool = SwipeTrailblazeTool(
            direction = "UP",
            swipeOnElementText = "Text",
          ),
        )
      }
    }
  }

  @Test
  fun deserializeSwipeDownToolWithText() {
    val yaml = """
- tools:
    - swipe:
        direction: DOWN
        swipeOnElementText: Text
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    with(trailItems) {
      assertThat(size).isEqualTo(1)
      with(get(0) as TrailYamlItem.ToolTrailItem) {
        assertThat(tools.size).isEqualTo(1)
        TrailblazeToolYamlWrapper(
          name = "swipe",
          trailblazeTool = SwipeTrailblazeTool(
            direction = "DOWN",
            swipeOnElementText = "Text",
          ),
        )
      }
    }
  }

  @Test
  fun deserializeSwipeLeftToolWithText() {
    val yaml = """
- tools:
    - swipe:
        direction: LEFT
        swipeOnElementText: Text
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    with(trailItems) {
      assertThat(size).isEqualTo(1)
      with(get(0) as TrailYamlItem.ToolTrailItem) {
        assertThat(tools.size).isEqualTo(1)
        TrailblazeToolYamlWrapper(
          name = "swipe",
          trailblazeTool = SwipeTrailblazeTool(
            direction = "LEFT",
            swipeOnElementText = "Text",
          ),
        )
      }
    }
  }

  @Test
  fun deserializeSwipeRightToolWithText() {
    val yaml = """
- tools:
    - swipe:
        direction: RIGHT
        swipeOnElementText: Text
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    with(trailItems) {
      assertThat(size).isEqualTo(1)
      with(get(0) as TrailYamlItem.ToolTrailItem) {
        assertThat(tools.size).isEqualTo(1)
        TrailblazeToolYamlWrapper(
          name = "swipe",
          trailblazeTool = SwipeTrailblazeTool(
            direction = "RIGHT",
            swipeOnElementText = "Text",
          ),
        )
      }
    }
  }

  @Test
  fun deserializeWaitForIdleSync() {
    val yaml = """
- tools:
    - wait: {}
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    with(trailItems) {
      assertThat(size).isEqualTo(1)
      with(get(0) as TrailYamlItem.ToolTrailItem) {
        assertThat(tools.size).isEqualTo(1)
        TrailblazeToolYamlWrapper(
          name = "wait",
          trailblazeTool = WaitForIdleSyncTrailblazeTool(
            timeToWaitInSeconds = 5,
          ),
        )
      }
    }
  }

  @Test
  fun deserializeWaitForIdleSyncWithTime() {
    val yaml = """
- tools:
    - wait:
        timeToWaitInSeconds: 15
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    with(trailItems) {
      assertThat(size).isEqualTo(1)
      with(get(0) as TrailYamlItem.ToolTrailItem) {
        assertThat(tools.size).isEqualTo(1)
        TrailblazeToolYamlWrapper(
          name = "wait",
          trailblazeTool = WaitForIdleSyncTrailblazeTool(
            timeToWaitInSeconds = 15,
          ),
        )
      }
    }
  }

  @Test
  fun deserializeLaunchAppTool() {
    val yaml = """
- tools:
    - launchApp:
        appId: com.squareup.cash.beta.debug
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    with(trailItems) {
      assertThat(size).isEqualTo(1)
      with(get(0) as TrailYamlItem.ToolTrailItem) {
        assertThat(tools.size).isEqualTo(1)
        TrailblazeToolYamlWrapper(
          name = "wait",
          trailblazeTool = LaunchAppTrailblazeTool(
            appId = "com.squareup.cash.beta.debug",
            launchMode = LaunchMode.REINSTALL,
          ),
        )
      }
    }
  }

  @Test
  fun deserializeLaunchAppToolReinstall() {
    val yaml = """
- tools:
    - launchApp:
        appId: com.squareup.cash.beta.debug
        launchMode: REINSTALL
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    with(trailItems) {
      assertThat(size).isEqualTo(1)
      with(get(0) as TrailYamlItem.ToolTrailItem) {
        assertThat(tools.size).isEqualTo(1)
        TrailblazeToolYamlWrapper(
          name = "wait",
          trailblazeTool = LaunchAppTrailblazeTool(
            appId = "com.squareup.cash.beta.debug",
            launchMode = LaunchMode.REINSTALL,
          ),
        )
      }
    }
  }

  @Test
  fun deserializeLaunchAppToolResume() {
    val yaml = """
- tools:
    - launchApp:
        appId: com.squareup.cash.beta.debug
        launchMode: RESUME
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    with(trailItems) {
      assertThat(size).isEqualTo(1)
      with(get(0) as TrailYamlItem.ToolTrailItem) {
        assertThat(tools.size).isEqualTo(1)
        TrailblazeToolYamlWrapper(
          name = "wait",
          trailblazeTool = LaunchAppTrailblazeTool(
            appId = "com.squareup.cash.beta.debug",
            launchMode = LaunchMode.RESUME,
          ),
        )
      }
    }
  }

  @Test
  fun deserializeLaunchAppToolForceRestart() {
    val yaml = """
- tools:
    - launchApp:
        appId: com.squareup.cash.beta.debug
        launchMode: FORCE_RESTART
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    with(trailItems) {
      assertThat(size).isEqualTo(1)
      with(get(0) as TrailYamlItem.ToolTrailItem) {
        assertThat(tools.size).isEqualTo(1)
        TrailblazeToolYamlWrapper(
          name = "wait",
          trailblazeTool = LaunchAppTrailblazeTool(
            appId = "com.squareup.cash.beta.debug",
            launchMode = LaunchMode.FORCE_RESTART,
          ),
        )
      }
    }
  }

  @Test
  fun deserializeTapOnPointTool() {
    val yaml = """
- tools:
    - tapOnPoint:
        x: 100
        y: 200
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    with(trailItems) {
      assertThat(size).isEqualTo(1)
      with(get(0) as TrailYamlItem.ToolTrailItem) {
        assertThat(tools.size).isEqualTo(1)
        TrailblazeToolYamlWrapper(
          name = "tapOnPoint",
          trailblazeTool = TapOnPointTrailblazeTool(
            x = 100,
            y = 200,
          ),
        )
      }
    }
  }

  @Test
  fun deserializeTapOnElementWithTextTool() {
    val yaml = """
- tools:
    - tapOnElementWithText:
        text: Sign Out
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    with(trailItems) {
      assertThat(size).isEqualTo(1)
      with(get(0) as TrailYamlItem.ToolTrailItem) {
        assertThat(tools.size).isEqualTo(1)
        TrailblazeToolYamlWrapper(
          name = "tapOnElementWithText",
          trailblazeTool = TapOnElementWithTextTrailblazeTool(
            text = "Sign Out",
            index = 0,
            id = null,
            enabled = null,
            selected = null,
          ),
        )
      }
    }
  }

  @Test
  fun deserializeTapOnElementWithAccessibilityTextTool() {
    val yaml = """
- tools:
    - tapOnElementWithAccessibilityText:
        accessibilityText: Accounts
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    with(trailItems) {
      assertThat(size).isEqualTo(1)
      with(get(0) as TrailYamlItem.ToolTrailItem) {
        assertThat(tools.size).isEqualTo(1)
        TrailblazeToolYamlWrapper(
          name = "tapOnElementWithAccessibilityText",
          trailblazeTool = TapOnElementWithAccessiblityTextTrailblazeTool(
            accessibilityText = "Accounts",
            index = null,
            id = null,
            enabled = null,
            selected = null,
          ),
        )
      }
    }
  }

  @Test
  fun deserializeLongPressOnElementWithTextTool() {
    val yaml = """
- tools:
    - longPressOnElementWithText:
        text: Sign Out
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    with(trailItems) {
      assertThat(size).isEqualTo(1)
      with(get(0) as TrailYamlItem.ToolTrailItem) {
        assertThat(tools.size).isEqualTo(1)
        TrailblazeToolYamlWrapper(
          name = "tapOnElementWithText",
          trailblazeTool = LongPressOnElementWithTextTrailblazeTool(
            text = "Sign Out",
            index = null,
            id = null,
            enabled = null,
            selected = null,
          ),
        )
      }
    }
  }

  @Test
  fun deserializeLongPressOnElementWithAccessibilityTextTool() {
    val yaml = """
- tools:
    - longPressElementWithAccessibilityText:
        accessibilityText: Accounts
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    with(trailItems) {
      assertThat(size).isEqualTo(1)
      with(get(0) as TrailYamlItem.ToolTrailItem) {
        assertThat(tools.size).isEqualTo(1)
        TrailblazeToolYamlWrapper(
          name = "tapOnElementWithAccessibilityText",
          trailblazeTool = LongPressElementWithAccessibilityTextTrailblazeTool(
            accessibilityText = "Accounts",
            index = null,
            id = null,
            enabled = null,
            selected = null,
          ),
        )
      }
    }
  }
}
