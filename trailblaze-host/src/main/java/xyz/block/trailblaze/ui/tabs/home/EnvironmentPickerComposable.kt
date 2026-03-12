package xyz.block.trailblaze.ui.tabs.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import xyz.block.trailblaze.ui.models.TrailblazeServerState.TestingEnvironment

/**
 * A reusable composable that displays environment profile cards (Mobile / Web).
 * Used in both the onboarding dialog and the Home tab.
 */
@Composable
fun EnvironmentPicker(
  selectedEnvironment: TestingEnvironment?,
  onEnvironmentSelected: (TestingEnvironment) -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    EnvironmentCard(
      environment = TestingEnvironment.MOBILE,
      title = "Mobile",
      description = "Test Android and iOS apps on connected devices and simulators.",
      icon = { Icon(Icons.Filled.PhoneAndroid, contentDescription = "Mobile", modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary) },
      isSelected = selectedEnvironment == TestingEnvironment.MOBILE,
      onClick = { onEnvironmentSelected(TestingEnvironment.MOBILE) },
      modifier = Modifier.weight(1f),
    )
    EnvironmentCard(
      environment = TestingEnvironment.WEB,
      title = "Web",
      description = "Test web applications in a browser using Playwright.",
      icon = { Icon(Icons.Filled.Language, contentDescription = "Web", modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary) },
      isSelected = selectedEnvironment == TestingEnvironment.WEB,
      onClick = { onEnvironmentSelected(TestingEnvironment.WEB) },
      modifier = Modifier.weight(1f),
    )
  }
}

@Composable
private fun EnvironmentCard(
  environment: TestingEnvironment,
  title: String,
  description: String,
  icon: @Composable () -> Unit,
  isSelected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  OutlinedCard(
    onClick = onClick,
    modifier = modifier,
    border = if (isSelected) {
      BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
    } else {
      CardDefaults.outlinedCardBorder()
    },
    colors = if (isSelected) {
      CardDefaults.outlinedCardColors(
        containerColor = MaterialTheme.colorScheme.primaryContainer,
      )
    } else {
      CardDefaults.outlinedCardColors()
    },
  ) {
    Column(
      modifier = Modifier.padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      icon()
      Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
      )
      Text(
        text = description,
        style = MaterialTheme.typography.bodyMedium,
        color = if (isSelected) {
          MaterialTheme.colorScheme.onPrimaryContainer
        } else {
          MaterialTheme.colorScheme.onSurfaceVariant
        },
      )
    }
  }
}
