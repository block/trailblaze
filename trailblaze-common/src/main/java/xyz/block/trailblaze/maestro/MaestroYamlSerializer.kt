package xyz.block.trailblaze.maestro

import kotlinx.serialization.Serializable
import maestro.orchestra.AddMediaCommand
import maestro.orchestra.AirplaneValue
import maestro.orchestra.ApplyConfigurationCommand
import maestro.orchestra.AssertCommand
import maestro.orchestra.AssertConditionCommand
import maestro.orchestra.BackPressCommand
import maestro.orchestra.ClearKeychainCommand
import maestro.orchestra.ClearStateCommand
import maestro.orchestra.Command
import maestro.orchestra.Condition
import maestro.orchestra.CopyTextFromCommand
import maestro.orchestra.DefineVariablesCommand
import maestro.orchestra.ElementSelector
import maestro.orchestra.EraseTextCommand
import maestro.orchestra.EvalScriptCommand
import maestro.orchestra.HideKeyboardCommand
import maestro.orchestra.InputRandomCommand
import maestro.orchestra.InputRandomType
import maestro.orchestra.InputTextCommand
import maestro.orchestra.KillAppCommand
import maestro.orchestra.LaunchAppCommand
import maestro.orchestra.OpenLinkCommand
import maestro.orchestra.PasteTextCommand
import maestro.orchestra.PressKeyCommand
import maestro.orchestra.RunFlowCommand
import maestro.orchestra.RunScriptCommand
import maestro.orchestra.ScrollCommand
import maestro.orchestra.ScrollUntilVisibleCommand
import maestro.orchestra.SetAirplaneModeCommand
import maestro.orchestra.SetLocationCommand
import maestro.orchestra.SetOrientationCommand
import maestro.orchestra.StopAppCommand
import maestro.orchestra.SwipeCommand
import maestro.orchestra.TakeScreenshotCommand
import maestro.orchestra.TapOnElementCommand
import maestro.orchestra.TapOnPointV2Command
import maestro.orchestra.ToggleAirplaneModeCommand
import maestro.orchestra.TravelCommand
import maestro.orchestra.WaitForAnimationToEndCommand

object MaestroYamlSerializer {

  private fun indent(count: Int = 1, string: String): String = string.prependIndent(" ".repeat(count * 4))

  @Serializable
  data class MaestroCommandYamlNode(
    val type: String,
    val stringProps: Map<String, String> = emptyMap(),
    val mapProps: Map<String, Map<String, String>> = emptyMap(),
    val stringList: List<String> = emptyList(),
    val mapToStringList: Map<String, List<String>> = emptyMap(),
    val nestedSelectorProps: Map<String, ElementSelectorData> = emptyMap(),
    val nestedListSelectorProps: Map<String, List<ElementSelectorData>> = emptyMap(),
  )

  @Serializable
  data class ElementSelectorData(
    val stringProps: Map<String, String> = emptyMap(),
    val nestedSelectorProps: Map<String, ElementSelectorData> = emptyMap(),
    val nestedListSelectorProps: Map<String, List<ElementSelectorData>> = emptyMap(),
  )

  private fun extractStringProperties(obj: Any): Map<String, String> {
    return when (obj) {
      is ElementSelector -> {
        val props = mutableMapOf<String, String>()
        // UNHANDLED
        //  point: 50%, 50%  # (optional) Relative position on screen. "50%, 50%" is the middle of screen
        //  point: 50, 75    # (optional) Exact coordinates on screen. x:50 y:50, in pixels

        // UNHANDLED map properties
        // val below: ElementSelector? = null,
        // val above: ElementSelector? = null,
        // val leftOf: ElementSelector? = null,
        // val rightOf: ElementSelector? = null,
        // val containsChild: ElementSelector? = null,
        // val containsDescendants: List<ElementSelector>? = null,
        // val traits: List<ElementTrait>? = null,
        // val childOf: ElementSelector? = null
        obj.textRegex?.let { props["text"] = it.wrappedInQuotes() }
        obj.idRegex?.let { props["id"] = it.wrappedInQuotes() }
        obj.index?.let { props["index"] = it }
        obj.size?.let {
          props["width"] = it.width.toString()
          props["height"] = it.height.toString()
          props["tolerance"] = it.tolerance.toString()
        }
        obj.enabled?.let { props["enabled"] = it.toString() }
        obj.checked?.let { props["checked"] = it.toString() }
        obj.focused?.let { props["focused"] = it.toString() }
        obj.selected?.let { props["selected"] = it.toString() }
        return props
      }
      else -> mapOf()
    }
  }

