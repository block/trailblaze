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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells.Fixed
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material.icons.filled.Money
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import xyz.block.trailblaze.llm.LlmSessionUsageAndCost
import xyz.block.trailblaze.ui.utils.FormattingUtils.formatCommaNumber
import xyz.block.trailblaze.ui.utils.FormattingUtils.formatDouble

@Composable
fun LlmUsageComposable(llmSessionUsageAndCost: LlmSessionUsageAndCost?, gridState: LazyGridState) {

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
                  text = "Avg: ${formatCommaNumber(llmSessionUsageAndCost.averageInputTokens, 0)}/req",
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
                  text = "Avg: ${formatCommaNumber(llmSessionUsageAndCost.averageOutputTokens, 0)}/req",
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
