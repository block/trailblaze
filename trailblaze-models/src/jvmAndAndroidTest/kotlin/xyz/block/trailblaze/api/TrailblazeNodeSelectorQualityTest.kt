package xyz.block.trailblaze.api

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit coverage for [hasSemanticIdentifier]. Two regressions this is guarding against:
 *
 * 1. **Class-only top-level + identified child** — the original symptom that drove the recursion
 *    landing in commonMain. A Compose-rendered `Surface` or `Box` wraps an inner `TextView` with
 *    text; the selector generator emits `class~"android.view.View", contains child
 *    class~"android.widget.TextView", "WebView"` (round-trip-valid). A non-recursive
 *    `hasSemanticIdentifier` would reject this as "class-only" and the recorder would
 *    silently fall back to `tapOnPoint`, losing selector stability. This test pins the
 *    recursive accept.
 *
 * 2. **Truly class-only with no disambiguating children** — when the tap lands on a node whose
 *    selector chain has no human-recognizable identifier anywhere (e.g. tap on the on-screen
 *    Android keyboard lands on the app's ScrollView underneath because the keyboard runs in a
 *    separate AccessibilityWindow we don't fetch), reject so the recorder falls back to
 *    `tapOnPoint`. Otherwise we'd record `class~"android.widget.ScrollView"`, which wouldn't
 *    reliably re-find the user's actual target at replay.
 *
 * Coverage across all six driver-match variants is deliberate — a future driver addition or
 * field rename would need to touch each `when` arm, and these tests make the affected paths
 * fail loudly.
 */
class TrailblazeNodeSelectorQualityTest {

  // ---------------------------------------------------------------------------
  // Top-level driver-match accepts
  // ---------------------------------------------------------------------------

  @Test
  fun `accepts AndroidAccessibility with textRegex`() {
    val sel = TrailblazeNodeSelector(
      androidAccessibility = DriverNodeMatch.AndroidAccessibility(textRegex = "Coffee"),
    )
    assertTrue(sel.hasSemanticIdentifier())
  }

  @Test
  fun `accepts AndroidAccessibility with resourceIdRegex`() {
    val sel = TrailblazeNodeSelector(
      androidAccessibility = DriverNodeMatch.AndroidAccessibility(resourceIdRegex = "com.example/btn_submit"),
    )
    assertTrue(sel.hasSemanticIdentifier())
  }

  @Test
  fun `accepts AndroidAccessibility with composeTestTagRegex`() {
    val sel = TrailblazeNodeSelector(
      androidAccessibility = DriverNodeMatch.AndroidAccessibility(composeTestTagRegex = "submit-button"),
    )
    assertTrue(sel.hasSemanticIdentifier())
  }

  @Test
  fun `accepts AndroidAccessibility with contentDescriptionRegex`() {
    val sel = TrailblazeNodeSelector(
      androidAccessibility = DriverNodeMatch.AndroidAccessibility(contentDescriptionRegex = "menu"),
    )
    assertTrue(sel.hasSemanticIdentifier())
  }

  @Test
  fun `accepts AndroidMaestro with textRegex`() {
    val sel = TrailblazeNodeSelector(
      androidMaestro = DriverNodeMatch.AndroidMaestro(textRegex = "OK"),
    )
    assertTrue(sel.hasSemanticIdentifier())
  }

  @Test
  fun `accepts Web with dataTestId`() {
    val sel = TrailblazeNodeSelector(
      web = DriverNodeMatch.Web(dataTestId = "submit"),
    )
    assertTrue(sel.hasSemanticIdentifier())
  }

  @Test
  fun `accepts Web with ariaNameRegex`() {
    val sel = TrailblazeNodeSelector(
      web = DriverNodeMatch.Web(ariaNameRegex = "Submit form"),
    )
    assertTrue(sel.hasSemanticIdentifier())
  }

  @Test
  fun `accepts Compose with testTag`() {
    val sel = TrailblazeNodeSelector(
      compose = DriverNodeMatch.Compose(testTag = "login_button"),
    )
    assertTrue(sel.hasSemanticIdentifier())
  }

  @Test
  fun `accepts IosMaestro with resourceIdRegex`() {
    val sel = TrailblazeNodeSelector(
      iosMaestro = DriverNodeMatch.IosMaestro(resourceIdRegex = "loginButton"),
    )
    assertTrue(sel.hasSemanticIdentifier())
  }

  @Test
  fun `accepts IosAxe with labelRegex`() {
    val sel = TrailblazeNodeSelector(
      iosAxe = DriverNodeMatch.IosAxe(labelRegex = "Submit"),
    )
    assertTrue(sel.hasSemanticIdentifier())
  }

  // ---------------------------------------------------------------------------
  // Top-level driver-match rejects
  // ---------------------------------------------------------------------------

  @Test
  fun `rejects null driverMatch with no hierarchy`() {
    val sel = TrailblazeNodeSelector()
    assertFalse(sel.hasSemanticIdentifier())
  }

  @Test
  fun `rejects class-only AndroidAccessibility with no hierarchy`() {
    // The driving case for this function — tapping on a `ScrollView` with no other matchable
    // fields produces a selector that round-trip-validates today but would silently re-find
    // a different ScrollView at replay on a different screen layout.
    val sel = TrailblazeNodeSelector(
      androidAccessibility = DriverNodeMatch.AndroidAccessibility(classNameRegex = "android.widget.ScrollView"),
    )
    assertFalse(sel.hasSemanticIdentifier())
  }

  @Test
  fun `rejects state-only AndroidAccessibility`() {
    // isEnabled / isClickable / isFocused / etc. don't disambiguate at replay time.
    val sel = TrailblazeNodeSelector(
      androidAccessibility = DriverNodeMatch.AndroidAccessibility(
        isEnabled = true,
        isClickable = true,
        isFocused = true,
      ),
    )
    assertFalse(sel.hasSemanticIdentifier())
  }

  @Test
  fun `rejects class-only AndroidMaestro`() {
    val sel = TrailblazeNodeSelector(
      androidMaestro = DriverNodeMatch.AndroidMaestro(classNameRegex = "android.widget.ScrollView"),
    )
    assertFalse(sel.hasSemanticIdentifier())
  }

  @Test
  fun `rejects role-only Compose`() {
    val sel = TrailblazeNodeSelector(
      compose = DriverNodeMatch.Compose(role = "Button"),
    )
    assertFalse(sel.hasSemanticIdentifier())
  }

  // ---------------------------------------------------------------------------
  // Hierarchy recursion
  // ---------------------------------------------------------------------------

  @Test
  fun `accepts class-only top-level when containsChild carries text`() {
    // The core "Compose-on-Android" regression — a `Surface` (which renders as
    // `android.view.View` in the accessibility tree, no resource-id or text) wraps a
    // `TextView` with the recognizable label "WebView". The selector cascade emits the
    // parent as the target and uses the child's text to disambiguate. Round-trip-valid,
    // genuinely stable for replay.
    val sel = TrailblazeNodeSelector(
      androidAccessibility = DriverNodeMatch.AndroidAccessibility(classNameRegex = "android.view.View"),
      containsChild = TrailblazeNodeSelector(
        androidAccessibility = DriverNodeMatch.AndroidAccessibility(
          classNameRegex = "android.widget.TextView",
          textRegex = "WebView",
        ),
      ),
    )
    assertTrue(sel.hasSemanticIdentifier())
  }

  @Test
  fun `accepts class-only top-level when containsDescendants carries text`() {
    val sel = TrailblazeNodeSelector(
      androidAccessibility = DriverNodeMatch.AndroidAccessibility(classNameRegex = "android.view.View"),
      containsDescendants = listOf(
        TrailblazeNodeSelector(
          androidAccessibility = DriverNodeMatch.AndroidAccessibility(textRegex = "Sign in"),
        ),
      ),
    )
    assertTrue(sel.hasSemanticIdentifier())
  }

  @Test
  fun `accepts class-only top-level when childOf carries an id`() {
    val sel = TrailblazeNodeSelector(
      androidAccessibility = DriverNodeMatch.AndroidAccessibility(classNameRegex = "android.view.View"),
      childOf = TrailblazeNodeSelector(
        androidAccessibility = DriverNodeMatch.AndroidAccessibility(resourceIdRegex = "com.example/main_panel"),
      ),
    )
    assertTrue(sel.hasSemanticIdentifier())
  }

  @Test
  fun `rejects class-only top-level when containsChild is also class-only`() {
    // A `View` with a `View` child gives the cascade nothing semantic to anchor on. The
    // recursive walk has to bottom out — otherwise a deeply nested chain of class-only
    // selectors would pass.
    val sel = TrailblazeNodeSelector(
      androidAccessibility = DriverNodeMatch.AndroidAccessibility(classNameRegex = "android.view.View"),
      containsChild = TrailblazeNodeSelector(
        androidAccessibility = DriverNodeMatch.AndroidAccessibility(classNameRegex = "android.view.View"),
      ),
    )
    assertFalse(sel.hasSemanticIdentifier())
  }

  @Test
  fun `accepts deeply nested containsChild chain when leaf carries text`() {
    // Three levels deep: View > View > TextView "Submit". The recursion must follow
    // containsChild all the way down to find the disambiguating leaf.
    val sel = TrailblazeNodeSelector(
      androidAccessibility = DriverNodeMatch.AndroidAccessibility(classNameRegex = "android.view.View"),
      containsChild = TrailblazeNodeSelector(
        androidAccessibility = DriverNodeMatch.AndroidAccessibility(classNameRegex = "android.view.ViewGroup"),
        containsChild = TrailblazeNodeSelector(
          androidAccessibility = DriverNodeMatch.AndroidAccessibility(textRegex = "Submit"),
        ),
      ),
    )
    assertTrue(sel.hasSemanticIdentifier())
  }

  @Test
  fun `rejects class-only at every level of a deeply nested chain`() {
    // The dual of the above: if the WHOLE chain is class-only with no text anywhere, the
    // selector still needs to be rejected.
    val sel = TrailblazeNodeSelector(
      androidAccessibility = DriverNodeMatch.AndroidAccessibility(classNameRegex = "android.view.View"),
      containsChild = TrailblazeNodeSelector(
        androidAccessibility = DriverNodeMatch.AndroidAccessibility(classNameRegex = "android.view.ViewGroup"),
        containsChild = TrailblazeNodeSelector(
          androidAccessibility = DriverNodeMatch.AndroidAccessibility(classNameRegex = "android.view.View"),
        ),
      ),
    )
    assertFalse(sel.hasSemanticIdentifier())
  }

  @Test
  fun `accepts when one of multiple containsDescendants carries text`() {
    // containsDescendants is a list — any one of them carrying a semantic identifier should
    // qualify the whole selector. (The semantic anchor doesn't have to be on every item.)
    val sel = TrailblazeNodeSelector(
      androidAccessibility = DriverNodeMatch.AndroidAccessibility(classNameRegex = "android.view.View"),
      containsDescendants = listOf(
        TrailblazeNodeSelector(
          androidAccessibility = DriverNodeMatch.AndroidAccessibility(classNameRegex = "android.view.View"),
        ),
        TrailblazeNodeSelector(
          androidAccessibility = DriverNodeMatch.AndroidAccessibility(textRegex = "Confirm"),
        ),
      ),
    )
    assertTrue(sel.hasSemanticIdentifier())
  }

  // ---------------------------------------------------------------------------
  // Spatial-anchor recursion — below / above / leftOf / rightOf
  // ---------------------------------------------------------------------------

  @Test
  fun `accepts class-only top-level when below anchor carries an id`() {
    // The pattern that drove the spatial-anchor recursion: a password EditText with no
    // resource-id of its own, but positioned below an EditText whose resource-id IS set
    // (e.g. `id~"username_label"`). The recorder cascade emits this as
    // `class~"EditText", below { id~"username_label" }` — perfectly stable for replay
    // because the labeled field anchors the positional lookup. Without the spatial
    // recursion this rejected as "class-only".
    val sel = TrailblazeNodeSelector(
      androidAccessibility = DriverNodeMatch.AndroidAccessibility(classNameRegex = "android.widget.EditText"),
      below = TrailblazeNodeSelector(
        androidAccessibility = DriverNodeMatch.AndroidAccessibility(resourceIdRegex = "com.example/username_label"),
      ),
    )
    assertTrue(sel.hasSemanticIdentifier())
  }

  @Test
  fun `accepts class-only top-level when above anchor carries text`() {
    val sel = TrailblazeNodeSelector(
      androidAccessibility = DriverNodeMatch.AndroidAccessibility(classNameRegex = "android.widget.EditText"),
      above = TrailblazeNodeSelector(
        androidAccessibility = DriverNodeMatch.AndroidAccessibility(textRegex = "Submit"),
      ),
    )
    assertTrue(sel.hasSemanticIdentifier())
  }

  @Test
  fun `accepts class-only top-level when leftOf anchor carries text`() {
    val sel = TrailblazeNodeSelector(
      androidAccessibility = DriverNodeMatch.AndroidAccessibility(classNameRegex = "android.widget.Button"),
      leftOf = TrailblazeNodeSelector(
        androidAccessibility = DriverNodeMatch.AndroidAccessibility(textRegex = "Cancel"),
      ),
    )
    assertTrue(sel.hasSemanticIdentifier())
  }

  @Test
  fun `accepts class-only top-level when rightOf anchor carries content description`() {
    val sel = TrailblazeNodeSelector(
      androidAccessibility = DriverNodeMatch.AndroidAccessibility(classNameRegex = "android.widget.ImageView"),
      rightOf = TrailblazeNodeSelector(
        androidAccessibility = DriverNodeMatch.AndroidAccessibility(contentDescriptionRegex = "Profile picture"),
      ),
    )
    assertTrue(sel.hasSemanticIdentifier())
  }

  @Test
  fun `rejects class-only top-level when spatial anchor is also class-only`() {
    // Dual of the hierarchy reject: a spatial anchor that's itself class-only doesn't
    // ground the selector either. Two anonymous EditTexts where one is "below" the other
    // still doesn't tell you which is which.
    val sel = TrailblazeNodeSelector(
      androidAccessibility = DriverNodeMatch.AndroidAccessibility(classNameRegex = "android.widget.EditText"),
      below = TrailblazeNodeSelector(
        androidAccessibility = DriverNodeMatch.AndroidAccessibility(classNameRegex = "android.widget.EditText"),
      ),
    )
    assertFalse(sel.hasSemanticIdentifier())
  }
}
