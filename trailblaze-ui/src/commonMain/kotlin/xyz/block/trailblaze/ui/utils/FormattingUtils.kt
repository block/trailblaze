package xyz.block.trailblaze.ui.utils

import kotlin.math.pow

object FormattingUtils {
  /**
   * Formats milliseconds as a `m:ss.mmm`-style relative timestamp (e.g., `"1:23.456"`).
   * Used by the device logs panel for per-line "time since session start" display.
   */
  fun formatRelativeTimeWithMillis(ms: Long): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    val millis = ms % 1000
    val secStr = if (sec < 10) "0$sec" else "$sec"
    val msStr =
      when {
        millis < 10 -> "00$millis"
        millis < 100 -> "0$millis"
        else -> "$millis"
      }
    return "$min:$secStr.$msStr"
  }

  /**
   * Formats milliseconds to seconds with 2 decimal places (e.g., "1.25s").
   * Useful for displaying durations, loading times, and performance metrics.
   */
  fun formatDuration(durationMs: Long): String {
    val prefix = if (durationMs < 0) "-" else ""
    val absSeconds = kotlin.math.abs(durationMs) / 1000.0
    val truncated = (absSeconds * 100).toInt()
    val whole = truncated / 100
    val frac = truncated % 100
    return "$prefix${whole}.${frac.toString().padStart(2, '0')}s"
  }

  /**
   * Coarse, human-readable duration for compact labels (e.g. the "» 37m later" badge the
   * exported timeline shows over a collapsed idle gap). Rounds down to whole units:
   * `"45s"`, `"37m"`, `"2h 5m"`, `"1h"` (minutes dropped on an exact hour). Unlike
   * [formatDuration] (which renders seconds to 2dp and is meant for sub-minute step
   * durations), this stays readable across minutes and hours.
   */
  fun formatCompactDuration(durationMs: Long): String {
    val totalSeconds = kotlin.math.abs(durationMs) / 1000
    val totalMinutes = totalSeconds / 60
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    val seconds = totalSeconds % 60
    return when {
      hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
      hours > 0 -> "${hours}h"
      totalMinutes > 0 -> "${minutes}m"
      else -> "${seconds}s"
    }
  }

  /**
   * Formats integers with comma separators (e.g., 1234 → "1,234").
   * Used for displaying large numbers in a readable format.
   */
  fun formatCommaInt(value: Int): String {
    val result = buildString {
      val str = value.toString()
      for ((i, c) in str.reversed().withIndex()) {
        if (i > 0 && i % 3 == 0) append(",")
        append(c)
      }
    }.reversed()
    return result
  }

  /**
   * Formats any numeric value based on its type.
   * Integers get comma separators, floats get decimal formatting.
   *
   * @param decimals Number of decimal places for floating-point numbers
   */
  fun formatCommaNumber(value: Number, decimals: Int = 0): String {
    return when (value) {
      is Double, is Float -> formatDouble(value.toDouble(), decimals)
      is Int, is Long -> formatCommaInt(value.toInt())
      else -> value.toString()
    }
  }

  /**
   * Formats a double with exactly the specified number of decimal places and comma separators.
   * Pads with zeros or truncates as needed (e.g., 1234.4 with 2 decimals → "1,234.40").
   */
  fun formatDouble(value: Double, decimals: Int): String {
    // Use integer arithmetic to avoid Double.toString() scientific notation (e.g. "5.0E-4")
    val factor = 10.0.pow(decimals)
    val scaledRounded = kotlin.math.round(value * factor).toLong()
    val isNegative = scaledRounded < 0
    val absScaled = kotlin.math.abs(scaledRounded)
    val factorLong = factor.toLong()

    val intPart = absScaled / factorLong
    val fracPart = absScaled % factorLong

    val prefix = if (isNegative) "-" else ""
    val intStr = intPart.toString()

    val formattedStr = if (decimals <= 0) {
      "$prefix$intStr"
    } else {
      "$prefix$intStr.${fracPart.toString().padStart(decimals, '0')}"
    }

    // Add comma separators to the integer part
    val finalDotIndex = formattedStr.indexOf(".")
    val integerPart = if (finalDotIndex >= 0) formattedStr.take(finalDotIndex) else formattedStr
    val decimalPart = if (finalDotIndex >= 0) formattedStr.substring(finalDotIndex) else ""

    val formattedIntegerPart = buildString {
      for ((i, c) in integerPart.reversed().withIndex()) {
        if (i > 0 && i % 3 == 0 && c != '-') append(",")
        append(c)
      }
    }.reversed()

    return formattedIntegerPart + decimalPart
  }
}