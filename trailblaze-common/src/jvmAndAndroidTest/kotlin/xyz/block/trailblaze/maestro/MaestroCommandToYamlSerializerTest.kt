package xyz.block.trailblaze.maestro

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import maestro.DeviceOrientation
import maestro.KeyCode
import maestro.ScrollDirection
import maestro.SwipeDirection
import maestro.TapRepeat
import maestro.orchestra.AddMediaCommand
import maestro.orchestra.AirplaneValue
import maestro.orchestra.AssertCommand
import maestro.orchestra.AssertConditionCommand
import maestro.orchestra.ClearKeychainCommand
import maestro.orchestra.Command
import maestro.orchestra.Condition
import maestro.orchestra.CopyTextFromCommand
import maestro.orchestra.ElementSelector
import maestro.orchestra.EraseTextCommand
import maestro.orchestra.InputRandomCommand
import maestro.orchestra.InputRandomType
import maestro.orchestra.InputTextCommand
import maestro.orchestra.LaunchAppCommand
import maestro.orchestra.OpenLinkCommand
import maestro.orchestra.PressKeyCommand
import maestro.orchestra.ScrollUntilVisibleCommand
import maestro.orchestra.SetAirplaneModeCommand
import maestro.orchestra.SetLocationCommand
import maestro.orchestra.SetOrientationCommand
import maestro.orchestra.SwipeCommand
import maestro.orchestra.TakeScreenshotCommand
import maestro.orchestra.TapOnElementCommand
import maestro.orchestra.TapOnPointV2Command
import maestro.orchestra.TravelCommand
import maestro.orchestra.yaml.YamlCommandReader
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Maestro doesn't have a Yaml generator, only a parser.
 *
 * These tests serialize commands to yaml, use Maestro's yaml parser and then assert the command is parsed correctly.
 */
class MaestroCommandToYamlSerializerTest {

  @Test
  fun `setOrientation command`() {
    SetOrientationCommand(
      orientation = DeviceOrientation.LANDSCAPE_LEFT,
    ).also { command ->
      convertCommandsToYamlAndParseAndCompare(
        commandToSerialize = command,
        expected = command,
      )
    }
  }

    @Test
    fun `takeScreenshot command`() {
      TakeScreenshotCommand(
        path = "hello",
      ).also { command ->
        convertCommandsToYamlAndParseAndCompare(
          commandToSerialize = command,
          expected = command,
        )
      }
    }

  @Test
  fun `travelCommand with speed`() {
    TravelCommand(
      points = listOf(
        TravelCommand.GeoPoint("37.7749", "-122.4194"),
        TravelCommand.GeoPoint("7.7749", "-22.4194"),
      ),
      speedMPS = 110.0,
    ).also { command ->
      convertCommandsToYamlAndParseAndCompare(
        commandToSerialize = command,
        expected = command,
      )
    }
  }

  @Test
  fun `travelCommand without speed`() {
    TravelCommand(
      points = listOf(
        TravelCommand.GeoPoint("37.7749", "-122.4194"),
        TravelCommand.GeoPoint("7.7749", "-22.4194"),
      ),
    ).also { command ->
      convertCommandsToYamlAndParseAndCompare(
        commandToSerialize = command,
        expected = command.copy(
          speedMPS = 10.0, // Default Value
        ),
      )
    }
  }

  @Test
  fun `swipeCommand relative`() {
    SwipeCommand(
      duration = 1000,
      startRelative = "0%,0%",
      endRelative = "50%,50%",
    ).also { command ->
      convertCommandsToYamlAndParseAndCompare(
        commandToSerialize = command,
        expected = command,
      )
    }
  }

  @Test
  fun `swipeCommand element`() {
    SwipeCommand(
      elementSelector = ElementSelector(
        textRegex = "John Appleseed",
      ),
      direction = SwipeDirection.UP,
    ).also { command ->
      convertCommandsToYamlAndParseAndCompare(
        commandToSerialize = command,
        expected = command,
      )
    }
  }

  @Test
  fun `swipeCommand direction`() {
    SwipeCommand(
      direction = SwipeDirection.LEFT,
    ).also { command ->
      convertCommandsToYamlAndParseAndCompare(
        commandToSerialize = command,
        expected = command,
      )
    }
  }

