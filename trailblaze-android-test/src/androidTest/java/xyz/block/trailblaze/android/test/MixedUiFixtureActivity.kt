package xyz.block.trailblaze.android.test

import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.material3.Button as ComposeButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.verticalScrollAxisRange
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.util.Collections
import kotlin.test.assertTrue

/**
 * Deterministic mixed View + Compose screen for the ANDROID_TEST driver's on-device tests.
 *
 * The top half is classic Android Views (a labelled [TextView], an [EditText] and a [Button]);
 * the bottom half is a [ComposeView] hosting Compose semantics (a test-tagged text field, a
 * button, and a status label). Interacting with either half mutates state rendered in the OTHER
 * half where possible, so a passing test proves cross-backend dispatch rather than two isolated
 * silos.
 */
class MixedUiFixtureActivity : ComponentActivity() {

  /**
   * Whether the late-placement composable has been given a size yet. Hoisted out of the Compose
   * content because the thing that flips it is a classic View's click listener.
   */
  private var latePlacementRevealed by mutableStateOf(false)
  private var composeButtonCovered by mutableStateOf(false)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    root.setPadding(0, 160, 0, 0)

    val viewStatus =
      TextView(this).apply {
        id = VIEW_STATUS_ID
        text = VIEW_STATUS_INITIAL
      }
    val viewInput = EditText(this).apply { id = VIEW_INPUT_ID; hint = VIEW_INPUT_HINT }
    val viewButton =
      Button(this).apply {
        id = VIEW_BUTTON_ID
        text = VIEW_BUTTON_LABEL
        setOnClickListener { viewStatus.text = VIEW_STATUS_CLICKED }
      }
    root.addView(viewStatus)
    root.addView(viewInput)
    root.addView(viewButton)

    // Two buttons with identical text, telling apart only by the row they live in. Nothing about
    // the buttons themselves distinguishes them, so a selector has to reach for structure and the
    // action has to land on the exact node that matched.
    root.addView(
      duplicateRow(VIEW_ALPHA_ROW_TAG) { viewStatus.text = VIEW_STATUS_ALPHA },
    )
    root.addView(
      duplicateRow(VIEW_BETA_ROW_TAG) { viewStatus.text = VIEW_STATUS_BETA },
    )

    // A clickable row whose label is a plain TextView: the thing worth naming in a trail is the
    // label, and the thing that handles the tap is its parent.
    root.addView(
      LinearLayout(this).apply {
        tag = VIEW_CLICKABLE_ROW_TAG
        isClickable = true
        setOnClickListener { viewStatus.text = VIEW_STATUS_ROW }
        addView(
          TextView(this@MixedUiFixtureActivity).apply { text = VIEW_ROW_LABEL },
        )
      },
    )

    // A non-clickable row with TWO clickable children, equally close. Relocating UP to the thing
    // that can take a tap is well-defined — there is one chain of ancestors. Relocating DOWN into
    // one of two equally close descendants is a coin flip, and this row is where the driver has to
    // refuse rather than pick one and report success.
    root.addView(
      LinearLayout(this).apply {
        tag = VIEW_AMBIGUOUS_ROW_TAG
        addView(
          Button(this@MixedUiFixtureActivity).apply {
            text = VIEW_AMBIGUOUS_LEFT
            setOnClickListener { viewStatus.text = VIEW_STATUS_AMBIGUOUS }
          },
        )
        addView(
          Button(this@MixedUiFixtureActivity).apply {
            text = VIEW_AMBIGUOUS_RIGHT
            setOnClickListener { viewStatus.text = VIEW_STATUS_AMBIGUOUS }
          },
        )
      },
    )

    // A row with two clickable children, one of them GONE. The hidden one was never part of the
    // tree a selector could match, so relocation must not count it: as a candidate it either takes
    // the tap or turns one real target into two and refuses to choose.
    root.addView(
      LinearLayout(this).apply {
        tag = VIEW_HIDDEN_SIBLING_ROW_TAG
        addView(
          Button(this@MixedUiFixtureActivity).apply {
            text = VIEW_HIDDEN_ACTION
            visibility = View.GONE
            setOnClickListener { viewStatus.text = VIEW_STATUS_HIDDEN_ACTION }
          },
        )
        addView(
          Button(this@MixedUiFixtureActivity).apply {
            text = VIEW_VISIBLE_ACTION
            setOnClickListener { viewStatus.text = VIEW_STATUS_VISIBLE_ACTION }
          },
        )
      },
    )