  private fun extractNestedSelectorProperties(obj: Any): Map<String, ElementSelectorData> = when (obj) {
    is ElementSelector -> {
      mutableMapOf<String, ElementSelectorData>().apply {
        obj.below?.let { put("below", elementSelectorToData(it)) }
        obj.above?.let { put("above", elementSelectorToData(it)) }
        obj.leftOf?.let { put("leftOf", elementSelectorToData(it)) }
        obj.rightOf?.let { put("rightOf", elementSelectorToData(it)) }
        obj.containsChild?.let { put("containsChild", elementSelectorToData(it)) }
        obj.childOf?.let { put("childOf", elementSelectorToData(it)) }
      }
    }

    else -> mapOf()
  }

  private fun extractNestedListSelectorProperties(obj: Any): Map<String, List<ElementSelectorData>> = when (obj) {
    is ElementSelector -> {
      mutableMapOf<String, List<ElementSelectorData>>().apply {
        obj.containsDescendants?.let { descendants ->
          put("containsDescendants", descendants.map { elementSelectorToData(it) })
        }
      }
    }

    else -> mapOf()
  }

  private fun elementSelectorToData(selector: ElementSelector): ElementSelectorData = ElementSelectorData(
    stringProps = extractStringProperties(selector),
    nestedSelectorProps = extractNestedSelectorProperties(selector),
    nestedListSelectorProps = extractNestedListSelectorProperties(selector),
  )

  private fun createTapOnCommand(
    command: Command,
  ): MaestroCommandYamlNode {
    val longPress = when (command) {
      is TapOnElementCommand -> command.longPress
      is TapOnPointV2Command -> command.longPress
      else -> null
    }

    return MaestroCommandYamlNode(
      type = if (longPress == true) {
        "longPressOn"
      } else {
        "tapOn"
      },
      stringProps = mutableMapOf<String, String>().apply {
        when (command) {
          is TapOnElementCommand -> {
            putAll(extractStringProperties(command.selector))
          }

          is TapOnPointV2Command -> {
            put("point", command.point)
          }

          else -> {
            error("Unsupported command: ${command::class.simpleName}")
          }
        }

        when (command) {
          is TapOnElementCommand -> command.waitToSettleTimeoutMs
          is TapOnPointV2Command -> command.waitToSettleTimeoutMs
          else -> null
        }?.let { put("waitToSettleTimeoutMs", it.toString()) }

        when (command) {
          is TapOnElementCommand -> command.retryIfNoChange
          is TapOnPointV2Command -> command.retryIfNoChange
          else -> null
        }?.let { put("retryTapIfNoChange", it.toString()) }

        when (command) {
          is TapOnElementCommand -> command.repeat
          is TapOnPointV2Command -> command.repeat
          else -> null
        }.also { tapRepeat ->
          tapRepeat?.repeat?.let { put("repeat", it.toString()) }
          tapRepeat?.delay?.let { put("delay", it.toString()) }
        }
      },
      nestedSelectorProps = when (command) {
        is TapOnElementCommand -> extractNestedSelectorProperties(command.selector)
        else -> emptyMap()
      },
      nestedListSelectorProps = when (command) {
        is TapOnElementCommand -> extractNestedListSelectorProperties(command.selector)
        else -> emptyMap()
      },
    )
  }