  @Test
  fun `swipeCommand elementSelector with relative selectors`() {
    SwipeCommand(
      elementSelector = ElementSelector(
        textRegex = "Swipe Element",
        enabled = true,
        below = ElementSelector(
          idRegex = "above_element",
          textRegex = "Above Text",
        ),
        containsChild = ElementSelector(
          textRegex = "Child Text",
          checked = true,
        ),
        containsDescendants = listOf(
          ElementSelector(
            textRegex = "Descendant 1",
          ),
          ElementSelector(
            idRegex = "descendant_2",
            enabled = false,
          ),
        ),
      ),
      direction = SwipeDirection.UP,
    ).also { command ->
      convertCommandsToYamlAndParseAndCompare(
        commandToSerialize = command,
        expected = command,
      )
    }
  }

  @Test
  fun `swipeCommand with elementSelector but no direction uses coordinates`() {
    SwipeCommand(
      elementSelector = ElementSelector(
        textRegex = "Ignored Element",
      ),
      startRelative = "10%,20%",
      endRelative = "90%,80%",
      duration = 500,
    ).also { command ->
      convertCommandsToYamlAndParseAndCompare(
        commandToSerialize = command,
        expected = command.copy(elementSelector = null),
      )
    }
  }

  @Test
  fun tapOnElementCommand() {
    TapOnElementCommand(
      selector = ElementSelector(
        textRegex = "John Appleseed",
        enabled = true,
        focused = true,
      ),
    ).also { command ->
      convertCommandsToYamlAndParseAndCompare(
        commandToSerialize = command,
        expected = command.copy(
          retryIfNoChange = false, // Default Value
          waitUntilVisible = false, // Default Value
          longPress = false, // Default Value
        ),
      )
    }
  }

  @Test
  fun tapOnElementCommandWithSelectors() {
    TapOnElementCommand(
      selector = ElementSelector(
        textRegex = "John Appleseed",
        enabled = true,
        focused = true,
        childOf = ElementSelector(
          idRegex = "some_id",
          textRegex = "some_child_text",
          enabled = true,
        ),
        containsChild = ElementSelector(
          idRegex = "some_id",
          textRegex = "some_text",
        ),
        above = ElementSelector(
          idRegex = "some_id",
          selected = true,
          textRegex = "some_above_text",
        ),
        below = ElementSelector(
          idRegex = "some_id",
          textRegex = "some_below_text",
          enabled = true,
        ),
        leftOf = ElementSelector(
          idRegex = "some_id",
          textRegex = "some_left_text",
          leftOf = ElementSelector(
            idRegex = "some_id",
            textRegex = "some_left_text",
            enabled = false,
          ),
        ),
        rightOf = ElementSelector(
          idRegex = "some_id",
          textRegex = "some_right_text",
          enabled = false,
        ),
        containsDescendants = listOf(
          ElementSelector(
            textRegex = "some_text1",
          ),
          ElementSelector(
            textRegex = "some_text2",
            checked = true,
            rightOf = ElementSelector(
              idRegex = "some_id",
              textRegex = "some_left_text",
              leftOf = ElementSelector(
                idRegex = "some_id",
                textRegex = "some_left_text",
                enabled = false,
              ),
            ),
          ),
        ),
      ),
    ).also { command ->
      convertCommandsToYamlAndParseAndCompare(
        commandToSerialize = command,
        expected = command.copy(
          retryIfNoChange = false, // Default Value
          waitUntilVisible = false, // Default Value
          longPress = false, // Default Value
        ),
      )
    }
  }

  @Test
  fun `tapOnElementCommand retryIfNoChange=false`() {
    TapOnElementCommand(
      selector = ElementSelector(
        idRegex = "some_id",
      ),
      retryIfNoChange = false,
      waitUntilVisible = false,
    ).also { command ->
      convertCommandsToYamlAndParseAndCompare(
        commandToSerialize = command,
        expected = command.copy(
          longPress = false, // Default Value
        ),
      )
    }
  }

  @Test
  fun `tapOnElementCommand longPress`() {
    TapOnElementCommand(
      selector = ElementSelector(
        textRegex = "John Appleseed",
      ),
      longPress = true,
    ).also { command ->
      convertCommandsToYamlAndParseAndCompare(
        commandToSerialize = command,
        expected = command.copy(
          retryIfNoChange = false, // Default Value
          waitUntilVisible = false, // Default Value
        ),
      )
    }
  }