    // Schedules the late-placement composable to get its size well after the tap returns. The
    // delay is posted to the main looper rather than driven by a Compose `delay`, because the
    // Compose test clock auto-advances and would place the node during the very next
    // `waitForIdle` — the resolver's wait for bounds would then never be exercised.
    root.addView(
      Button(this).apply {
        text = VIEW_REVEAL_LABEL
        setOnClickListener { postDelayed({ latePlacementRevealed = true }, LATE_PLACEMENT_DELAY_MS) }
      },
    )

    val composeView =
      ComposeView(this).apply {
        setContent {
          var composeStatus by remember { mutableStateOf(COMPOSE_STATUS_INITIAL) }
          var composeText by remember { mutableStateOf("") }
          Column(modifier = Modifier.padding(16.dp)) {
            Text(text = composeStatus, modifier = Modifier.testTag(COMPOSE_STATUS_TAG))
            TextField(
              value = composeText,
              onValueChange = { composeText = it },
              modifier = Modifier.testTag(COMPOSE_INPUT_TAG),
            )
            // A masked date field, as a sign-up's date of birth is: empty, its text reads as the
            // mask itself, and it keeps only up to eight digits, so the mask plus typed digits is
            // an edit it refuses outright.
            var maskedDigits by remember { mutableStateOf("") }
            // A field already holding text that keeps only its first few characters, so a pasted
            // value is clipped. It takes part of the edit, and what it held before has to survive
            // the driver's retry. (Not in a Row beside the masked field: laid out that way, a tap on
            // the Compose input above, even a real one, left nothing focused.)
            var clipped by remember { mutableStateOf(CLIPPED_PREFILL) }
            BasicTextField(
              value = maskedDigits,
              onValueChange = { typed -> if (typed.length <= 8 && typed.all(Char::isDigit)) maskedDigits = typed },
              visualTransformation = { text ->
                if (text.isEmpty()) {
                  TransformedText(AnnotatedString(MASKED_DATE_MASK), EmptyMaskOffsets)
                } else {
                  TransformedText(text, OffsetMapping.Identity)
                }
              },
              modifier = Modifier.testTag(MASKED_DATE_TAG),
            )
            Text("$MASKED_DATE_STATUS_PREFIX$maskedDigits")
            BasicTextField(
              value = clipped,
              onValueChange = { clipped = it.take(CLIPPED_MAX_LENGTH) },
              modifier = Modifier.testTag(CLIPPED_TAG),
            )
            Text("$CLIPPED_STATUS_PREFIX$clipped")
            // A search field that names itself by its description and shows a placeholder while
            // empty. The platform's accessibility tree publishes that description on a child node
            // of its own, so a recording names the field by it as text.
            var searchQuery by remember { mutableStateOf("") }
            BasicTextField(
              value = searchQuery,
              onValueChange = { searchQuery = it },
              modifier = Modifier.semantics { contentDescription = SEARCH_DESCRIPTION },
              decorationBox = { inner ->
                Box {
                  if (searchQuery.isEmpty()) Text(SEARCH_PLACEHOLDER)
                  inner()
                }
              },
            )
            Text("$SEARCH_STATUS_PREFIX$searchQuery")
            Box {
              ComposeButton(
                onClick = { composeStatus = COMPOSE_STATUS_CLICKED },
                modifier = Modifier.testTag(COMPOSE_BUTTON_TAG),
              ) {
                Text(COMPOSE_BUTTON_LABEL)
              }
              // A node drawn over the button with no handler of its own, as some apps draw a decorative
              // overlay over a button. A touch still reaches the button; a
              // hit test over semantics bounds alone calls it covered.
              if (composeButtonCovered) {
                Box(modifier = Modifier.matchParentSize().testTag(COMPOSE_COVER_TAG))
              }
            }
            // Classic Views embedded back INSIDE the Compose content. Compose semantics stop at
            // this boundary — the host reports the interop node and nothing beneath it — so these
            // exist only in the View tree, under a Compose host. An app that builds its screens
            // this way (a large mixed app usually does) is invisible to a collector that treats a
            // Compose host as the end of the View walk.
            AndroidView(
              factory = { context ->
                LinearLayout(context).apply {
                  orientation = LinearLayout.VERTICAL
                  addView(
                    Button(context).apply {
                      text = EMBEDDED_VIEW_BUTTON_LABEL
                      // Flips COMPOSE state, so a passing tap proves the driver acted on the real
                      // embedded View rather than finding some same-named node elsewhere.
                      setOnClickListener { composeStatus = COMPOSE_STATUS_EMBEDDED }
                    },
                  )
                  // A whole further ComposeView parked BENEATH the interop boundary: Compose in a
                  // View in Compose. Its semantics live in their own root, reachable only by a
                  // root walk that keeps descending through a Compose host — a walk that stops at
                  // the first host loses this node and only this node.
                  addView(
                    ComposeView(context).apply {
                      setContent {
                        Text(
                          text = NESTED_COMPOSE_LABEL,
                          modifier = Modifier.testTag(NESTED_COMPOSE_TAG),
                        )
                      }
                    },
                  )
                }
              },
            )
            // Two nodes that are in the semantics tree from the first frame but occupy no screen
            // space. Every property a selector can read is already correct on both; only their
            // bounds say whether acting on them would do anything.
            Box(
              modifier = Modifier
                .size(if (latePlacementRevealed) 48.dp else 0.dp)
                .testTag(COMPOSE_LATE_TAG),
            )
            Box(modifier = Modifier.size(0.dp).testTag(COMPOSE_NEVER_PLACED_TAG))
            // A node whose semantics VALUE is a lambda, so reading this tree runs fixture code and
            // the fixture can report which thread ran it. `AndroidComposeHierarchyCollector`
            // invokes a scroll range's `value` for every node carrying one, so any collector that
            // maps this node trips the probe.
            Box(
              modifier = Modifier
                .size(0.dp)
                .testTag(COMPOSE_READ_PROBE_TAG)
                .semantics {
                  verticalScrollAxisRange = ScrollAxisRange(
                    value = {
                      SemanticsReadProbe.record()
                      0f
                    },
                    maxValue = { 0f },
                  )
                },
            )
          }
        }
      }
    // WRAP_CONTENT height, so the View rows above always get their space. A MATCH_PARENT sibling
    // can push them off screen, and an off-screen view is dropped from the hierarchy entirely.
    root.addView(
      composeView,
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
    )

