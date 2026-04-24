package xyz.block.trailblaze.host.axe

import maestro.SwipeDirection
import maestro.orchestra.AssertConditionCommand
import maestro.orchestra.BackPressCommand
import maestro.orchestra.Command
import maestro.orchestra.EraseTextCommand
import maestro.orchestra.HideKeyboardCommand
import maestro.orchestra.InputRandomCommand
import maestro.orchestra.InputTextCommand
import maestro.orchestra.KillAppCommand
import maestro.orchestra.LaunchAppCommand
import maestro.orchestra.OpenLinkCommand
import maestro.orchestra.ScrollCommand
import maestro.orchestra.StopAppCommand
import maestro.orchestra.SwipeCommand
import maestro.orchestra.TakeScreenshotCommand
import maestro.orchestra.TapOnElementCommand
import maestro.orchestra.TapOnPointCommand
import maestro.orchestra.TapOnPointV2Command
import maestro.orchestra.WaitForAnimationToEndCommand
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.api.TrailblazeElementSelector
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.util.Console

/**
 * Converts Maestro [Command] objects to [AxeAction] for execution through the AXe CLI,
 * bypassing Maestro's Orchestra/XCUITest pipeline entirely.
 *
 * iOS Simulator equivalent of [xyz.block.trailblaze.android.accessibility.MaestroCommandConverter].
 * Commands AXe can't service (e.g. `SetOrientationCommand`, `SetAirplaneModeCommand`) are logged
 * and skipped — not silently dropped — so trail authors see what's missing.
 */
object MaestroCommandToAxeActionConverter {

  private const val DEFAULT_ERASE_COUNT = 50

  fun convertAll(commands: List<Command>): List<AxeAction> =
    commands.flatMap { convert(it) }

  fun convert(command: Command): List<AxeAction> = when (command) {
    is TapOnPointV2Command -> listOf(convertTapOnPointV2(command))
    is TapOnPointCommand -> listOf(convertTapOnPoint(command))
    is TapOnElementCommand -> listOf(convertTapOnElement(command))
    is SwipeCommand -> convertSwipe(command)
    is InputTextCommand -> listOf(AxeAction.InputText(command.text))
    is InputRandomCommand -> listOf(AxeAction.InputText(command.genRandomString()))
    is EraseTextCommand -> listOf(AxeAction.EraseText(command.charactersToErase ?: DEFAULT_ERASE_COUNT))
    // iOS has no native "back" button — a tap at top-left is the usual convention, but trails
    // normally use a UI back button. We'll map BackPressCommand to a Home press as the safest
    // system-level navigation; authored trails should prefer explicit taps.
    is BackPressCommand -> {
      Console.log("[AxeConverter] BackPressCommand has no iOS equivalent — mapping to Home")
      listOf(AxeAction.PressHome)
    }
    is HideKeyboardCommand -> {
      // AXe has no direct "dismiss keyboard" — a tap-outside in the upper-left is a common
      // fallback. Use (10, 80) rather than (10, 10) to avoid catching iOS's status-bar area.
      // If that proves unreliable, revisit with an `axe key` sequence (Return, etc.).
      Console.log("[AxeConverter] HideKeyboardCommand: tapping (10, 80) as a fallback")
      listOf(AxeAction.Tap(10, 80))
    }
    is ScrollCommand -> listOf(AxeAction.ScrollDown) // matches Maestro's default forward scroll
    is WaitForAnimationToEndCommand -> listOf(AxeAction.WaitForSettle(timeoutMs = command.timeout ?: 5_000L))
    is AssertConditionCommand -> convertAssertCondition(command)
    is LaunchAppCommand -> listOf(AxeAction.LaunchApp(command.appId))
    is StopAppCommand -> listOf(AxeAction.StopApp(command.appId))
    is KillAppCommand -> listOf(AxeAction.StopApp(command.appId))
    is OpenLinkCommand -> listOf(AxeAction.OpenLink(command.link))
    is TakeScreenshotCommand -> listOf(AxeAction.TakeScreenshot)
    else -> {
      Console.log("[AxeConverter] Skipping unsupported command: ${command::class.simpleName}")
      emptyList()
    }
  }

  private fun convertTapOnPoint(command: TapOnPointCommand): AxeAction =
    if (command.longPress == true) AxeAction.LongPress(command.x, command.y)
    else AxeAction.Tap(command.x, command.y)

  private fun convertTapOnPointV2(command: TapOnPointV2Command): AxeAction {
    val parts = command.point.split(",").map { it.trim() }
    if (parts.size != 2) error("Invalid point format: ${command.point}")
    val xStr = parts[0]
    val yStr = parts[1]
    return if (xStr.endsWith("%") && yStr.endsWith("%")) {
      val xPct = xStr.removeSuffix("%").toDouble()
      val yPct = yStr.removeSuffix("%").toDouble()
      if (command.longPress == true) {
        // TapRelative has no long-press form, and there's no AXe touch primitive keyed on
        // percent coords. Warn the author rather than silently dropping the long-press intent
        // — authored trails should use absolute coords if they need a real hold.
        Console.log(
          "[AxeConverter] TapOnPointV2Command with longPress=true and percent coords — " +
            "AXe has no percent-based touch-hold, falling back to a regular tap. " +
            "Switch to absolute coordinates if a real long-press is required.",
        )
      }
      AxeAction.TapRelative(xPct, yPct)
    } else {
      val x = xStr.toDouble().toInt()
      val y = yStr.toDouble().toInt()
      if (command.longPress == true) AxeAction.LongPress(x, y) else AxeAction.Tap(x, y)
    }
  }