  @Test
  fun tapOnPointV2Command() {
    TapOnPointV2Command(
      point = "50%,50%",
      longPress = true,
      repeat = TapRepeat(1, 1000),
      waitToSettleTimeoutMs = 1000,
    ).also { command ->
      convertCommandsToYamlAndParseAndCompare(
        commandToSerialize = command,
        expected = command.copy(
          retryIfNoChange = false, // Default Value
        ),
      )
    }
  }

  @Test
  fun `setAirplaneModeCommand enabled`() {
    SetAirplaneModeCommand(
      value = AirplaneValue.Enable,
    ).also { command ->
      convertCommandsToYamlAndParseAndCompare(
        commandToSerialize = command,
        expected = command,
      )
    }
  }

  @Test
  fun `setAirplaneModeCommand disabled`() {
    SetAirplaneModeCommand(
      value = AirplaneValue.Disable,
    ).also { command ->
      convertCommandsToYamlAndParseAndCompare(
        commandToSerialize = command,
        expected = command,
      )
    }
  }

  @Test
  fun scrollUntilVisibleCommand() {
    ScrollUntilVisibleCommand(
      selector = ElementSelector(
        textRegex = "Some Text",
      ),
      direction = ScrollDirection.DOWN,
      visibilityPercentage = 50,
      centerElement = false,
    ).also { command ->
      convertCommandsToYamlAndParseAndCompare(
        commandToSerialize = command,
        expected = command,
      )
    }
  }

  @Test
  fun `scrollUntilVisibleCommand with relative selectors`() {
    ScrollUntilVisibleCommand(
      selector = ElementSelector(
        textRegex = "Target Element",
        enabled = true,
        childOf = ElementSelector(
          idRegex = "parent_container",
          enabled = true,
        ),
        above = ElementSelector(
          textRegex = "Element Below",
          selected = true,
        ),
        containsDescendants = listOf(
          ElementSelector(
            textRegex = "Child Text",
          ),
        ),
      ),
      direction = ScrollDirection.DOWN,
      visibilityPercentage = 75,
      centerElement = true,
    ).also { command ->
      convertCommandsToYamlAndParseAndCompare(
        commandToSerialize = command,
        expected = command,
      )
    }
  }

  @Test
  fun pressKeyCommand() {
    PressKeyCommand(
      code = KeyCode.ENTER,
    ).also { command ->
      convertCommandsToYamlAndParseAndCompare(
        commandToSerialize = command,
        expected = command,
      )
    }
  }

  @Test
  fun `inputRandomCommand TEXT`() {
    InputRandomCommand(
      inputType = InputRandomType.TEXT,
      length = 1,
    ).also { command ->
      convertCommandsToYamlAndParseAndCompare(
        commandToSerialize = command,
        expected = command,
      )
    }
  }

  @Test
  fun `inputRandomCommand length only`() {
    InputRandomCommand(
      length = 3,
    ).also { command ->
      convertCommandsToYamlAndParseAndCompare(
        commandToSerialize = command,
        expected = command,
      )
    }
  }

  @Test
  fun `inputRandomCommand TEXT_PERSON_NAME`() {
    InputRandomCommand(
      inputType = InputRandomType.TEXT_PERSON_NAME,
    ).also { command ->
      convertCommandsToYamlAndParseAndCompare(
        commandToSerialize = command,
        expected = command,
      )
    }
  }

  @Test
  fun `inputRandomCommand TEXT_EMAIL_ADDRESS`() {
    InputRandomCommand(
      inputType = InputRandomType.TEXT_EMAIL_ADDRESS,
    ).also { command ->
      convertCommandsToYamlAndParseAndCompare(
        commandToSerialize = command,
        expected = command,
      )
    }
  }

  @Test
  fun `inputRandomCommand NUMBER`() {
    InputRandomCommand(
      inputType = InputRandomType.NUMBER,
    ).also { command ->
      convertCommandsToYamlAndParseAndCompare(
        commandToSerialize = command,
        expected = command,
      )
    }
  }

  @Test
  fun `inputTextCommand simple`() {
    InputTextCommand(
      text = "user@example.com",
    ).also { command ->
      convertCommandsToYamlAndParseAndCompare(
        commandToSerialize = command,
        expected = command,
      )
    }
  }

