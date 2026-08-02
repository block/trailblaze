package xyz.block.trailblaze.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class TargetTypeGuardTest {

  // --- The motivating shape: a search box echoing the query ---

  @Test
  fun `bare text selector resolving onto a search field is flagged`() {
    val warning = TargetTypeGuard.assessUnrequestedTextInput(bareText("Pizza"), searchField())
    assertNotNull(warning, "case-insensitive bare-text match landed on the box echoing the query")
    assertTrue(warning.contains("text input"), warning)
  }

  @Test
  fun `the warning names the field the match came from`() {
    val warning = TargetTypeGuard.assessUnrequestedTextInput(bareText("Pizza"), searchField())
    assertNotNull(warning)
    assertTrue(warning.contains("`text`"), "provenance makes the warning actionable: $warning")
  }

  @Test
  fun `the same selector on a non-editable result row is not flagged`() {
    val row = node(DriverNodeDetail.AndroidAccessibility(text = "Pizza", isClickable = true))
    assertNull(TargetTypeGuard.assessUnrequestedTextInput(bareText("Pizza"), row))
  }

  @Test
  fun `a selector that deliberately targets an input is not flagged`() {
    for (deliberate in listOf(
      TrailblazeNodeSelector(DriverNodeMatch.AndroidAccessibility(textRegex = "Pizza", isEditable = true)),
      TrailblazeNodeSelector(DriverNodeMatch.AndroidAccessibility(textRegex = "Pizza", classNameRegex = ".*EditText")),
      TrailblazeNodeSelector(DriverNodeMatch.AndroidAccessibility(textRegex = "Pizza", resourceIdRegex = ".*search")),
      TrailblazeNodeSelector(DriverNodeMatch.AndroidAccessibility(textRegex = "Pizza", isPassword = true)),
    )) {
      assertNull(
        TargetTypeGuard.assessUnrequestedTextInput(deliberate, searchField()),
        "selector pinned something input-specific: ${deliberate.description()}",
      )
    }
  }

  // --- Ambiguous match sets: the shape a single resolved node cannot show ---

  @Test
  fun `a match set mixing a search field and a result row is flagged`() {
    val nodes = listOf(
      searchField(),
      node(DriverNodeDetail.AndroidAccessibility(text = "Pizza", isClickable = true)),
    )
    val warning = TargetTypeGuard.assessAmbiguousTextInput(bareText("Pizza"), nodes)
    assertNotNull(warning)
    assertTrue(warning.contains("ambiguous"), warning)
  }

  @Test
  fun `a match set of only non-inputs is not flagged`() {
    val nodes = listOf(
      node(DriverNodeDetail.AndroidAccessibility(text = "Pizza", isClickable = true)),
      node(DriverNodeDetail.AndroidAccessibility(text = "Pizza", isClickable = true)),
    )
    assertNull(TargetTypeGuard.assessAmbiguousTextInput(bareText("Pizza"), nodes))
  }

  @Test
  fun `a match set of only inputs is not flagged as ambiguous`() {
    // Nothing to disambiguate *between* — the single-match check is the right reporter here.
    assertNull(
      TargetTypeGuard.assessAmbiguousTextInput(bareText("Pizza"), listOf(searchField(), searchField())),
    )
  }

  // --- Provenance mirrors each resolveText() chain exactly ---

  @Test
  fun `android accessibility provenance follows text then hintText then contentDescription`() {
    assertEquals("text", provenance(DriverNodeDetail.AndroidAccessibility(text = "a", hintText = "b", contentDescription = "c")))
    assertEquals("hintText", provenance(DriverNodeDetail.AndroidAccessibility(hintText = "b", contentDescription = "c")))
    assertEquals("contentDescription", provenance(DriverNodeDetail.AndroidAccessibility(contentDescription = "c")))
    assertNull(provenance(DriverNodeDetail.AndroidAccessibility()))
  }

  /**
   * `Compose.resolveText()` is `editableText ?: text ?: contentDescription` — the editable field's
   * live contents lead the chain, so on Compose a bare `textRegex` matches what the user typed
   * before it matches any label. This check's failure shape, by construction.
   */
  @Test
  fun `compose provenance puts editableText first`() {
    assertEquals("editableText", provenance(DriverNodeDetail.Compose(editableText = "pizza", text = "Search")))
    assertEquals("text", provenance(DriverNodeDetail.Compose(text = "Search")))
  }

  /**
   * `IosAxe.resolveText()` is the only blank-skipping chain. A uniform "first non-null" helper
   * would report `label` here, which is wrong and would point a reader at an empty field.
   */
  @Test
  fun `iosAxe provenance skips blank values unlike every other chain`() {
    assertEquals("value", provenance(DriverNodeDetail.IosAxe(label = "", value = "pizza", title = "t")))
    assertEquals("title", provenance(DriverNodeDetail.IosAxe(label = "", value = "   ", title = "t")))
    assertEquals("label", provenance(DriverNodeDetail.IosAxe(label = "Pizza", value = "v")))
  }

  @Test
  fun `the null-coalescing chains do NOT skip blanks`() {
    // `text ?: hintText` — an empty `text` still wins, so reporting `hintText` would be a lie.
    assertEquals("text", provenance(DriverNodeDetail.AndroidAccessibility(text = "", hintText = "Search")))
    assertEquals("text", provenance(DriverNodeDetail.IosMaestro(text = "", hintText = "Search")))
  }

  // --- Provenance and resolveText() must not drift apart ---

  /**
   * The per-chain tests above pin today's order, but none of them fails if a `resolveText()` chain
   * is later **reordered**: [TargetTypeGuard.textMatchProvenance] would go on reporting the old
   * position, and the warning would name a field the match did not come from. A warning pointing
   * at the wrong property is worse than no warning — it sends the reader to a field that is not
   * why their selector matched. So tie the two together here.
   *
   * The invariant, which holds regardless of what order either chain is in: the field the
   * provenance names must hold exactly what that driver's own `resolveText()` returned.
   *
   * Fixtures give every field a distinct value, because equal values could not tell a reordered
   * chain from a correct one.
   */
  @Test
  fun `the field named by the provenance holds exactly what resolveText returned`() {
    for (detail in RESOLVE_TEXT_FIXTURES) {
      val named = TargetTypeGuard.textMatchProvenance(detail)
      val resolved = detail.resolveTextPerDriver()
      if (named == null) {
        assertNull(resolved, "nothing was named as provenance, so nothing should resolve: $detail")
        continue
      }
      assertEquals(
        resolved,
        detail.readStringProperty(named),
        "provenance named `$named`, but resolveText() returned a different field's value — the " +
          "provenance chain and the resolveText() chain have drifted apart for $detail",
      )
    }
  }

  @Test
  fun `the drift fixtures cover every chain the provenance knows about`() {
    assertEquals(
      5,
      RESOLVE_TEXT_FIXTURES.map { it::class }.distinct().size,
      "one fixture group per resolveText() chain — a dropped driver silently stops being pinned",
    )
  }

  // --- Drivers outside the rollout stay silent rather than guess ---

  @Test
  fun `a web node is never reported as a text input`() {
    val web = node(DriverNodeDetail.Web(ariaRole = "textbox", ariaName = "Pizza"))
    assertNull(TargetTypeGuard.assessUnrequestedTextInput(bareText("Pizza"), web))
  }

  // --- fixtures ---

  private fun provenance(detail: DriverNodeDetail) = TargetTypeGuard.textMatchProvenance(detail)

  private fun bareText(pattern: String) =
    TrailblazeNodeSelector(DriverNodeMatch.AndroidAccessibility(textRegex = pattern))

  private fun searchField() = node(
    DriverNodeDetail.AndroidAccessibility(
      className = "android.widget.EditText",
      text = "pizza",
      isEditable = true,
    ),
  )

  private var nextId = 1L

  private fun node(detail: DriverNodeDetail) = TrailblazeNode(
    nodeId = nextId++,
    bounds = TrailblazeNode.Bounds(0, 200, 1080, 280),
    driverDetail = detail,
  )

  /** `resolveText()` is declared per driver rather than on the sealed interface, so dispatch here. */
  private fun DriverNodeDetail.resolveTextPerDriver(): String? = when (this) {
    is DriverNodeDetail.AndroidAccessibility -> resolveText()
    is DriverNodeDetail.AndroidMaestro -> resolveText()
    is DriverNodeDetail.IosMaestro -> resolveText()
    is DriverNodeDetail.IosAxe -> resolveText()
    is DriverNodeDetail.Compose -> resolveText()
    else -> null
  }

  /**
   * Reads the property the provenance named, *by that name*, so this test carries no second
   * name-to-field table of its own that could drift alongside the thing it is checking. A renamed
   * field fails loudly here, which is correct: the guard emits the name as a literal string, so a
   * rename makes its warning wrong too.
   */
  private fun DriverNodeDetail.readStringProperty(property: String): String? {
    val getter = "get" + property.replaceFirstChar { it.uppercaseChar() }
    return try {
      javaClass.getMethod(getter).invoke(this) as String?
    } catch (_: NoSuchMethodException) {
      fail("provenance named `$property`, but ${javaClass.simpleName} has no such property")
    }
  }

  private companion object {
    /**
     * Every position of all five chains, plus the edge shape the chains disagree on: `IosAxe`
     * skips blank values, every other chain plain null-coalesces so a blank still wins.
     */
    val RESOLVE_TEXT_FIXTURES = listOf(
      // text ?: hintText ?: contentDescription
      DriverNodeDetail.AndroidAccessibility(text = "aa-text", hintText = "aa-hint", contentDescription = "aa-cd"),
      DriverNodeDetail.AndroidAccessibility(hintText = "aa-hint", contentDescription = "aa-cd"),
      DriverNodeDetail.AndroidAccessibility(contentDescription = "aa-cd"),
      DriverNodeDetail.AndroidAccessibility(text = "", hintText = "aa-hint"),
      DriverNodeDetail.AndroidAccessibility(),
      // text ?: hintText ?: accessibilityText
      DriverNodeDetail.AndroidMaestro(text = "am-text", hintText = "am-hint", accessibilityText = "am-a11y"),
      DriverNodeDetail.AndroidMaestro(hintText = "am-hint", accessibilityText = "am-a11y"),
      DriverNodeDetail.AndroidMaestro(accessibilityText = "am-a11y"),
      DriverNodeDetail.AndroidMaestro(text = "", hintText = "am-hint"),
      // text ?: hintText ?: accessibilityText
      DriverNodeDetail.IosMaestro(text = "im-text", hintText = "im-hint", accessibilityText = "im-a11y"),
      DriverNodeDetail.IosMaestro(hintText = "im-hint", accessibilityText = "im-a11y"),
      DriverNodeDetail.IosMaestro(accessibilityText = "im-a11y"),
      DriverNodeDetail.IosMaestro(text = "", hintText = "im-hint"),
      // label ?: value ?: title, each gated on isNotBlank()
      DriverNodeDetail.IosAxe(label = "ax-label", value = "ax-value", title = "ax-title"),
      DriverNodeDetail.IosAxe(label = "", value = "ax-value", title = "ax-title"),
      DriverNodeDetail.IosAxe(label = "", value = "   ", title = "ax-title"),
      DriverNodeDetail.IosAxe(),
      // editableText ?: text ?: contentDescription — the editable field's live contents lead
      DriverNodeDetail.Compose(editableText = "co-editable", text = "co-text", contentDescription = "co-cd"),
      DriverNodeDetail.Compose(text = "co-text", contentDescription = "co-cd"),
      DriverNodeDetail.Compose(contentDescription = "co-cd"),
      DriverNodeDetail.Compose(),
    )
  }
}