  private fun toPrimitive(command: Command): MaestroCommandYamlNode? = when (command) {
    // COMPLETE
    is TapOnElementCommand,
    is TapOnPointV2Command,
    -> {
      createTapOnCommand(command)
    }
    // COMPLETE
    is LaunchAppCommand -> {
      MaestroCommandYamlNode(
        type = "launchApp",
        stringProps = mutableMapOf<String, String>(
          "appId" to command.appId,
        ).apply {
          command.stopApp?.let { put("stopApp", it.toString()) }
          command.clearKeychain?.let { put("clearKeychain", it.toString()) }
          command.clearState?.let { put("clearState", it.toString()) }
        },
        mapProps = mutableMapOf<String, Map<String, String>>().apply {
          command.permissions?.let { put("permissions", it) }

          command.launchArguments?.let {
            put("arguments", it.mapValues { it.value.toString() })
          }
        },
      )
    }
    // COMPLETE
    is InputTextCommand -> {
      MaestroCommandYamlNode(
        type = "inputText",
        stringProps = mutableMapOf<String, String>(
          "text" to command.text.wrappedInQuotes(),
        ),
      )
    }
    // COMPLETE
    is ClearStateCommand -> {
      MaestroCommandYamlNode(
        type = "clearState",
        stringProps = mutableMapOf<String, String>(
          "appId" to command.appId,
        ),
      )
    }
    // COMPLETE
    is StopAppCommand -> {
      MaestroCommandYamlNode(
        type = "stopApp",
        stringProps = mutableMapOf<String, String>(
          "appId" to command.appId,
        ),
      )
    }
    // COMPLETE
    is ScrollCommand -> {
      MaestroCommandYamlNode(
        type = "scroll",
      )
    }
    // COMPLETE
    is ToggleAirplaneModeCommand -> {
      MaestroCommandYamlNode(
        type = "toggleAirplaneMode",
      )
    }
    // COMPLETE
    is WaitForAnimationToEndCommand -> {
      MaestroCommandYamlNode(
        type = "waitForAnimationToEnd",
        stringProps = mutableMapOf<String, String>().apply {
          command.timeout?.let { put("timeout", it.toString()) }
        },
      )
    }
    // COMPLETE
    is BackPressCommand -> {
      MaestroCommandYamlNode(
        type = "back",
      )
    }
    // COMPLETE
    is CopyTextFromCommand -> {
      MaestroCommandYamlNode(
        type = "copyTextFrom",
        stringProps = mutableMapOf<String, String>().apply {
          putAll(extractStringProperties(command.selector))
        },
        nestedSelectorProps = extractNestedSelectorProperties(command.selector),
        nestedListSelectorProps = extractNestedListSelectorProperties(command.selector),
      )
    }
    // COMPLETE
    is EraseTextCommand -> {
      MaestroCommandYamlNode(
        type = "eraseText",
        stringProps = mutableMapOf<String, String>().apply {
          command.charactersToErase?.let { put("charactersToErase", it.toString()) }
        },
      )
    }
    // COMPLETE
    is SwipeCommand -> {
      MaestroCommandYamlNode(
        type = "swipe",
        stringProps = mutableMapOf<String, String>().apply {
          // Default is 400L, but it's not public, so we can just have it serialized
          put("duration", command.duration.toString())

          command.waitToSettleTimeoutMs?.let { put("waitToSettleTimeoutMs", it.toString()) }

          // Swipe command takes either:
          // 	1. direction: Direction based swipe with: "RIGHT", "LEFT", "UP", or "DOWN" or
          // 	2. start and end: Coordinates based swipe with: "start" and "end" coordinates
          // 	3. direction and element to swipe directionally on element

          if (command.direction != null) {
            command.direction?.let { put("direction", it.name) }
          } else if (command.startRelative != null || command.endRelative != null) {
            command.startRelative?.let { put("start", it) }
            command.endRelative?.let { put("end", it) }
          } else {
            command.startPoint?.let { put("start", "${it.x},${it.y}") }
            command.endPoint?.let { put("end", "${it.x},${it.y}") }
          }
        },
        nestedSelectorProps = mutableMapOf<String, ElementSelectorData>().apply {
          // Only include 'from' element when both elementSelector and direction are present
          if (command.elementSelector != null && command.direction != null) {
            put("from", elementSelectorToData(command.elementSelector!!))
          }
        },
        nestedListSelectorProps = emptyMap(),
      )
    }
    // COMPLETE
    is HideKeyboardCommand -> {
      MaestroCommandYamlNode(
        type = "hideKeyboard",
      )
    }
    // COMPLETE
    is KillAppCommand -> {
      MaestroCommandYamlNode(
        type = "killApp",
        stringProps = mutableMapOf<String, String>(
          "appId" to command.appId,
        ),
      )
    }
    // COMPLETE
    is OpenLinkCommand -> {
      MaestroCommandYamlNode(
        type = "openLink",
        stringProps = mutableMapOf<String, String>(
          "link" to command.link,
          "autoVerify" to command.autoVerify.toString(),
          "browser" to command.browser.toString(),
        ),
      )
    }
    // COMPLETE
    is PasteTextCommand -> {
      MaestroCommandYamlNode(
        type = "pasteText",
      )
    }
    // COMPLETE
    is SetLocationCommand -> {
      MaestroCommandYamlNode(
        type = "setLocation",
        stringProps = mutableMapOf<String, String>(
          "latitude" to command.latitude,
          "longitude" to command.longitude,
        ),
      )
    }
    // COMPLETE
    is AssertConditionCommand -> {
      conditionToMaestroCommand(
        condition = command.condition,
        timeout = command.timeoutMs(),
      )
    }
    // COMPLETE
    is AssertCommand -> {
      conditionToMaestroCommand(
        condition = Condition(
          visible = command.visible,
          notVisible = command.notVisible,
        ),
        timeout = command.timeout,
      )
    }
    // COMPLETE
    is AddMediaCommand -> {
      MaestroCommandYamlNode(
        type = "addMedia",
        stringList = command.mediaPaths,
      )
    }
    // COMPLETE
    is ClearKeychainCommand -> {
      MaestroCommandYamlNode(
        type = "clearKeychain",
      )
    }
    // COMPLETE
    is SetAirplaneModeCommand -> {
      MaestroCommandYamlNode(
        type = "setAirplaneMode",
        stringProps = mutableMapOf<String, String>(
          "value" to when (command.value) {
            AirplaneValue.Enable -> "enabled"
            AirplaneValue.Disable -> "disabled"
          },
        ),
      )
    }
    // COMPLETED
    is InputRandomCommand -> {
      MaestroCommandYamlNode(
        type = when (command.inputType) {
          InputRandomType.NUMBER -> "inputRandomNumber"
          InputRandomType.TEXT_EMAIL_ADDRESS -> "inputRandomEmail"
          InputRandomType.TEXT_PERSON_NAME -> "inputRandomPersonName"
          InputRandomType.TEXT_CITY_NAME -> "inputRandomCityName"
          InputRandomType.TEXT_COUNTRY_NAME -> "inputRandomCountryName"
          InputRandomType.TEXT_COLOR -> "inputRandomColorName"
          InputRandomType.TEXT,
          null,
          -> "inputRandomText"
        },
        stringProps = mutableMapOf<String, String>().apply {
          when (command.inputType) {
            InputRandomType.TEXT_CITY_NAME,
            InputRandomType.TEXT_COUNTRY_NAME,
            InputRandomType.TEXT_COLOR,
            InputRandomType.TEXT_EMAIL_ADDRESS,
            InputRandomType.TEXT_PERSON_NAME,
            -> {
              // Nothing
            }

            InputRandomType.NUMBER,
            InputRandomType.TEXT,
            null,
            -> command.length?.let { put("length", it.toString()) }
          }
        },
      )
    }
    // COMPLETED
    is PressKeyCommand -> {
      MaestroCommandYamlNode(
        type = "pressKey",
        stringProps = mutableMapOf<String, String>(
          "key" to command.code.description,
        ),
      )
    }
    // COMPLETED
    // NOT SUPPORTED (Requires JS)
    // - speed: 40 # 0-100 (optional, default: 40) Scroll speed. Higher values scroll faster.
    is ScrollUntilVisibleCommand -> {
      MaestroCommandYamlNode(
        type = "scrollUntilVisible",
        stringProps = mutableMapOf<String, String>(
          "direction" to command.direction.name,
        ).apply {
          if (command.timeout != ScrollUntilVisibleCommand.DEFAULT_TIMEOUT_IN_MILLIS) {
            put("timeout", command.timeout.toString())
          }
          if (command.centerElement != ScrollUntilVisibleCommand.DEFAULT_CENTER_ELEMENT) {
            put("centerElement", command.centerElement.toString())
          }
          if (command.visibilityPercentage != ScrollUntilVisibleCommand.DEFAULT_ELEMENT_VISIBILITY_PERCENTAGE) {
            put("visibilityPercentage", command.visibilityPercentage.toString())
          }
        },
        nestedSelectorProps = mutableMapOf<String, ElementSelectorData>().apply {
          // Put the element selector under "element" key as per Maestro documentation
          put("element", elementSelectorToData(command.selector))
        },
        nestedListSelectorProps = emptyMap(),
      )
    }

    // WILL NOT BE SUPPORTED
    is EvalScriptCommand,
    is RunScriptCommand,
    -> {
      error("${command::class.simpleName} will not be not supported")
    }
    // https://docs.maestro.dev/api-reference/commands/travel
    is TravelCommand -> {
      MaestroCommandYamlNode(
        type = "travel",
        stringProps = mutableMapOf<String, String>().apply {
          val speed = command.speedMPS ?: 10 // Speed is "nullable" but actually required, providing a default value
          put("speed", speed.toString())
        },
        mapToStringList = mutableMapOf<String, List<String>>().apply {
          put("points", command.points.map { "${it.latitude},${it.longitude}" })
        },
      )
    }
    // https://docs.maestro.dev/api-reference/commands/setorientation
    is SetOrientationCommand -> {
      MaestroCommandYamlNode(
        type = "setOrientation",
        stringProps = mutableMapOf<String, String>().apply {
          put("orientation", command.orientation.name)
        },
      )
    }

    // https://docs.maestro.dev/api-reference/commands/takescreenshot
    is TakeScreenshotCommand -> {
      MaestroCommandYamlNode(
        type = "takeScreenshot",
        stringProps = mutableMapOf<String, String>().apply {
          put("path", command.path)
        },
      )
    }

    is ApplyConfigurationCommand -> {
      println("ApplyConfigurationCommand is not handled currently")
      null
    }

    is DefineVariablesCommand -> {
      println("DefineVariablesCommand is not handled currently")
      null
    }

    is RunFlowCommand -> {
      println("RunFlowCommand is not handled currently - skipping flow reference in report")
      null
    }

    else -> error("Unsupported command: ${command::class.simpleName}")
  }.let { node ->
    node?.copy(
      stringProps = node.stringProps.toMutableMap().apply {
        command.label?.let { label -> put("label", label) }
      },
    )
  }

