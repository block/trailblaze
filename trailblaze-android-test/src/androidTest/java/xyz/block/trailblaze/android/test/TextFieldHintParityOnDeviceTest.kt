package xyz.block.trailblaze.android.test

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import xyz.block.trailblaze.android.test.hierarchy.AndroidHybridHierarchyCollector
import xyz.block.trailblaze.android.test.hierarchy.InProcessAccessibilityTree
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.TrailblazeNode

/**
 * A text field's hint reads here as the platform's accessibility tree publishes it.
 *
 * Recordings made on the accessibility driver name an empty field by that hint, so a different
 * string here is a field this driver cannot find. A field whose label and placeholder say the same
 * thing is the case that diverged: a card-number field whose recording anchors the one copy of its label.
 */
class TextFieldHintParityOnDeviceTest {

  @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun aTextFieldsHintMatchesThePlatformAccessibilityTree() {
    composeRule.setContent {
      Column {
        // A field that draws its label above and its placeholder inside at once, both saying the
        // same thing — Material's own fields show the placeholder only while focused.
        BasicTextField(value = "", onValueChange = {}, decorationBox = { inner ->
          Column {
            Text(CARD_NUMBER)
            Text(CARD_NUMBER)
            inner()
          }
        })
        BasicTextField(value = "", onValueChange = {}, decorationBox = { inner ->
          Column {
            Text(EXPIRY_LABEL)
            Text(EXPIRY_PLACEHOLDER)
            inner()
          }
        })
        OutlinedTextField(value = "", onValueChange = {}, label = { Text(EXPIRY_LABEL) }, placeholder = { Text(EXPIRY_PLACEHOLDER) })
        OutlinedTextField(value = "", onValueChange = {}, label = { Text(NOTE_LABEL) })
      }
    }
    composeRule.waitForIdle()

    val platform = checkNotNull(InProcessAccessibilityTree.capture(fresh = true)) { "no accessibility tree" }.tree
      .flatten()
      .mapNotNull { (it.driverDetail as? DriverNodeDetail.AndroidAccessibility)?.takeIf { d -> d.isEditable }?.hintText }
    val host = composeRule.activity
    val target = RuleBackedAndroidTestTarget(activityProvider = { host }, composeTestRule = composeRule)
    val collected = AndroidHybridHierarchyCollector.collect(host, target).trailblazeTree
      .flatten()
      .mapNotNull { (it.driverDetail as? DriverNodeDetail.Compose)?.accessibilityHintText }

    assertEquals(4, platform.size, "the platform tree should publish one hint per field, got $platform")
    assertEquals(platform, collected)
  }

  private fun TrailblazeNode.flatten(): List<TrailblazeNode> = listOf(this) + children.flatMap { it.flatten() }

  private companion object {
    const val CARD_NUMBER = "Card Number"
    const val EXPIRY_LABEL = "Expiration Date"
    const val EXPIRY_PLACEHOLDER = "MM/YY"
    const val NOTE_LABEL = "Note"
  }
}
