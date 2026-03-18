package xyz.block.trailblaze.examples.sampleapp.ui.screens.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

data class CatalogItem(
  val id: String,
  val name: String,
  val category: String,
  val price: String,
  val seasonal: Boolean = false,
)

private val CATALOG_ITEMS =
  listOf(
    CatalogItem("coffee", "Coffee", "Beverages", "$2.50"),
    CatalogItem("coffee_latte", "Coffee Latte", "Beverages", "$4.50"),
    CatalogItem("coffee_americano", "Coffee Americano", "Beverages", "$3.50"),
    CatalogItem("pumpkin_spice", "Pumpkin Spice", "Seasonal", "$5.50", seasonal = true),
    CatalogItem("mango_smoothie", "Mango Smoothie", "Seasonal", "$5.00", seasonal = true),
    CatalogItem("tea", "Tea", "Beverages", "$2.00"),
    CatalogItem("orange_juice", "Orange Juice", "Beverages", "$3.00"),
    CatalogItem("muffin", "Muffin", "Food", "$3.50"),
    CatalogItem("bagel", "Bagel", "Food", "$2.50"),
  )

@Composable
fun CatalogScreen() {
  var showSeasonal by remember { mutableStateOf(false) }
  var selectedItem by remember { mutableStateOf<CatalogItem?>(null) }
  val visibleItems = remember(showSeasonal) { CATALOG_ITEMS.filter { !it.seasonal || showSeasonal } }

  Column(modifier = Modifier.fillMaxSize()) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      FilterChip(
        selected = showSeasonal,
        onClick = { showSeasonal = !showSeasonal },
        label = { Text("Show seasonal") },
        modifier = Modifier.testTag("filter_seasonal"),
      )
    }

    HorizontalDivider()

    LazyColumn(modifier = Modifier.fillMaxSize()) {
      items(visibleItems, key = { it.id }) { item ->
        CatalogItemRow(item = item, onClick = { selectedItem = item })
        HorizontalDivider()
      }
    }
  }

  selectedItem?.let { item ->
    AlertDialog(
      onDismissRequest = { selectedItem = null },
      title = { Text(item.name) },
      text = { Text("${item.category} · ${item.price}") },
      confirmButton = {
        Button(
          onClick = { selectedItem = null },
          modifier = Modifier.testTag("btn_add_to_cart"),
        ) {
          Text("Add to Cart")
        }
      },
      dismissButton = {
        TextButton(onClick = { selectedItem = null }) { Text("Dismiss") }
      },
    )
  }
}

@Composable
private fun CatalogItemRow(item: CatalogItem, onClick: () -> Unit) {
  val thumbnailColor =
    when (item.category) {
      "Beverages" -> MaterialTheme.colorScheme.primaryContainer
      "Food" -> MaterialTheme.colorScheme.secondaryContainer
      else -> MaterialTheme.colorScheme.tertiaryContainer
    }
  Row(
    modifier =
      Modifier.fillMaxWidth()
        .clickable(onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 12.dp)
        .testTag("catalog_row_${item.id}"),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      modifier =
        Modifier.size(48.dp)
          .background(thumbnailColor, RoundedCornerShape(8.dp))
          .semantics { contentDescription = "${item.name} icon" },
    )
    Spacer(Modifier.width(16.dp))
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = item.name,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.testTag("catalog_item_${item.id}"),
      )
      Text(
        text = item.category,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Text(
      text = item.price,
      style = MaterialTheme.typography.bodyMedium,
      modifier = Modifier.testTag("catalog_price_${item.id}"),
    )
  }
}
