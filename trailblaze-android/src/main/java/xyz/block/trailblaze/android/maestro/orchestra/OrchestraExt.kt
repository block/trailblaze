package xyz.block.trailblaze.android.maestro.orchestra

import maestro.MaestroException
import maestro.Point
import maestro.UiElement

/**
 * Last Update from Maestro v2.0.6
 * https://github.com/mobile-dev-inc/Maestro/blob/v2.0.6/maestro-orchestra/src/main/java/maestro/orchestra/Orchestra.kt
 * SEE THE README.md in this package for details on modifications from OSS version
 */
internal fun calculateElementRelativePoint(element: UiElement, point: String): Point {
  val bounds = element.bounds

  return if (point.contains("%")) {
    // Percentage-based coordinates within element bounds
    val (percentX, percentY) = point
      .replace("%", "")
      .split(",")
      .map { it.trim().toInt() }

    if (percentX !in 0..100 || percentY !in 0..100) {
      throw MaestroException.InvalidCommand("Invalid element-relative point: $point. Percentages must be between 0 and 100.")
    }

    val x = bounds.x + (bounds.width * percentX / 100)
    val y = bounds.y + (bounds.height * percentY / 100)
    Point(x, y)
  } else {
    // Absolute coordinates within element bounds
    val (x, y) = point.split(",")
      .map { it.trim().toInt() }

    if (x < 0 || y < 0 || x >= bounds.width || y >= bounds.height) {
      throw MaestroException.InvalidCommand("Invalid element-relative point: $point. Coordinates must be within element bounds (0,0) to (${bounds.width - 1},${bounds.height - 1}).")
    }

    Point(bounds.x + x, bounds.y + y)
  }
}