  // UNSUPPORTED FIELDS
  // val platform: Platform? = null,
  // val scriptCondition: String? = null,
  private fun conditionToMaestroCommand(
    condition: Condition,
    timeout: Long?,
  ): MaestroCommandYamlNode = if (condition.visible != null) {
    MaestroCommandYamlNode(
      type = "assertVisible",
      stringProps = mutableMapOf<String, String>().apply {
        putAll(extractStringProperties(condition.visible!!))
        timeout?.let { put("timeout", it.toString()) }
      },
      nestedSelectorProps = extractNestedSelectorProperties(condition.visible!!),
      nestedListSelectorProps = extractNestedListSelectorProperties(condition.visible!!),
    )
  } else if (condition.notVisible != null) {
    MaestroCommandYamlNode(
      type = "assertNotVisible",
      stringProps = mutableMapOf<String, String>().apply {
        putAll(extractStringProperties(condition.notVisible!!))
        timeout?.let { put("timeout", it.toString()) }
      },
      nestedSelectorProps = extractNestedSelectorProperties(condition.notVisible!!),
      nestedListSelectorProps = extractNestedListSelectorProperties(condition.notVisible!!),
    )
  } else if (condition.scriptCondition != null) {
    MaestroCommandYamlNode(
      type = "assertScript",
      stringProps = mutableMapOf<String, String>().apply {
        put("script", condition.scriptCondition!!.wrappedInQuotes())
        timeout?.let { put("timeout", it.toString()) }
      },
    )
  } else {
    error("Unsupported state of Condition $condition $timeout")
  }

