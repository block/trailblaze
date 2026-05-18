package xyz.block.trailblaze.api

/**
 * True iff this selector — or any of its hierarchy clauses ([TrailblazeNodeSelector.containsChild],
 * [TrailblazeNodeSelector.containsDescendants], [TrailblazeNodeSelector.childOf]) or spatial
 * anchors ([TrailblazeNodeSelector.below], [TrailblazeNodeSelector.above],
 * [TrailblazeNodeSelector.leftOf], [TrailblazeNodeSelector.rightOf]) — pins to at least one
 * field a human would recognize as "this is what the element is": text, content-description,
 * hint, resource id, test tag, ARIA name, etc. Class name alone (`android.widget.ScrollView`)
 * and state-only matchers (`isEnabled = true`) don't count on their own — they're too generic
 * or transient — but a class-only top-level paired with a child that has text counts, because
 * the disambiguating text on the child is what re-finds the element at replay. Same goes for
 * a positional anchor whose target carries semantic info: `class~"EditText", below { id~
 * "username_label" }` is stable because the labeled username field is what re-finds the
 * password EditText below it.
 *
 * Walking into hierarchy clauses matters for Compose-heavy screens where the tapped node is a
 * `Surface`/`Box` (`android.view.View` with no resource-id) whose inner `TextView` is the
 * human-recognizable label. Without this walk, the generator's perfectly-valid
 * `class~View, contains child class~TextView "WebView"` selector would be rejected as
 * "class-only" even though it round-trip-validates.
 *
 * Lives on the selector itself (rather than near a single recorder) so the desktop tab and the
 * wasm `/devices` viewer apply the same quality bar — historically the desktop recorder only
 * gated on `roundTripValid` and would silently accept a class-only selector that passed
 * round-trip on a sparse tree, while the web recorder rejected the same selector here. Same
 * gesture, two recordings — the deduplication of that policy is the reason this lives in
 * commonMain.
 *
 * Used by the recorder's selector path to decide between `tapOnElementBySelector` (rich
 * selector) and `tapOnPoint` (raw coordinates) when the generator returns a low-quality
 * fallback. Returning false intentionally degrades to coords rather than emit a selector that
 * wouldn't reliably resolve at replay.
 */
fun TrailblazeNodeSelector.hasSemanticIdentifier(): Boolean {
  if (driverMatchHasSemanticIdentifier()) return true
  // Recurse into hierarchy clauses — a class-only top-level paired with an identified child is
  // still a stable selector (the child's text/id is what disambiguates).
  if (containsChild?.hasSemanticIdentifier() == true) return true
  if (containsDescendants?.any { it.hasSemanticIdentifier() } == true) return true
  if (childOf?.hasSemanticIdentifier() == true) return true
  // Recurse into spatial anchors. A class-only top-level paired with a positional anchor that
  // carries a semantic identifier is also stable — e.g. `class~"EditText", below { id~
  // "username_label" }` is uniquely the password field beneath the labeled username field,
  // and the anchor's id is what makes the selector survive layout drift. Code review on
  // PR #3038 flagged this gap: the recorder cascade emits `below`/`above`/`leftOf`/`rightOf`
  // anchors for "the second EditText" pattern, and without this walk a perfectly-stable
  // selector would have been rejected as class-only.
  if (below?.hasSemanticIdentifier() == true) return true
  if (above?.hasSemanticIdentifier() == true) return true
  if (leftOf?.hasSemanticIdentifier() == true) return true
  if (rightOf?.hasSemanticIdentifier() == true) return true
  return false
}

/**
 * True iff *this* selector's [TrailblazeNodeSelector.driverMatch] (not its hierarchy clauses)
 * carries a semantic identifier. Split out from [hasSemanticIdentifier] so the recursion above
 * can walk children without re-entering the hierarchy-aware logic at each level.
 */
private fun TrailblazeNodeSelector.driverMatchHasSemanticIdentifier(): Boolean {
  val match = driverMatch ?: return false
  return when (match) {
    is DriverNodeMatch.AndroidAccessibility ->
      match.resourceIdRegex != null ||
        match.uniqueId != null ||
        match.composeTestTagRegex != null ||
        match.textRegex != null ||
        match.contentDescriptionRegex != null ||
        match.hintTextRegex != null ||
        match.labeledByTextRegex != null ||
        match.paneTitleRegex != null
    is DriverNodeMatch.AndroidMaestro ->
      match.resourceIdRegex != null ||
        match.textRegex != null ||
        match.accessibilityTextRegex != null ||
        match.hintTextRegex != null
    is DriverNodeMatch.Web ->
      match.ariaNameRegex != null ||
        match.ariaDescriptorRegex != null ||
        match.dataTestId != null ||
        match.cssSelector != null
    is DriverNodeMatch.Compose ->
      match.testTag != null ||
        match.textRegex != null ||
        match.editableTextRegex != null ||
        match.contentDescriptionRegex != null
    is DriverNodeMatch.IosMaestro ->
      match.textRegex != null ||
        match.resourceIdRegex != null ||
        match.accessibilityTextRegex != null ||
        match.hintTextRegex != null
    is DriverNodeMatch.IosAxe ->
      match.labelRegex != null ||
        match.valueRegex != null ||
        match.uniqueId != null ||
        match.titleRegex != null
  }
}
