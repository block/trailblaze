package xyz.block.trailblaze.ui.utils

import kotlin.math.pow

object FormattingUtils {
  /**
   * Formats milliseconds to seconds with 2 decimal places (e.g., "1.25s").
   * Useful for displaying durations, loading times, and performance metrics.
   */
  fun formatDuration(durationMs: Long): String {
    val seconds = durationMs / 1000.0
    return "${(seconds * 100).toInt() / 100.0}s"
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
   * Formats a double with exactly the specified number of decimal places.
   * Pads with zeros or truncates as needed (e.g., 123.4 with 2 decimals → "123.40").
   */
  fun formatDouble(value: Double, decimals: Int): String {
    val factor = 10.0.pow(decimals)
    val rounded = kotlin.math.round(value * factor) / factor
    val str = rounded.toString()
    val dotIndex = str.indexOf(".")
    return if (decimals <= 0) str.substringBefore(".") else when {
      dotIndex < 0 -> str + "." + "0".repeat(decimals)
      else -> {
        val currentDecimals = str.length - dotIndex - 1
        if (currentDecimals == decimals) str
        else if (currentDecimals < decimals) str + "0".repeat(decimals - currentDecimals)
        else str.substring(0, dotIndex + 1 + decimals)
      }
    }
  }

}