package xyz.block.trailblaze.android.accessibility

import maestro.KeyCode
import maestro.SwipeDirection
import maestro.orchestra.AssertConditionCommand
import maestro.orchestra.BackPressCommand
import maestro.orchestra.Command
import maestro.orchestra.EraseTextCommand
import maestro.orchestra.HideKeyboardCommand
import maestro.orchestra.InputTextCommand
import maestro.orchestra.PressKeyCommand
import maestro.orchestra.ScrollCommand
import maestro.orchestra.SwipeCommand
import maestro.orchestra.TapOnElementCommand
import maestro.orchestra.TapOnPointCommand
import maestro.orchestra.TapOnPointV2Command
import maestro.orchestra.WaitForAnimationToEndCommand
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.api.TrailblazeElementSelector
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.util.Console

/**
 * Converts Maestro [Command] objects to [AccessibilityAction] for execution through the
 * accessibility service, bypassing Maestro's Orchestra/Driver/screenshot pipeline entirely.
 *
 * This is the bridge layer that allows existing `MapsToMaestroCommands` tools and trail
 * files (which generate Maestro commands) to run through the Maestro-free accessibility path.
 *
 * Conversion is best-effort: unsupported commands (e.g. `SetLocationCommand`) are skipped with
 * a warning since the accessibility service cannot perform all system-level operations.
 */
object MaestroCommandConverter {

  /** Default number of characters to erase when [EraseTextCommand.charactersToErase] is null. */
  private const val DEFAULT_ERASE_COUNT = 50

  /**
   * Converts a single Maestro [Command] to zero or more [AccessibilityAction]s.
   *
   * Returns an empty list for commands that have no accessibility equivalent, allowing
   * the caller to skip them gracefully or log a warning.
   */
  fun convert(command: Command): List<AccessibilityAction> {
    return when (command) {
      // LaunchAppCommand is handled separately by AccessibilityTrailblazeAgent.executeLaunchAppViaAdb()
      // before commands reach this converter. It requires ADB shell access for stop/clear/permissions
      // which is not available from the accessibility service context.
      is TapOnPointV2Command -> listOf(convertTapOnPointV2(command))
      is TapOnPointCommand -> listOf(convertTapOnPoint(command))
      is TapOnElementCommand -> listOf(convertTapOnElement(command))
      is SwipeCommand -> convertSwipe(command)
      is InputTextCommand -> listOf(AccessibilityAction.InputText(command.text))
      is EraseTextCommand ->
        listOf(AccessibilityAction.EraseText(command.charactersToErase ?: DEFAULT_ERASE_COUNT))
      is BackPressCommand -> listOf(AccessibilityAction.PressBack)
      is HideKeyboardCommand -> listOf(AccessibilityAction.HideKeyboard)
      // ScrollCommand has no direction field (only label/optional), so it always maps to
      // ScrollForward, matching Maestro Orchestra's scrollVerticalCommand() behavior.
      is ScrollCommand -> listOf(AccessibilityAction.ScrollForward)
      is PressKeyCommand -> convertPressKey(command)
      is WaitForAnimationToEndCommand ->
        listOf(AccessibilityAction.WaitForSettle(timeoutMs = command.timeout ?: 5_000L))
      is AssertConditionCommand -> convertAssertCondition(command)
      else -> {
        Console.log(
          "MaestroCommandConverter: Skipping unsupported command: ${command::class.simpleName}"
        )
        emptyList()
      }
    }
  }

  /**
   * Converts a list of Maestro [Command]s, flattening into a single action list.
   * Unsupported commands are skipped with logging.
   */
  fun convertAll(commands: List<Command>): List<AccessibilityAction> {
    val actions = commands.flatMap { convert(it) }
    if (actions.isEmpty() && commands.isNotEmpty()) {
      val skippedTypes = commands.map { it::class.simpleName }.distinct().joinToString(", ")
      Console.log(
        "WARNING: MaestroCommandConverter: All ${commands.size} commands produced no actions. Skipped types: $skippedTypes"
      )
    }
    return actions
  }