  @Test
  fun `inputTextCommand number`() {
    InputTextCommand(
      text = "5105105105105100",
    ).also { command ->
      convertCommandsToYamlAndParseAndCompare(
        commandToSerialize = command,
        expected = command,
      )
    }
  }

  @Test
  fun `inputTextCommand lots of spaces`() {
    InputTextCommand(
      text = "a b c d e f g",
    ).also { command ->
      convertCommandsToYamlAndParseAndCompare(
        commandToSerialize = command,
        expected = command,
      )
    }
  }

  @Test
  fun `inputTextCommand multiline`() {
    InputTextCommand(
      text = "Line 1\nLine 2\nLine 3\n",
    ).also { command ->
      convertCommandsToYamlAndParseAndCompare(
        commandToSerialize = command,
        expected = command,
      )
    }
  }

  @Test
  fun addMediaCommand() {
    // Example file for the test with a full path and the file needs to exist.
    // This would typically be a screenshot or video file.
    val filePath = File.createTempFile("media", ".txt").canonicalPath
    AddMediaCommand(
      mediaPaths = listOf(
        filePath,
      ),
    ).also { command ->
      convertCommandsToYamlAndParseAndCompare(
        commandToSerialize = command,
        expected = command,
      )
    }
  }

  @Test
  fun `copyTextFromCommand with relative selectors`() {
    CopyTextFromCommand(
      selector = ElementSelector(
        textRegex = "Copy This Text",
        focused = true,
        rightOf = ElementSelector(
          idRegex = "left_element",
          textRegex = "Left Element",
        ),
        containsChild = ElementSelector(
          textRegex = "Has Child",
          checked = true,
        ),
        containsDescendants = listOf(
          ElementSelector(
            textRegex = "Descendant Text",
            enabled = true,
          ),
        ),
      ),
    ).also { command ->
      convertCommandsToYamlAndParseAndCompare(
        commandToSerialize = command,
        expected = command,
      )
    }
  }

  @Test
  fun assertCommand() {
    AssertCommand(
      visible = ElementSelector(
        textRegex = "Some Text",
        index = "0",
        enabled = true,
        checked = true,
      ),
    ).also { command ->
      convertCommandsToYamlAndParseAndCompare(
        commandToSerialize = command,
        expected = AssertConditionCommand(
          condition = Condition(
            visible = command.visible,
            notVisible = command.notVisible,
          ),
        ),
      )
    }
  }

  @Test
  fun `assertCommand with relative selectors`() {
    AssertCommand(
      visible = ElementSelector(
        textRegex = "Assert This",
        index = "1",
        enabled = true,
        above = ElementSelector(
          idRegex = "below_element",
          textRegex = "Below Text",
          containsChild = ElementSelector(
            textRegex = "Nested Child",
            checked = true,
          ),
        ),
        containsDescendants = listOf(
          ElementSelector(
            textRegex = "List Item 1",
          ),
          ElementSelector(
            textRegex = "List Item 2",
            selected = true,
          ),
        ),
      ),
    ).also { command ->
      convertCommandsToYamlAndParseAndCompare(
        commandToSerialize = command,
        expected = AssertConditionCommand(
          condition = Condition(
            visible = command.visible,
            notVisible = command.notVisible,
          ),
        ),
      )
    }
  }

  @Test
  fun assertConditionCommand() {
    AssertConditionCommand(
      condition = Condition(
        notVisible = ElementSelector(
          idRegex = "the_id",
          selected = true,
        ),
      ),
    ).also { command ->
      convertCommandsToYamlAndParseAndCompare(
        commandToSerialize = command,
        expected = command,
      )
    }
  }

  @Test
  fun `assertConditionCommand with complex relative selectors`() {
    AssertConditionCommand(
      condition = Condition(
        visible = ElementSelector(
          textRegex = "Visible Element",
          enabled = true,
          leftOf = ElementSelector(
            idRegex = "right_element",
            selected = true,
            above = ElementSelector(
              textRegex = "Nested Above",
              checked = false,
            ),
          ),
          containsDescendants = listOf(
            ElementSelector(
              textRegex = "First Descendant",
              enabled = true,
            ),
            ElementSelector(
              idRegex = "second_descendant",
              focused = true,
              rightOf = ElementSelector(
                textRegex = "Left of Second",
              ),
            ),
          ),
        ),
      ),
    ).also { command ->
      convertCommandsToYamlAndParseAndCompare(
        commandToSerialize = command,
        expected = command,
      )
    }
  }

