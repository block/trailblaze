package xyz.block.trailblaze.host

import maestro.Driver
import maestro.Maestro
import maestro.orchestra.MaestroCommand
import maestro.orchestra.Orchestra
import maestro.orchestra.TapOnPointV2Command
import xyz.block.trailblaze.maestro.OrchestraRunner

/**
 * Maestro's own `TapOnPointV2` handler parses a percent point with `Integer.parseInt`, so a
 * decimal percent such as `"50.5%,26%"` (from `tapOnPercent: { x: 50.5, y: 26 }`) would throw on
 * the Maestro driver. Rewrites such a tap to the absolute point Maestro itself would compute
 * (`grid * percent / 100`, truncated), keeping the fraction instead of dropping it. Every other
 * command, including whole-number percent taps, passes through untouched.
 */
internal object DecimalPercentTapLowering {

  /** [screenGrid] is Maestro's `widthGrid to heightGrid`, read only when a rewrite is needed. */
  fun lower(command: MaestroCommand, screenGrid: () -> Pair<Int, Int>): MaestroCommand {
    val tap = command.tapOnPointV2Command ?: return command
    val (xPercent, yPercent) = decimalPercents(tap.point) ?: return command
    val (width, height) = screenGrid()
    val x = (width * xPercent / 100.0).toInt()
    val y = (height * yPercent / 100.0).toInt()
    return MaestroCommand(tap.copy(point = "$x,$y"))
  }

  /**
   * The x/y of a `"x%,y%"` point that Maestro can't parse as integers, else null. Non-finite
   * values (`NaN%`) are left for Maestro to reject rather than turned into a tap.
   */
  private fun decimalPercents(point: String): Pair<Double, Double>? {
    val parts = point.split(",").map { it.trim() }
    if (parts.size != 2 || parts.any { !it.endsWith("%") }) return null
    val (xStr, yStr) = parts.map { it.removeSuffix("%").trim() }
    if (xStr.toIntOrNull() != null && yStr.toIntOrNull() != null) return null
    val x = xStr.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
    val y = yStr.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
    return x to y
  }
}

/**
 * Runs commands through Maestro's [Orchestra] on [driver], lowering decimal percent taps first.
 * [OrchestraRunner] hands it one command at a time, right before that command runs, so the
 * screen size is read at tap time and a preceding rotation is already reflected in it.
 */
internal class HostOrchestraExecutor(
  private val driver: Driver,
  private val callbacks: OrchestraRunner.OrchestraCallbacks,
) : OrchestraRunner.OrchestraExecutor {
  override suspend fun execute(commands: List<MaestroCommand>): Boolean = Orchestra(
    maestro = Maestro(driver),
    onCommandComplete = callbacks.onCommandComplete,
    onCommandFailed = { index, command, throwable ->
      callbacks.onCommandFailed(index, command, throwable)
      Orchestra.ErrorResolution.FAIL
    },
  ).runFlow(
    commands.map { command ->
      DecimalPercentTapLowering.lower(command) {
        driver.deviceInfo().let { it.widthGrid to it.heightGrid }
      }
    },
  ).success
}