  private fun getSingleOrMultilineValue(value: String): String {
    val lines = value.lines()
    return if (lines.size == 1) {
      value
    } else {
      "|\n" + lines.joinToString("\n") { line -> indent(1, line) }
    }
  }

  private fun renderElementSelectorDataAsListItem(
    selectorData: ElementSelectorData,
    tabs: Int,
    yamlStringBuilder: StringBuilder,
  ) {
    yamlStringBuilder.appendLine(indent(tabs, "-"))
    renderElementSelectorData(selectorData, tabs + 1, yamlStringBuilder)
  }

  private fun renderElementSelectorData(
    selectorData: ElementSelectorData,
    tabs: Int,
    yamlStringBuilder: StringBuilder,
  ) {
    selectorData.stringProps.forEach { (key, value) ->
      yamlStringBuilder.appendLine(indent(tabs, "$key: $value"))
    }
    selectorData.nestedSelectorProps.forEach { (key, value) ->
      yamlStringBuilder.appendLine(indent(tabs, "$key:"))
      renderElementSelectorData(value, tabs + 1, yamlStringBuilder)
    }
    selectorData.nestedListSelectorProps.forEach { (key, values) ->
      yamlStringBuilder.appendLine(indent(tabs, "$key:"))
      values.forEach { value ->
        yamlStringBuilder.appendLine(indent(tabs + 1, "-"))
        renderElementSelectorData(value, tabs + 2, yamlStringBuilder)
      }
    }
  }

