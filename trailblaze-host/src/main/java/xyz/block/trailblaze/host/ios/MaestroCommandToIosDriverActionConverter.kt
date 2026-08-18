package xyz.block.trailblaze.host.ios

import maestro.KeyCode
import maestro.SwipeDirection
import maestro.orchestra.AssertConditionCommand
import maestro.orchestra.BackPressCommand
import maestro.orchestra.ClearKeychainCommand
import maestro.orchestra.ClearStateCommand
import maestro.orchestra.Command
import maestro.orchestra.EraseTextCommand
import maestro.orchestra.HideKeyboardCommand
import maestro.orchestra.InputRandomCommand
import maestro.orchestra.InputTextCommand
import maestro.orchestra.KillAppCommand
import maestro.orchestra.LaunchAppCommand
import maestro.orchestra.OpenLinkCommand
import maestro.orchestra.PressKeyCommand
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
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.util.Console

/**
 * Converts Maestro [Command] objects to [IosDriverAction] for execution through the AXe CLI,
 * bypassing Maestro's Orchestra/XCUITest pipeline entirely.
 *
 * iOS Simulator equivalent of [xyz.block.trailblaze.android.accessibility.MaestroCommandConverter].
 * Commands AXe can't service (e.g. `SetOrientationCommand`, `SetAirplaneModeCommand`) fail the
 * batch, naming the command — so trail authors see what's missing.
 */
object MaestroCommandToIosDriverActionConverter {

  private const val DEFAULT_ERASE_COUNT = 50

  /**
   * Converts every command, failing loudly on the first one the driver can't service (a null
   * per-command conversion); skipping an unsupported command would report Success for a
   * partially-run batch. An empty (non-null) conversion is an intentional no-op and is skipped.
   */
  fun convertAll(commands: List<Command>): List<IosDriverAction> = commands.flatMap { command ->
    convert(command)
      ?: throw TrailblazeException(
        "Maestro command ${command::class.simpleName} is unsupported by the AXe driver — " +
          "failing the batch instead of silently skipping it.",
      )
  }

  /**
   * Converts one command. Returns null when the driver can't service the command (unsupported —
   * [convertAll] fails the batch), and an empty list for an intentional successful no-op.
   */
  fun convert(command: Command): List<IosDriverAction>? = when (command) {
    is TapOnPointV2Command -> listOf(convertTapOnPointV2(command))
    is TapOnPointCommand -> listOf(convertTapOnPoint(command))
    is TapOnElementCommand -> listOf(convertTapOnElement(command))
    is SwipeCommand -> convertSwipe(command)
    is InputTextCommand -> listOf(IosDriverAction.InputText(command.text))
    is InputRandomCommand -> listOf(IosDriverAction.InputText(command.genRandomString()))
    is EraseTextCommand -> listOf(IosDriverAction.EraseText(command.charactersToErase ?: DEFAULT_ERASE_COUNT))
    // iOS has no native "back" button, and Maestro's own iOS driver no-ops BACK. Mirror that
    // (log + skip) so a shared trail behaves identically on IOS_HOST and this driver — mapping
    // to Home would background the app mid-trail. Authored trails should tap the app's own
    // back affordance instead.
    is BackPressCommand -> {
      Console.log("[IosDriverConverter] BackPressCommand has no iOS equivalent — skipping (Maestro parity)")
      emptyList()
    }
    is HideKeyboardCommand -> {
      // AXe has no direct "dismiss keyboard" — a tap-outside in the upper-left is a common
      // fallback. Use (10, 80) rather than (10, 10) to avoid catching iOS's status-bar area.
      // If that proves unreliable, revisit with an `axe key` sequence (Return, etc.).
      Console.log("[IosDriverConverter] HideKeyboardCommand: tapping (10, 80) as a fallback")
      listOf(IosDriverAction.Tap(10, 80))
    }
    is ScrollCommand -> listOf(IosDriverAction.ScrollDown) // matches Maestro's default forward scroll
    // timeout is a String in Maestro 2.6.1 and may be a non-numeric expression; degrade to the
    // default rather than throwing NumberFormatException on this alternate-driver path.
    is WaitForAnimationToEndCommand -> listOf(IosDriverAction.WaitForSettle(timeoutMs = command.timeout?.toLongOrNull() ?: 5_000L))
    is AssertConditionCommand -> convertAssertCondition(command)
    is PressKeyCommand -> convertPressKey(command)
    is LaunchAppCommand -> listOf(
      IosDriverAction.LaunchApp(
        command.appId,
        // Maestro's default for an omitted stopApp is stop-then-launch (cold start).
        stopFirst = command.stopApp != false,
        clearState = command.clearState == true,
        clearKeychain = command.clearKeychain == true,
        permissions = command.permissions,
        launchArguments = toIosLaunchArgumentsList(command.launchArguments.orEmpty()),
      ),
    )
    is StopAppCommand -> listOf(IosDriverAction.StopApp(command.appId))
    is KillAppCommand -> listOf(IosDriverAction.StopApp(command.appId))
    is ClearStateCommand -> listOf(IosDriverAction.ClearState(command.appId))
    is ClearKeychainCommand -> listOf(IosDriverAction.ClearKeychain)
    is OpenLinkCommand -> listOf(IosDriverAction.OpenLink(command.link))
    is TakeScreenshotCommand -> listOf(IosDriverAction.TakeScreenshot)
    else -> {
      Console.log("[IosDriverConverter] Unsupported command: ${command::class.simpleName}")
      null
    }
  }