    setContentView(root)
  }

  /**
   * Reveals the late-placement node NOW, from any thread.
   *
   * The reveal button's [LATE_PLACEMENT_DELAY_MS] is a clock, and a test that must prove ORDER
   * (this capture finished while that run was still waiting) cannot rest on one — a loaded
   * emulator turns any margin into a coin flip. This hook lets the test sequence the reveal on
   * the event itself.
   */
  fun revealLateContentNow() {
    runOnUiThread { latePlacementRevealed = true }
  }

  /** Draws a handler-less node over the Compose button, from any thread. */
  fun coverComposeButtonNow() {
    runOnUiThread { composeButtonCovered = true }
  }

  private fun duplicateRow(rowTag: String, onClick: () -> Unit): LinearLayout =
    LinearLayout(this).apply {
      tag = rowTag
      addView(
        Button(this@MixedUiFixtureActivity).apply {
          text = VIEW_DUPLICATE_LABEL
          setOnClickListener { onClick() }
        },
      )
    }

  companion object {
    // A declared resource id, not a generated one: `AccessibilityHierarchyParityOnDeviceTest`
    // needs one element whose exact resource NAME both hierarchies can be asked for, and
    // `generateViewId()` produces ids that `Resources.getResourceName` cannot name at all.
    val VIEW_STATUS_ID = xyz.block.trailblaze.android.test.test.R.id.view_status
    val VIEW_INPUT_ID = android.view.View.generateViewId()
    const val VIEW_INPUT_HINT = "View input"
    val VIEW_BUTTON_ID = android.view.View.generateViewId()

    const val VIEW_STATUS_INITIAL = "View status: ready"
    const val VIEW_STATUS_CLICKED = "View status: clicked"
    const val VIEW_BUTTON_LABEL = "View Button"

    const val VIEW_DUPLICATE_LABEL = "Pick Me"
    const val VIEW_ALPHA_ROW_TAG = "row_alpha"
    const val VIEW_BETA_ROW_TAG = "row_beta"
    const val VIEW_STATUS_ALPHA = "View status: alpha"
    const val VIEW_STATUS_BETA = "View status: beta"

    const val VIEW_AMBIGUOUS_ROW_TAG = "ambiguous_row"
    const val VIEW_AMBIGUOUS_LEFT = "Left"
    const val VIEW_AMBIGUOUS_RIGHT = "Right"
    const val VIEW_STATUS_AMBIGUOUS = "View status: ambiguous"

    const val VIEW_HIDDEN_SIBLING_ROW_TAG = "hidden_sibling_row"
    const val VIEW_HIDDEN_ACTION = "Hidden Action"
    const val VIEW_VISIBLE_ACTION = "Visible Action"
    const val VIEW_STATUS_HIDDEN_ACTION = "View status: hidden action"
    const val VIEW_STATUS_VISIBLE_ACTION = "View status: visible action"

    const val VIEW_CLICKABLE_ROW_TAG = "clickable_row"
    const val VIEW_ROW_LABEL = "Row label"
    const val VIEW_STATUS_ROW = "View status: row"

    const val COMPOSE_STATUS_TAG = "compose_status"
    const val COMPOSE_INPUT_TAG = "compose_input"
    const val COMPOSE_BUTTON_TAG = "compose_button"
    const val COMPOSE_COVER_TAG = "compose_cover"
    const val MASKED_DATE_TAG = "masked_date"
    const val MASKED_DATE_MASK = "MM/DD/YYYY"
    const val MASKED_DATE_STATUS_PREFIX = "Masked date: "
    const val CLIPPED_TAG = "clipped_field"
    const val CLIPPED_PREFILL = "abc"
    const val CLIPPED_MAX_LENGTH = 5
    const val CLIPPED_STATUS_PREFIX = "Clipped: "
    const val SEARCH_DESCRIPTION = "Search for people"
    const val SEARCH_PLACEHOLDER = "Name or email"
    const val SEARCH_STATUS_PREFIX = "Searched: "
    const val COMPOSE_STATUS_INITIAL = "Compose status: ready"
    const val COMPOSE_STATUS_CLICKED = "Compose status: clicked"
    const val COMPOSE_BUTTON_LABEL = "Compose Button"

    const val EMBEDDED_VIEW_BUTTON_LABEL = "Embedded View Button"
    const val COMPOSE_STATUS_EMBEDDED = "Compose status: embedded"
    const val NESTED_COMPOSE_TAG = "nested_compose"
    const val NESTED_COMPOSE_LABEL = "Nested Compose"

    const val VIEW_REVEAL_LABEL = "Reveal Later"
    const val COMPOSE_LATE_TAG = "compose_late"
    const val COMPOSE_NEVER_PLACED_TAG = "compose_never_placed"
    const val COMPOSE_READ_PROBE_TAG = "compose_read_probe"

    /**
     * Long enough that the tap's own `waitForIdle` cannot cover it — a posted message does not
     * keep the looper busy — and far short of the resolver's 8s bound, so the wait is what the
     * test measures rather than the timeout.
     */
    const val LATE_PLACEMENT_DELAY_MS = 1_500L
  }
}