  @Test
  fun `assertConditionCommand notVisible with relative selectors`() {
    AssertConditionCommand(
      condition = Condition(
        notVisible = ElementSelector(
          idRegex = "hidden_element",
          enabled = false,
          below = ElementSelector(
            textRegex = "Above Element",
            selected = true,
          ),
          childOf = ElementSelector(
            idRegex = "parent_container",
            containsChild = ElementSelector(
              textRegex = "Sibling Element",
            ),
          ),
        ),
      ),
    ).also { command ->
      convertCommandsToYamlAndParseAndCompare(
        commandToSerialize = command,
        expected = command,
      )
    }
  }

  @Test
  fun setLocationCommand() {
    SetLocationCommand(
      latitude = "37.7749",
      longitude = "-122.4194",
    ).also { command ->
      convertCommandsToYamlAndParseAndCompare(
        commandToSerialize = command,
        expected = command,
      )
    }
  }

  @Test
  fun openLinkCommand() {
    OpenLinkCommand(
      link = "https://www.google.com",
      autoVerify = true,
      browser = true,
    ).also { command ->
      convertCommandsToYamlAndParseAndCompare(
        commandToSerialize = command,
        expected = command,
      )
    }
  }

  @Test
  fun launchAppCommand() {
    LaunchAppCommand(
      appId = "com.google.android.deskclock",
      launchArguments = mapOf(
        "foo" to "This is a string",
        "isFooEnabled" to false,
        "fooValue" to 3.24,
        "fooInt" to 3,
      ),
      permissions = mapOf(
        "all" to "allow",
      ),
    ).also { command ->
      convertCommandsToYamlAndParseAndCompare(
        commandToSerialize = command,
        expected = command,
      )
    }
  }

  @Test
  fun clearKeychainCommand() {
    ClearKeychainCommand().also { command ->
      convertCommandsToYamlAndParseAndCompare(
        commandToSerialize = command,
        expected = command,
      )
    }
  }

  @Test
  fun eraseTextCommand() {
    EraseTextCommand(
      charactersToErase = 12,
    ).also { command ->
      convertCommandsToYamlAndParseAndCompare(
        commandToSerialize = command,
        expected = command,
      )
    }
  }

  @Test
  fun `tapOnElementCommand text`() {
    TapOnElementCommand(
      ElementSelector(
        textRegex = "John Appleseed",
      ),
    ).also { command ->
      convertCommandsToYamlAndParseAndCompare(
        commandToSerialize = command,
        expected = command.copy(
          retryIfNoChange = false, // Default Value
          waitUntilVisible = false, // Default Value
          longPress = false, // Default Value
        ),
      )
    }
  }

  @Test
  fun `tapOnElementCommand id`() {
    TapOnElementCommand(
      ElementSelector(
        idRegex = "the_id",
        index = "0",
        size = ElementSelector.SizeSelector(
          width = 100,
          height = 100,
          tolerance = 10,
        ),
        enabled = true,
        checked = true,
        focused = true,
        selected = true,
      ),
    ).also { command ->
      convertCommandsToYamlAndParseAndCompare(
        commandToSerialize = command,
        expected = command.copy(
          retryIfNoChange = false, // Default Value
          waitUntilVisible = false, // Default Value
          longPress = false, // Default Value
        ),
      )
    }
  }

  companion object {
    val gsonInstance: Gson = GsonBuilder().setPrettyPrinting().create()
    private fun convertCommandsToYamlAndParseAndCompare(commandToSerialize: Command, expected: Command) {
      val commands = listOf(commandToSerialize)
      val yamlString = MaestroYamlSerializer.toYaml(commands)
      println(yamlString)

      val flowFile = File.createTempFile("flow", ".yaml").apply { writeText(yamlString) }
      val parsedCommands = YamlCommandReader.readCommands(flowFile.toPath())
      println(parsedCommands.joinToString("\n"))

      val parsedBasicCommands = parsedCommands
        // Filter out the "Apply Configuration" commands since they are not part of the original commands
        .filterNot { it.applyConfigurationCommand != null }
        .map { it.asCommand() }

      assertEquals(
        gsonInstance.toJson(expected),
        gsonInstance.toJson(parsedBasicCommands.first()),
      )
    }
  }
}