  private fun convertTapOnElement(command: TapOnElementCommand): AxeAction {
    val nodeSelector = convertElementSelectorToNodeSelector(convertMaestroSelector(command.selector))
    return AxeAction.TapOnElement(nodeSelector = nodeSelector, longPress = command.longPress == true)
  }

  private fun convertSwipe(command: SwipeCommand): List<AxeAction> = when {
    command.startPoint != null && command.endPoint != null -> listOf(
      AxeAction.Swipe(
        startX = command.startPoint!!.x,
        startY = command.startPoint!!.y,
        endX = command.endPoint!!.x,
        endY = command.endPoint!!.y,
        durationMs = command.duration,
      ),
    )
    command.direction != null -> {
      val dir = when (command.direction!!) {
        SwipeDirection.UP -> AxeAction.Direction.UP
        SwipeDirection.DOWN -> AxeAction.Direction.DOWN
        SwipeDirection.LEFT -> AxeAction.Direction.LEFT
        SwipeDirection.RIGHT -> AxeAction.Direction.RIGHT
      }
      listOf(AxeAction.SwipeDirection(dir, command.duration))
    }
    else -> {
      Console.log("[AxeConverter] SwipeCommand had no points or direction — skipping")
      emptyList()
    }
  }

  private fun convertAssertCondition(command: AssertConditionCommand): List<AxeAction> {
    val cond = command.condition
    val timeoutMs = command.timeoutMs() ?: 5_000L
    return when {
      cond.visible != null -> {
        val selector = convertElementSelectorToNodeSelector(convertMaestroSelector(cond.visible!!))
        listOf(AxeAction.AssertVisible(selector, timeoutMs))
      }
      cond.notVisible != null -> {
        val selector = convertElementSelectorToNodeSelector(convertMaestroSelector(cond.notVisible!!))
        listOf(AxeAction.AssertNotVisible(selector, timeoutMs))
      }
      else -> {
        Console.log("[AxeConverter] AssertConditionCommand without visible/notVisible — skipping")
        emptyList()
      }
    }
  }

  /**
   * Converts a Maestro-shaped [TrailblazeElementSelector] to a [TrailblazeNodeSelector]
   * carrying a [DriverNodeMatch.IosAxe] match so it resolves correctly against IosAxe trees.
   *
   * Field mapping:
   * - `textRegex` → `labelRegex` (Maestro `text` matches the AX `label` field)
   * - `idRegex` → `uniqueId` (treated as exact match since AX identifiers are identity, not patterns)
   * - `enabled` → `enabled`
   *
   * State flags without a direct AX equivalent (`selected`, `focused`, `checked`) are dropped —
   * they were Maestro-inferred rather than native on iOS anyway.
   */
  private fun convertElementSelectorToNodeSelector(
    selector: TrailblazeElementSelector,
  ): TrailblazeNodeSelector {
    val hasMatch = selector.textRegex != null ||
      selector.idRegex != null ||
      selector.enabled != null
    val driverMatch = if (hasMatch) {
      DriverNodeMatch.IosAxe(
        labelRegex = selector.textRegex,
        uniqueId = selector.idRegex,
        enabled = selector.enabled,
      )
    } else null

    return TrailblazeNodeSelector.withMatch(
      driverMatch,
      below = selector.below?.let { convertElementSelectorToNodeSelector(it) },
      above = selector.above?.let { convertElementSelectorToNodeSelector(it) },
      leftOf = selector.leftOf?.let { convertElementSelectorToNodeSelector(it) },
      rightOf = selector.rightOf?.let { convertElementSelectorToNodeSelector(it) },
      childOf = selector.childOf?.let { convertElementSelectorToNodeSelector(it) },
      containsChild = selector.containsChild?.let { convertElementSelectorToNodeSelector(it) },
      containsDescendants = selector.containsDescendants?.map { convertElementSelectorToNodeSelector(it) },
      index = selector.index?.toDoubleOrNull()?.toInt(),
    )
  }

  private fun convertMaestroSelector(
    selector: maestro.orchestra.ElementSelector,
  ): TrailblazeElementSelector = TrailblazeElementSelector(
    textRegex = selector.textRegex,
    idRegex = selector.idRegex,
    index = selector.index,
    enabled = selector.enabled,
    selected = selector.selected,
    checked = selector.checked,
    focused = selector.focused,
    below = selector.below?.let { convertMaestroSelector(it) },
    above = selector.above?.let { convertMaestroSelector(it) },
    leftOf = selector.leftOf?.let { convertMaestroSelector(it) },
    rightOf = selector.rightOf?.let { convertMaestroSelector(it) },
    containsChild = selector.containsChild?.let { convertMaestroSelector(it) },
    containsDescendants = selector.containsDescendants?.map { convertMaestroSelector(it) },
    childOf = selector.childOf?.let { convertMaestroSelector(it) },
  )
}