/**
 * Which threads have read [MixedUiFixtureActivity]'s probe node's semantics.
 *
 * Compose owns its semantics from the UI thread, and a read taken anywhere else can interleave with
 * a recomposition. Nothing about a half-updated tree throws, so the only way to state that contract
 * as an assertion is to have the tree report who read it — hence a semantics value that is a lambda.
 */
object SemanticsReadProbe {
  private val threads = Collections.synchronizedList(mutableListOf<Thread>())

  fun record() {
    threads += Thread.currentThread()
  }

  fun reset() {
    threads.clear()
  }

  /** A copy: an assertion must not iterate a list a concurrent capture is still appending to. */
  fun readingThreads(): List<Thread> = synchronized(threads) { threads.toList() }

  /**
   * Asserts that the probe HAS been read — a probe nobody read proves nothing — and that every
   * read so far came from the UI thread. Shared by every test that states the thread-affinity
   * contract, so the two halves of the assertion cannot drift apart.
   */
  fun assertReadOnlyOnUiThread() {
    val readers = readingThreads()
    assertTrue(
      readers.isNotEmpty(),
      "The probe node's semantics were never read, so nothing here proves anything — is it still " +
        "in the fixture's Compose content, and does the collector still read a scroll range?",
    )
    assertTrue(
      readers.all { it === Looper.getMainLooper().thread },
      "Compose semantics were read off the UI thread by ${readers.map(Thread::getName).distinct()}",
    )
  }
}

/** The empty field's offsets: every caret position in the mask is position 0 of the empty value. */
private object EmptyMaskOffsets : OffsetMapping {
  override fun originalToTransformed(offset: Int): Int = 0

  override fun transformedToOriginal(offset: Int): Int = 0
}
