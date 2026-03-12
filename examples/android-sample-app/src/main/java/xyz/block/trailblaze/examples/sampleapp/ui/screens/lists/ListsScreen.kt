package xyz.block.trailblaze.examples.sampleapp.ui.screens.lists

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ListsScreen(onItemClick: (Int) -> Unit) {
  LazyColumn(modifier = Modifier.fillMaxSize()) {
    itemsIndexed((1..50).toList()) { index, item ->
      Text(
        text = "Item $item",
        fontSize = 18.sp,
        modifier =
          Modifier.fillMaxWidth()
            .clickable { onItemClick(item) }
            .padding(16.dp)
            .testTag("list_item_$item")
            .semantics { contentDescription = "list_item_$item" },
      )
      HorizontalDivider()
    }
  }
}