  /**
   * Flattens launch arguments into `xcrun simctl launch` argv, mirroring Maestro's
   * `IOSLaunchArguments`: non-boolean keys gain a `-` prefix (unless already dashed) so
   * `UserDefaults` surfaces the pair; boolean keys pass through untouched.
   */
  private fun toIosLaunchArgumentsList(launchArguments: Map<String, Any>): List<String> =
    launchArguments.flatMap { (key, value) ->
      val argKey = if (value is Boolean || key.startsWith("-")) key else "-$key"
      listOf(argKey, value.toString())
    }

  private fun convertTapOnPoint(command: TapOnPointCommand): IosDriverAction =
    if (command.longPress == true) IosDriverAction.LongPress(command.x, command.y)
    else IosDriverAction.Tap(command.x, command.y)

  private fun convertTapOnPointV2(command: TapOnPointV2Command): IosDriverAction {
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
          "[IosDriverConverter] TapOnPointV2Command with longPress=true and percent coords — " +
            "AXe has no percent-based touch-hold, falling back to a regular tap. " +
            "Switch to absolute coordinates if a real long-press is required.",
        )
      }
      IosDriverAction.TapRelative(xPct, yPct)
    } else {
      val x = xStr.toDouble().toInt()
      val y = yStr.toDouble().toInt()
      if (command.longPress == true) IosDriverAction.LongPress(x, y) else IosDriverAction.Tap(x, y)
    }
  }

  private fun convertTapOnElement(command: TapOnElementCommand): IosDriverAction {
    val nodeSelector = convertElementSelectorToNodeSelector(convertMaestroSelector(command.selector))
    return IosDriverAction.TapOnElement(
      nodeSelector = nodeSelector,
      longPress = command.longPress == true,
      // Preserves Maestro-level `optional: true` (best-effort taps on maybe-present dialogs)
      // through the AXe lowering, matching the Android accessibility converter.
      optional = command.optional,
    )
  }

  private fun convertSwipe(command: SwipeCommand): List<IosDriverAction>? = when {
    command.startPoint != null && command.endPoint != null -> listOf(
      IosDriverAction.Swipe(
        startX = command.startPoint!!.x,
        startY = command.startPoint!!.y,
        endX = command.endPoint!!.x,
        endY = command.endPoint!!.y,
        durationMs = command.duration,
      ),
    )
    command.startRelative != null && command.endRelative != null -> {
      val start = parseRelativePoint(command.startRelative!!)
      val end = parseRelativePoint(command.endRelative!!)
      if (start != null && end != null) {
        listOf(
          IosDriverAction.SwipeRelative(
            startXPercent = start.first,
            startYPercent = start.second,
            endXPercent = end.first,
            endYPercent = end.second,
            durationMs = command.duration,
          ),
        )
      } else {
        Console.log(
          "[IosDriverConverter] SwipeCommand had unparseable relative points " +
            "('${command.startRelative}' → '${command.endRelative}') — unsupported",
        )
        null
      }
    }
    command.direction != null -> {
      val dir = when (command.direction!!) {
        SwipeDirection.UP -> IosDriverAction.Direction.UP
        SwipeDirection.DOWN -> IosDriverAction.Direction.DOWN
        SwipeDirection.LEFT -> IosDriverAction.Direction.LEFT
        SwipeDirection.RIGHT -> IosDriverAction.Direction.RIGHT
      }
      listOf(IosDriverAction.SwipeDirection(dir, command.duration))
    }
    else -> {
      Console.log("[IosDriverConverter] SwipeCommand had no points or direction — unsupported")
      null
    }
  }

  /** Parses a Maestro relative point ("50%,47%", spaces tolerated) into x/y percentages. */
  private fun parseRelativePoint(relative: String): Pair<Double, Double>? {
    val parts = relative.split(",").map { it.trim().removeSuffix("%") }
    if (parts.size != 2) return null
    val x = parts[0].toDoubleOrNull() ?: return null
    val y = parts[1].toDoubleOrNull() ?: return null
    return x to y
  }

  private fun convertAssertCondition(command: AssertConditionCommand): List<IosDriverAction>? {
    val cond = command.condition
    val timeoutMs = command.timeoutMs() ?: 5_000L
    return when {
      cond.visible != null -> {
        val selector = convertElementSelectorToNodeSelector(convertMaestroSelector(cond.visible!!))
        listOf(IosDriverAction.AssertVisible(selector, timeoutMs, optional = command.optional))
      }
      cond.notVisible != null -> {
        val selector = convertElementSelectorToNodeSelector(convertMaestroSelector(cond.notVisible!!))
        listOf(IosDriverAction.AssertNotVisible(selector, timeoutMs, optional = command.optional))
      }
      else -> {
        Console.log("[IosDriverConverter] AssertConditionCommand without visible/notVisible — unsupported")
        null
      }
    }
  }

  /**
   * Maps Maestro key codes onto driver primitives: a HID keycode ([IosDriverAction.PressKey])
   * where one exists, a hardware button otherwise. Android/TV-only codes (volume, remote) have
   * no iOS Simulator equivalent and are logged + skipped.
   */
  private fun convertPressKey(command: PressKeyCommand): List<IosDriverAction> = when (command.code) {
    KeyCode.ENTER -> listOf(IosDriverAction.PressKey.ENTER)
    KeyCode.BACKSPACE -> listOf(IosDriverAction.PressKey.BACKSPACE)
    KeyCode.TAB -> listOf(IosDriverAction.PressKey.TAB)
    KeyCode.ESCAPE -> listOf(IosDriverAction.PressKey.ESCAPE)
    KeyCode.HOME -> listOf(IosDriverAction.PressHome)
    KeyCode.LOCK, KeyCode.POWER -> listOf(IosDriverAction.PressLock)
    // BACK deliberately falls through to the skip below — Maestro's iOS driver no-ops it, and
    // mapping it to Home would background the app mid-trail (see BackPressCommand above).
    else -> {
      Console.log("[IosDriverConverter] pressKey(${command.code.name}) has no iOS mapping — skipping")
      emptyList()
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
