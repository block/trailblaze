import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells.Fixed
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import xyz.block.trailblaze.llm.LlmSessionUsageAndCost
import xyz.block.trailblaze.ui.composables.InteractivePieChart
import xyz.block.trailblaze.ui.composables.PieChartCenterContent
import xyz.block.trailblaze.ui.composables.PieChartSegment
import xyz.block.trailblaze.ui.utils.FormattingUtils.formatCommaNumber
import xyz.block.trailblaze.ui.utils.FormattingUtils.formatDouble

@Composable
fun LlmUsageComposable(
  llmSessionUsageAndCost: LlmSessionUsageAndCost?,
  gridState: LazyGridState,
  onShowRequestDetails: (Int) -> Unit = {},
) {

  if (llmSessionUsageAndCost != null) {
    LazyVerticalGrid(
      columns = Fixed(1),
      horizontalArrangement = Arrangement.spacedBy(16.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
      state = gridState,
      modifier = Modifier.fillMaxSize()
    ) {
      item {
        // Header with Model Information Combined
        Card(
          modifier = Modifier.fillMaxWidth(),
          elevation = CardDefaults.cardElevation()
        ) {
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .padding(20.dp)
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
              Column {
                Text(
                  text = "LLM Usage Summary",
                  style = MaterialTheme.typography.headlineSmall,
                  fontWeight = FontWeight.Bold
                )
                Text(
                  text = "Session analysis and cost breakdown",
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
                )
              }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            // Model Information
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.Bottom
            ) {
              Column {
                Text(
                  text = "Model",
                  style = MaterialTheme.typography.labelMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                  text = llmSessionUsageAndCost.llmModel.modelId,
                  style = MaterialTheme.typography.titleMedium,
                  fontWeight = FontWeight.SemiBold
                )
              }

              Column(horizontalAlignment = Alignment.End) {
                Text(
                  text = "Provider",
                  style = MaterialTheme.typography.labelMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                  text = llmSessionUsageAndCost.llmModel.trailblazeLlmProvider.display,
                  style = MaterialTheme.typography.titleMedium,
                  fontWeight = FontWeight.SemiBold
                )
              }
            }
          }
        }
      }

      item {
        // Key Metrics in Single Card
        Card(
          modifier = Modifier.fillMaxWidth(),
          elevation = CardDefaults.cardElevation()
        ) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(20.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
          ) {
            // Total Cost
            Column(
              horizontalAlignment = Alignment.CenterHorizontally,
              modifier = Modifier.weight(1f)
            ) {
              Icon(
                imageVector = Icons.Default.MonetizationOn,
                contentDescription = null,
              )
              Spacer(modifier = Modifier.height(4.dp))
              Text(
                text = "Total Cost",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
              )
              Text(
                text = "$${formatDouble(llmSessionUsageAndCost.totalCostInUsDollars, 2)}",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
              )
            }

            // Vertical Divider
            Box(
              modifier = Modifier
                .width(1.dp)
                .height(60.dp)
                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            )

            // Total Requests
            Column(
              horizontalAlignment = Alignment.CenterHorizontally,
              modifier = Modifier.weight(1f)
            ) {
              Icon(
                imageVector = Icons.Default.BarChart,
                contentDescription = null,
              )
              Spacer(modifier = Modifier.height(4.dp))
              Text(
                text = "Total Requests",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
              )
              Text(
                text = "${llmSessionUsageAndCost.totalRequestCount}",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
              )
            }

            // Vertical Divider
            Box(
              modifier = Modifier
                .width(1.dp)
                .height(60.dp)
                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            )

            // Avg Response Time
            Column(
              horizontalAlignment = Alignment.CenterHorizontally,
              modifier = Modifier.weight(1f)
            ) {
              Icon(
                imageVector = Icons.Default.Speed,
                contentDescription = null,
              )
              Spacer(modifier = Modifier.height(4.dp))
              Text(
                text = "Avg Response",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
              )
              Text(
                text = "${formatDouble((llmSessionUsageAndCost.averageDurationMillis / 1000.0), 2)}s",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
              )
            }
          }
        }
      }

      item {
        // Token Usage & Cost Breakdown Combined
        Card(
          modifier = Modifier.fillMaxWidth(),
          elevation = CardDefaults.cardElevation()
        ) {
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .padding(20.dp)
          ) {
            Text(
              text = "Token Usage & Cost Analysis",
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Token Usage Row
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceEvenly
            ) {
              Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f)
              ) {
                Text(
                  text = "Input Tokens",
                  style = MaterialTheme.typography.labelLarge,
                  fontWeight = FontWeight.Medium,
                  color = MaterialTheme.colorScheme.primary,
                  textAlign = TextAlign.Center
                )
                Text(
                  text = formatCommaNumber(llmSessionUsageAndCost.totalInputTokens),
                  style = MaterialTheme.typography.headlineSmall,
                  fontWeight = FontWeight.Bold,
                  textAlign = TextAlign.Center
                )
                Text(
                  text = "Avg: ${formatCommaNumber(llmSessionUsageAndCost.averageInputTokens, 0)} Tokens/request",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  textAlign = TextAlign.Center
                )
              }

              // Vertical Divider
              Box(
                modifier = Modifier
                  .width(1.dp)
                  .height(70.dp)
                  .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
              )

              Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f)
              ) {
                Text(
                  text = "Output Tokens",
                  style = MaterialTheme.typography.labelLarge,
                  fontWeight = FontWeight.Medium,
                  color = MaterialTheme.colorScheme.secondary,
                  textAlign = TextAlign.Center
                )
                Text(
                  text = formatCommaNumber(llmSessionUsageAndCost.totalOutputTokens),
                  style = MaterialTheme.typography.headlineSmall,
                  fontWeight = FontWeight.Bold,
                  textAlign = TextAlign.Center
                )
                Text(
                  text = "Avg: ${formatCommaNumber(llmSessionUsageAndCost.averageOutputTokens, 0)} Tokens/request",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  textAlign = TextAlign.Center
                )
              }
            }

            Spacer(modifier = Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            // Cost Breakdown in Single Line
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column {
                  Text(
                    text = "Input Cost",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                  )
                  Text(
                    text = "$${
                      formatDouble(
                        (llmSessionUsageAndCost.totalInputTokens * llmSessionUsageAndCost.llmModel.inputCostPerOneMillionTokens) / 1_000_000,
                        2
                      )
                    }",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                  )
                }

                Column {
                  Text(
                    text = "Output Cost",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                  )
                  Text(
                    text = "$${
                      formatDouble(
                        (llmSessionUsageAndCost.totalOutputTokens * llmSessionUsageAndCost.llmModel.outputCostPerOneMillionTokens) / 1_000_000,
                        2
                      )
                    }",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                  )
                }
              }

              Column(horizontalAlignment = Alignment.End) {
                Text(
                  text = "Total Cost",
                  style = MaterialTheme.typography.labelMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                  text = "$${formatDouble(llmSessionUsageAndCost.totalCostInUsDollars, 2)}",
                  style = MaterialTheme.typography.titleMedium,
                  fontWeight = FontWeight.Bold,
                  color = MaterialTheme.colorScheme.error
                )
              }
            }
          }
        }
      }
      
      // Token Breakdown Section (if available)
      llmSessionUsageAndCost.aggregatedInputTokenBreakdown?.let { breakdown ->
        item {
          Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation()
          ) {
            Column(
              modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
              ) {
                Icon(
                  imageVector = Icons.Default.Summarize,
                  contentDescription = null,
                )
                Column {
                  Text(
                    text = "Input Token Breakdown",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                  )
                  Text(
                    text = "Aggregated across all ${llmSessionUsageAndCost.totalRequestCount} requests",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                  )
                  Text(
                    text = "Shows what takes up space in the LLM's context window",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                  )
                }
              }
              
              Spacer(modifier = Modifier.height(20.dp))
              
              // Pie Chart
              val segments = buildList {
                add(
                  PieChartSegment(
                    label = "System Prompts",
                    value = breakdown.systemPrompt.tokens,
                    color = MaterialTheme.colorScheme.primary,
                    description = "${breakdown.systemPrompt.count} messages"
                  )
                )
                add(
                  PieChartSegment(
                    label = "User Prompts",
                    value = breakdown.userPrompt.tokens,
                    color = MaterialTheme.colorScheme.secondary,
                    description = "${breakdown.userPrompt.count} messages"
                  )
                )
                add(
                  PieChartSegment(
                    label = "Tool Descriptors",
                    value = breakdown.toolDescriptors.tokens,
                    color = MaterialTheme.colorScheme.tertiary,
                    description = "${breakdown.toolDescriptors.count} tools"
                  )
                )
                if (breakdown.images.count > 0) {
                  add(
                    PieChartSegment(
                      label = "Images",
                      value = breakdown.images.tokens,
                      color = MaterialTheme.colorScheme.error,
                      description = "${breakdown.images.count} images"
                    )
                  )
                }
              }
              
              InteractivePieChart(
                segments = segments,
                formatValue = { formatCommaNumber(it) },
                centerContent = { content ->
                  when (content) {
                    is PieChartCenterContent.Default -> {
                      Text(
                        text = content.totalValue,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                      )
                      Text(
                        text = "Total Tokens",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                      )
                    }
                    is PieChartCenterContent.Hovered -> {
                      Text(
                        text = content.label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = content.color
                      )
                      Spacer(modifier = Modifier.height(4.dp))
                      Text(
                        text = content.value,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                      )
                      Text(
                        text = "${content.percentage} of context",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium
                      )
                      content.description?.let { desc ->
                        Text(
                          text = desc,
                          style = MaterialTheme.typography.bodySmall,
                          color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                      }
                    }
                  }
                }
              )
              

            }
          }
        }
      }
      
      // Per-Request Breakdown Section
      if (llmSessionUsageAndCost.requestBreakdowns.isNotEmpty()) {
        item {
          Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation()
          ) {
            Column(
              modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
            ) {
              Text(
                text = "Per-Request Details",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
              )
              
              Spacer(modifier = Modifier.height(8.dp))
              
              // Header row
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
              ) {
                Row(
                  horizontalArrangement = Arrangement.spacedBy(8.dp),
                  modifier = Modifier.weight(1f)
                ) {
                  Text(
                    text = "Request",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(75.dp)
                  )
                  Text(
                    text = "System",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(65.dp)
                  )
                  Text(
                    text = "User",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(65.dp)
                  )
                  Text(
                    text = "Tools",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(65.dp)
                  )
                  Text(
                    text = "Images",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(65.dp)
                  )
                }
                Row(
                  horizontalArrangement = Arrangement.spacedBy(8.dp),
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Column(modifier = Modifier.width(100.dp)) {
                    Text(
                      text = "Input (LLM)",
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.primary,
                      fontWeight = FontWeight.Bold
                    )
                    Text(
                      text = "Input (Est)",
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                      fontSize = MaterialTheme.typography.labelSmall.fontSize * 0.9
                    )
                  }
                  Text(
                    text = "Output",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(70.dp)
                  )
                  Spacer(modifier = Modifier.width(60.dp)) // View button space
                  Text(
                    text = "Cost",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(80.dp)
                  )
                }
              }
              
              Spacer(modifier = Modifier.height(8.dp))
              HorizontalDivider()
              Spacer(modifier = Modifier.height(4.dp))
              
              llmSessionUsageAndCost.requestBreakdowns.forEachIndexed { index, request ->
                // Column-aligned row with Input/Output groupings
                Row(
                  modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  // Left: Request number and breakdown columns
                  Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                  ) {
                    Text(
                      text = "Request ${index + 1}",
                      style = MaterialTheme.typography.bodyMedium,
                      fontWeight = FontWeight.Medium,
                      modifier = Modifier.width(75.dp)
                    )
                    
                    // Breakdown columns
                    request.inputTokenBreakdown?.let { breakdown ->
                      Text(
                        text = formatCommaNumber(breakdown.systemPrompt.tokens),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(65.dp)
                      )
                      Text(
                        text = formatCommaNumber(breakdown.userPrompt.tokens),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(65.dp)
                      )
                      Text(
                        text = formatCommaNumber(breakdown.toolDescriptors.tokens),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(65.dp)
                      )
                      Text(
                        text = if (breakdown.images.count > 0) formatCommaNumber(breakdown.images.tokens) else "-",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(65.dp)
                      )
                    } ?: run {
                      // No breakdown available - show dashes
                      repeat(4) {
                        Text(
                          text = "-",
                          style = MaterialTheme.typography.bodySmall,
                          color = MaterialTheme.colorScheme.onSurfaceVariant,
                          modifier = Modifier.width(65.dp)
                        )
                      }
                    }
                  }
                  
                  // Right: Input Total, Output Tokens, View button, and Cost (aligned columns)
                  Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                  ) {
                    // Input Tokens - Reported vs Estimated
                    Column(modifier = Modifier.width(100.dp)) {
                      // LLM-Reported tokens
                      Text(
                        text = formatCommaNumber(request.inputTokens),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold
                      )
                      
                      // Estimated tokens
                      val estimatedTokens = request.inputTokenBreakdown?.totalEstimatedTokens
                      Text(
                        text = estimatedTokens?.let { formatCommaNumber(it) } ?: "-",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = MaterialTheme.typography.bodySmall.fontSize * 0.9
                      )
                    }
                    
                    // Output Tokens
                    Text(
                      text = "${formatCommaNumber(request.outputTokens)}",
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                      modifier = Modifier.width(70.dp)
                    )
                    TextButton(
                      onClick = { onShowRequestDetails(index) },
                      contentPadding = ButtonDefaults.TextButtonContentPadding
                    ) {
                      Icon(
                        imageVector = Icons.Default.Chat,
                        contentDescription = "View chat history",
                        modifier = Modifier.size(16.dp).padding(end = 4.dp)
                      )
                      Text("View", style = MaterialTheme.typography.labelSmall)
                    }
                    Text(
                      text = "$${formatDouble(request.totalCost, 4)}",
                      style = MaterialTheme.typography.bodySmall,
                      fontWeight = FontWeight.Bold,
                      color = MaterialTheme.colorScheme.error,
                      modifier = Modifier.width(80.dp)
                    )
                  }
                }
                
                if (index < llmSessionUsageAndCost.requestBreakdowns.size - 1) {
                  HorizontalDivider()
                }
              }
            }
          }
        }
      }
    }
  } else {
    // No data state
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(32.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center
    ) {
      Text(
        text = "📊",
        style = MaterialTheme.typography.displayMedium
      )
      Spacer(modifier = Modifier.height(16.dp))
      Text(
        text = "No LLM Usage Data",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
      Spacer(modifier = Modifier.height(8.dp))
      Text(
        text = "No LLM requests were found in this session",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
      )
    }
  }
}