  fun toYaml(
    commands: List<Command>,
    includeConfiguration: Boolean = true,
    appId: String? = null,
    prompt: String? = null,
  ): String {
    val appId = appId ?: commands.firstNotNullOfOrNull {
      when (it) {
        is LaunchAppCommand -> it.appId
        else -> null
      }
    } ?: "trailblaze"

    val entries: List<MaestroCommandYamlNode> = commands
      .mapNotNull { command -> toPrimitive(command) }

    val yamlString = buildString {
      if (includeConfiguration) {
        appendLine("appId: $appId")
        prompt?.let {
          appendLine("name: ${getSingleOrMultilineValue(it)}")
        }
        appendLine("---")
      }
      entries.forEach { commandNode: MaestroCommandYamlNode ->
        var tabs = 0
        if (commandNode.stringProps.isNotEmpty() || commandNode.stringList.isNotEmpty() || commandNode.mapProps.isNotEmpty() || commandNode.nestedSelectorProps.isNotEmpty() || commandNode.nestedListSelectorProps.isNotEmpty()) {
          appendLine(indent(tabs, "- ${commandNode.type}:"))
        } else {
          appendLine(indent(tabs, "- ${commandNode.type}"))
        }
        tabs++
        commandNode.mapToStringList.forEach { (key, valueStringList: List<String>) ->
          appendLine(indent(tabs, "$key:"))
          tabs++
          valueStringList.forEach { value ->
            appendLine(indent(tabs, "- $value"))
          }
          tabs--
        }
        commandNode.stringProps.forEach { (key, value) ->
          val newValue = getSingleOrMultilineValue(value)
          appendLine(indent(tabs, "$key: $newValue"))
        }
        commandNode.stringList.forEach { value ->
          appendLine(indent(tabs, "- $value"))
        }
        commandNode.mapProps.forEach { (key, valueMap: Map<String, String>) ->
          appendLine(indent(tabs, "$key:"))
          tabs++
          valueMap.forEach { key, value ->
            appendLine(indent(tabs, "$key: $value"))
          }
          tabs--
        }
        if (commandNode.nestedSelectorProps.isNotEmpty()) {
          commandNode.nestedSelectorProps.forEach { (key, value: ElementSelectorData) ->
            appendLine(indent(tabs, "$key:"))
            tabs++
            renderElementSelectorData(value, tabs, this)
            tabs--
          }
        }
        if (commandNode.nestedListSelectorProps.isNotEmpty()) {
          commandNode.nestedListSelectorProps.forEach { (key, value: List<ElementSelectorData>) ->
            appendLine(indent(tabs, "$key:"))
            tabs++
            value.forEach { selectorData ->
              appendLine(indent(tabs + 1, "-"))
              renderElementSelectorData(selectorData, tabs + 2, this)
            }
            tabs--
          }
        }
        tabs--
      }
    }
    return yamlString
  }

  private fun String.wrappedInQuotes(): String {
    val value = this

    val shouldQuote = value.matches(Regex("""^[-+]?[0-9]+$""")) ||
      // purely numeric
      value.matches(Regex("""^0[0-9]+$""")) ||
      // leading 0
      value.matches(Regex("""^\d{4}-\d{2}-\d{2}$""")) ||
      // date
      value.lowercase() in listOf("true", "false", "yes", "no", "on", "off") ||
      value.contains(":") ||
      value.contains("#")

    return if (shouldQuote) "\"$value\"" else value
  }
}