  private fun convertTapOnPointV2(command: TapOnPointV2Command): AccessibilityAction {
    val point = command.point
    // Point format is "x, y" or "x,y" or could be a percentage "50%,50%"
    val parts = point.split(",").map { it.trim() }
    if (parts.size != 2) {
      error("Invalid point format: $point")
    }

    val xStr = parts[0]
    val yStr = parts[1]

    return if (xStr.endsWith("%") && yStr.endsWith("%")) {
      // Relative/percentage coordinates — keep as Double through coordinate calculation
      // to avoid truncating fractional percentages before multiplication.
      val xPercent = xStr.removeSuffix("%").toDouble()
      val yPercent = yStr.removeSuffix("%").toDouble()
      if (command.longPress == true) {
        val (width, height) = TrailblazeAccessibilityService.getScreenDimensions()
        AccessibilityAction.LongPress(
          x = (width * xPercent / 100.0).toInt(),
          y = (height * yPercent / 100.0).toInt(),
        )
      } else {
        AccessibilityAction.TapRelative(
          percentX = xPercent.toInt(),
          percentY = yPercent.toInt(),
        )
      }
    } else {
      // Absolute coordinates (use toDouble().toInt() to handle floating-point strings like "540.5")
      val x = xStr.toDouble().toInt()
      val y = yStr.toDouble().toInt()
      if (command.longPress == true) {
        AccessibilityAction.LongPress(x = x, y = y)
      } else {
        AccessibilityAction.Tap(x = x, y = y)
      }
    }
  }

  private fun convertTapOnPoint(command: TapOnPointCommand): AccessibilityAction {
    return if (command.longPress == true) {
      AccessibilityAction.LongPress(x = command.x, y = command.y)
    } else {
      AccessibilityAction.Tap(x = command.x, y = command.y)
    }
  }

  private fun convertTapOnElement(command: TapOnElementCommand): AccessibilityAction {
    val maestroSelector = command.selector
    val elementSelector = convertElementSelector(maestroSelector)
    return AccessibilityAction.TapOnElement(
      nodeSelector = convertElementSelectorToNodeSelector(elementSelector),
      longPress = command.longPress == true,
    )
  }

  private fun convertSwipe(command: SwipeCommand): List<AccessibilityAction> {
    return when {
      // Absolute start/end points
      command.startPoint != null && command.endPoint != null -> {
        val start = command.startPoint!!
        val end = command.endPoint!!
        listOf(
          AccessibilityAction.Swipe(
            startX = start.x,
            startY = start.y,
            endX = end.x,
            endY = end.y,
            durationMs = command.duration,
          )
        )
      }
      // Relative percentage points (values may have optional "%" suffix)
      command.startRelative != null && command.endRelative != null -> {
        val (width, height) = TrailblazeAccessibilityService.getScreenDimensions()
        // Parse as Double to avoid truncating fractional percentages before multiplication.
        val startParts = command.startRelative!!.split(",").map {
          it.trim().removeSuffix("%").toDouble()
        }
        val endParts = command.endRelative!!.split(",").map {
          it.trim().removeSuffix("%").toDouble()
        }
        if (startParts.size != 2 || endParts.size != 2) {
          error("Invalid relative swipe format: start=${command.startRelative}, end=${command.endRelative}")
        }
        listOf(
          AccessibilityAction.Swipe(
            startX = (width * startParts[0] / 100.0).toInt(),
            startY = (height * startParts[1] / 100.0).toInt(),
            endX = (width * endParts[0] / 100.0).toInt(),
            endY = (height * endParts[1] / 100.0).toInt(),
            durationMs = command.duration,
          )
        )
      }
      // Direction-based swipe
      command.direction != null -> {
        val direction =
          when (command.direction!!) {
            SwipeDirection.UP -> AccessibilityAction.Direction.UP
            SwipeDirection.DOWN -> AccessibilityAction.Direction.DOWN
            SwipeDirection.LEFT -> AccessibilityAction.Direction.LEFT
            SwipeDirection.RIGHT -> AccessibilityAction.Direction.RIGHT
          }
        listOf(
          AccessibilityAction.SwipeDirection(
            direction = direction,
            durationMs = command.duration,
          )
        )
      }
      else -> {
        Console.log("MaestroCommandConverter: Cannot convert SwipeCommand — no direction or points")
        emptyList()
      }
    }
  }

  private fun convertAssertCondition(command: AssertConditionCommand): List<AccessibilityAction> {
    val condition = command.condition
    val timeoutMs = command.timeoutMs() ?: 5_000L
    return when {
      condition.visible != null -> {
        val elementSelector = convertElementSelector(condition.visible!!)
        val nodeSelector = convertElementSelectorToNodeSelector(elementSelector)
        listOf(AccessibilityAction.AssertVisible(nodeSelector = nodeSelector, timeoutMs = timeoutMs))
      }
      condition.notVisible != null -> {
        val elementSelector = convertElementSelector(condition.notVisible!!)
        val nodeSelector = convertElementSelectorToNodeSelector(elementSelector)
        listOf(
          AccessibilityAction.AssertNotVisible(nodeSelector = nodeSelector, timeoutMs = timeoutMs)
        )
      }
      else -> {
        Console.log(
          "MaestroCommandConverter: Skipping AssertConditionCommand with unsupported condition: $condition"
        )
        emptyList()
      }
    }
  }

