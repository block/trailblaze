package xyz.block.trailblaze.ui.composables

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.atan2
import kotlin.math.PI
import kotlin.math.sqrt

/**
 * Represents a single segment in the pie chart.
 */
data class PieChartSegment(
  val label: String,
  val value: Long,
  val color: Color,
  val description: String? = null,
)

/**
 * Content displayed in the center of the pie chart.
 */
sealed interface PieChartCenterContent {
  data class Default(val totalLabel: String, val totalValue: String) : PieChartCenterContent
  data class Hovered(
    val label: String,
    val value: String,
    val percentage: String,
    val description: String?,
    val color: Color,
  ) : PieChartCenterContent
}

/**
 * Interactive donut-style pie chart with hover effects and legend.
 *
 * @param segments List of segments to display in the chart
 * @param modifier Modifier for the chart container
 * @param chartSize Size of the chart (diameter)
 * @param strokeWidth Width of the donut ring
 * @param showLegend Whether to display the legend
 * @param centerContent Custom content provider for the center display
 * @param formatValue Function to format values for display
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun InteractivePieChart(
  segments: List<PieChartSegment>,
  modifier: Modifier = Modifier,
  chartSize: Dp = 200.dp,
  strokeWidth: Float = 40f,
  showLegend: Boolean = true,
  centerContent: (@Composable (PieChartCenterContent) -> Unit)? = null,
  formatValue: (Long) -> String = { it.toString() },
) {
  val total = segments.sumOf { it.value }.toFloat()
  if (total == 0f) return
  
  // Track hover state
  var hoveredSegmentIndex by remember { mutableStateOf<Int?>(null) }
  
  Row(
    modifier = modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
  ) {
    // Pie Chart
    Box(
      modifier = Modifier.size(chartSize),
      contentAlignment = Alignment.Center
    ) {
      Canvas(
        modifier = Modifier
          .size(chartSize - 20.dp)
          .onPointerEvent(PointerEventType.Move) { event ->
            val position = event.changes.first().position
            
            // Calculate which segment is hovered
            val centerX = size.width / 2
            val centerY = size.height / 2
            val dx = position.x - centerX
            val dy = position.y - centerY
            val distance = sqrt(dx * dx + dy * dy)
            
            // Check if pointer is within the ring
            val canvasSize = minOf(size.width, size.height)
            val outerRadius = canvasSize / 2f
            val innerRadius = outerRadius - strokeWidth
            
            if (distance in innerRadius..outerRadius) {
              // Calculate angle from mouse position
              // atan2(dy, dx) returns angle where 0° = right, 90° = down, -90° = up
              val atan2Angle = (atan2(dy.toDouble(), dx.toDouble()) * 180 / PI).toFloat()
              
              // Convert to drawing space (drawing starts at -90° top, goes clockwise)
              // Add 90° to align with drawing coordinate system
              val mouseAngle = ((atan2Angle + 90f) % 360f + 360f) % 360f
              
              // Track segments in [0, 360) space
              var segmentStartAngle = 0f
              hoveredSegmentIndex = null
              
              for (index in segments.indices) {
                val segment = segments[index]
                val segmentSweep = (segment.value.toFloat() / total) * 360f
                val segmentEndAngle = segmentStartAngle + segmentSweep
                
                // Simple range check in [0, 360) space
                if (mouseAngle >= segmentStartAngle && mouseAngle < segmentEndAngle) {
                  hoveredSegmentIndex = index
                  break
                }
                
                segmentStartAngle = segmentEndAngle
              }
            } else {
              hoveredSegmentIndex = null
            }
          }
          .onPointerEvent(PointerEventType.Exit) {
            hoveredSegmentIndex = null
          }
      ) {
        val canvasSize = minOf(size.width, size.height)
        var startAngle = -90f // Start from top
        
        segments.forEachIndexed { index, segment ->
          val sweepAngle = (segment.value.toFloat() / total) * 360f
          val isHovered = hoveredSegmentIndex == index
          
          drawArc(
            color = segment.color.copy(alpha = if (hoveredSegmentIndex == null || isHovered) 1f else 0.5f),
            startAngle = startAngle,
            sweepAngle = sweepAngle,
            useCenter = false,
            topLeft = Offset(
              (size.width - canvasSize) / 2,
              (size.height - canvasSize) / 2
            ),
            size = Size(canvasSize, canvasSize),
            style = Stroke(width = if (isHovered) strokeWidth + 8f else strokeWidth)
          )
          
          startAngle += sweepAngle
        }
      }
      
      // Center content
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
      ) {
        val hoveredSegment = hoveredSegmentIndex?.let { segments.getOrNull(it) }
        
        if (centerContent != null) {
          if (hoveredSegment != null) {
            val percentage = ((hoveredSegment.value.toFloat() / total) * 100).toInt()
            centerContent(
              PieChartCenterContent.Hovered(
                label = hoveredSegment.label,
                value = formatValue(hoveredSegment.value),
                percentage = "$percentage%",
                description = hoveredSegment.description,
                color = hoveredSegment.color
              )
            )
          } else {
            centerContent(
              PieChartCenterContent.Default(
                totalLabel = "Total",
                totalValue = formatValue(total.toLong())
              )
            )
          }
        } else {
          // Default center display
          if (hoveredSegment != null) {
            val percentage = ((hoveredSegment.value.toFloat() / total) * 100).toInt()
            
            Text(
              text = hoveredSegment.label,
              style = MaterialTheme.typography.labelMedium,
              fontWeight = FontWeight.Medium,
              color = hoveredSegment.color
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
              text = formatValue(hoveredSegment.value),
              style = MaterialTheme.typography.titleLarge,
              fontWeight = FontWeight.Bold
            )
            Text(
              text = "$percentage%",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              fontWeight = FontWeight.Medium
            )
            hoveredSegment.description?.let { desc ->
              Text(
                text = desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
            }
          } else {
            Text(
              text = formatValue(total.toLong()),
              style = MaterialTheme.typography.titleLarge,
              fontWeight = FontWeight.Bold
            )
            Text(
              text = "Total",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
        }
      }
    }
    
    // Legend
    if (showLegend) {
      Column(
        modifier = Modifier.weight(1f).padding(start = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        segments.forEach { segment ->
          val percentage = ((segment.value.toFloat() / total) * 100).toInt()
          PieChartLegendItem(
            color = segment.color,
            label = segment.label,
            percentage = percentage,
            value = formatValue(segment.value)
          )
        }
      }
    }
  }
}

@Composable
private fun PieChartLegendItem(
  color: Color,
  label: String,
  percentage: Int,
  value: String,
) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp)
  ) {
    Box(
      modifier = Modifier
        .size(16.dp)
        .clip(RoundedCornerShape(4.dp))
        .background(color)
    )
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = label,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium
      )
      Text(
        text = value,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
    }
    Text(
      text = "$percentage%",
      style = MaterialTheme.typography.titleSmall,
      fontWeight = FontWeight.Bold,
      color = color
    )
  }
}
