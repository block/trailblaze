package xyz.block.trailblaze.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Model Context Protocol (MCP) logo icon.
 * Three interconnected curved segments forming a stylized link/chain symbol.
 * Based on the official MCP logo from modelcontextprotocol.io.
 */
val McpLogo: ImageVector
  get() {
    if (_McpLogo != null) return _McpLogo!!

    _McpLogo =
      ImageVector.Builder(
          name = "McpLogo",
          defaultWidth = 24.dp,
          defaultHeight = 24.dp,
          viewportWidth = 180f,
          viewportHeight = 180f,
        )
        .apply {
          // Top-left to center-right arc
          path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 11.0667f,
            strokeLineCap = StrokeCap.Round,
          ) {
            moveTo(23.5996f, 85.2532f)
            curveTo(23.5996f, 85.2532f, 86.2021f, 22.6507f, 86.2021f, 22.6507f)
            arcTo(22.12f, 22.12f, 0f, false, true, 117.503f, 22.6507f)
            arcTo(22.12f, 22.12f, 0f, false, true, 117.503f, 53.9519f)
            lineTo(70.2254f, 101.23f)
          }
          // Center-left to bottom-right arc
          path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 11.0667f,
            strokeLineCap = StrokeCap.Round,
          ) {
            moveTo(70.8789f, 100.578f)
            lineTo(117.504f, 53.952f)
            arcTo(22.12f, 22.12f, 0f, false, true, 148.806f, 53.952f)
            lineTo(149.132f, 54.278f)
            arcTo(22.12f, 22.12f, 0f, false, true, 149.132f, 85.5792f)
            lineTo(92.5139f, 142.198f)
            arcTo(4.77f, 4.77f, 0f, false, false, 92.5139f, 152.631f)
            lineTo(104.14f, 164.257f)
          }
          // Middle diagonal arc
          path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 11.0667f,
            strokeLineCap = StrokeCap.Round,
          ) {
            moveTo(101.853f, 38.3013f)
            lineTo(55.553f, 84.6011f)
            arcTo(22.12f, 22.12f, 0f, false, false, 55.553f, 115.902f)
            arcTo(22.12f, 22.12f, 0f, false, false, 86.8543f, 115.902f)
            lineTo(133.154f, 69.6025f)
          }
        }
        .build()

    return _McpLogo!!
  }

private var _McpLogo: ImageVector? = null