  private fun convertPressKey(command: PressKeyCommand): List<AccessibilityAction> {
    return when (command.code) {
      KeyCode.BACK -> listOf(AccessibilityAction.PressBack)
      KeyCode.HOME -> listOf(AccessibilityAction.PressHome)
      else -> {
        Console.log(
          "MaestroCommandConverter: Key ${command.code} not supported via accessibility, skipping"
        )
        emptyList()
      }
    }
  }

  /**
   * Converts a [TrailblazeElementSelector] (Maestro-shaped) to a [TrailblazeNodeSelector]
   * with [DriverNodeMatch.AndroidAccessibility] matchers.
   *
   * This is the bridge that lets existing trail files (which produce Maestro commands with
   * [TrailblazeElementSelector]) run through the TrailblazeNode resolution pipeline.
   *
   * Mapping:
   * - `textRegex` → `DriverNodeMatch.AndroidAccessibility.textRegex` (same `resolveText()` priority)
   * - `idRegex` → `resourceIdRegex`
   * - `enabled/selected/checked/focused` → `isEnabled/isSelected/isChecked/isFocused`
   * - `index` (String?) → `TrailblazeNodeSelector.index` (Int?) via `toDoubleOrNull()?.toInt()`
   * - Spatial (`above/below/leftOf/rightOf`) and hierarchy (`childOf/containsChild/containsDescendants`) → recursive convert
   * - `size`, `traits`, `css` → dropped (no equivalent in driver-native model)
   */
  fun convertElementSelectorToNodeSelector(
    selector: TrailblazeElementSelector,
  ): TrailblazeNodeSelector {
    val hasDriverProperties = selector.textRegex != null ||
      selector.idRegex != null ||
      selector.enabled != null ||
      selector.selected != null ||
      selector.checked != null ||
      selector.focused != null

    val driverMatch = if (hasDriverProperties) {
      DriverNodeMatch.AndroidAccessibility(
        textRegex = selector.textRegex,
        resourceIdRegex = selector.idRegex,
        isEnabled = selector.enabled,
        isSelected = selector.selected,
        isChecked = selector.checked,
        isFocused = selector.focused,
      )
    } else {
      null
    }

    return TrailblazeNodeSelector.withMatch(
      driverMatch,
      below = selector.below?.let { convertElementSelectorToNodeSelector(it) },
      above = selector.above?.let { convertElementSelectorToNodeSelector(it) },
      leftOf = selector.leftOf?.let { convertElementSelectorToNodeSelector(it) },
      rightOf = selector.rightOf?.let { convertElementSelectorToNodeSelector(it) },
      childOf = selector.childOf?.let { convertElementSelectorToNodeSelector(it) },
      containsChild = selector.containsChild?.let { convertElementSelectorToNodeSelector(it) },
      containsDescendants = selector.containsDescendants?.map {
        convertElementSelectorToNodeSelector(it)
      },
      index = selector.index?.toDoubleOrNull()?.toInt(),
    )
  }

  /**
   * Converts a Maestro [maestro.orchestra.ElementSelector] to a [TrailblazeElementSelector].
   *
   * This maps the Maestro element matching model to our framework-native model,
   * preserving text/id regex, state filters, spatial relationships, and hierarchy.
   */
  private fun convertElementSelector(
    selector: maestro.orchestra.ElementSelector
  ): TrailblazeElementSelector {
    return TrailblazeElementSelector(
      textRegex = selector.textRegex,
      idRegex = selector.idRegex,
      index = selector.index,
      enabled = selector.enabled,
      selected = selector.selected,
      checked = selector.checked,
      focused = selector.focused,
      below = selector.below?.let { convertElementSelector(it) },
      above = selector.above?.let { convertElementSelector(it) },
      leftOf = selector.leftOf?.let { convertElementSelector(it) },
      rightOf = selector.rightOf?.let { convertElementSelector(it) },
      containsChild = selector.containsChild?.let { convertElementSelector(it) },
      containsDescendants = selector.containsDescendants?.map { convertElementSelector(it) },
      childOf = selector.childOf?.let { convertElementSelector(it) },
    )
  }
}
