package xyz.block.trailblaze.playwright

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * Pure unit tests for [PlaywrightAriaSnapshot.buildAiRefsByRoleName] and
 * [PlaywrightAriaSnapshot.roleNameCorrelationKey] — the AI-mode ref correlation mechanism
 * that replaced role/name/nth DOM matching in `PlaywrightScreenState`'s bounds/visibility
 * enrichment (https://github.com/block/trailblaze/issues/199 follow-up).
 *
 * These tests exercise the parsing logic without launching a browser; real-Chromium
 * coverage of the end-to-end ref resolution lives in `PlaywrightScreenStateBoundsTest`.
 */
class PlaywrightAriaSnapshotAiRefTest {

  @Test
  fun `blank yaml returns an empty map`() {
    assertEquals(emptyMap(), PlaywrightAriaSnapshot.buildAiRefsByRoleName(""))
    assertEquals(emptyMap(), PlaywrightAriaSnapshot.buildAiRefsByRoleName("   \n  "))
  }

  @Test
  fun `extracts one ref per named element in pre-order`() {
    val yaml = """
      - document [ref=e1]:
        - navigation "Main" [ref=e3]:
          - link "Home" [ref=e4] [cursor=pointer]:
            - /url: "#home"
    """.trimIndent()

    val refs = PlaywrightAriaSnapshot.buildAiRefsByRoleName(yaml)

    assertEquals(listOf("e1"), refs["document"])
    assertEquals(listOf("e3"), refs[PlaywrightAriaSnapshot.roleNameCorrelationKey("navigation", "Main")])
    assertEquals(listOf("e4"), refs[PlaywrightAriaSnapshot.roleNameCorrelationKey("link", "Home")])
  }

  @Test
  fun `duplicate role name pairs accumulate refs in document order for nth lookup`() {
    val yaml = """
      - document [ref=e1]:
        - link "Hardware" [ref=e2]:
        - link "Hardware" [ref=e3]:
    """.trimIndent()

    val refs = PlaywrightAriaSnapshot.buildAiRefsByRoleName(yaml)
    val key = PlaywrightAriaSnapshot.roleNameCorrelationKey("link", "Hardware")

    assertEquals(listOf("e2", "e3"), refs[key])
  }

  @Test
  fun `anonymous generic nodes are excluded from the correlation map`() {
    // AI mode inserts a synthetic unnamed "generic" wrapper around multiple top-level
    // children that DEFAULT mode collapses away — matching it by (role, name, nth) can't
    // tell a real anonymous generic wrapper apart from a synthetic one, so it must never
    // appear in the map (callers fall back to per-node locator resolution for it instead).
    val yaml = """
      - document [ref=e1]:
        - generic [active] [ref=e2]:
          - navigation "Main" [ref=e3]:
    """.trimIndent()

    val refs = PlaywrightAriaSnapshot.buildAiRefsByRoleName(yaml)

    assertNull(refs["generic"])
    assertEquals(listOf("e3"), refs[PlaywrightAriaSnapshot.roleNameCorrelationKey("navigation", "Main")])
  }

  @Test
  fun `named generic elements are still correlated`() {
    // Only ANONYMOUS generic nodes (no name) are excluded -- a named one (e.g. a div with
    // aria-label) is a real, addressable element and should resolve normally.
    val yaml = """
      - document [ref=e1]:
        - generic "Card" [ref=e2]:
    """.trimIndent()

    val refs = PlaywrightAriaSnapshot.buildAiRefsByRoleName(yaml)

    assertEquals(listOf("e2"), refs[PlaywrightAriaSnapshot.roleNameCorrelationKey("generic", "Card")])
  }

  @Test
  fun `lines without a ref annotation are ignored`() {
    val yaml = """
      - document:
        - button "Submit" [ref=e2]:
    """.trimIndent()

    val refs = PlaywrightAriaSnapshot.buildAiRefsByRoleName(yaml)

    assertNull(refs["document"])
    assertEquals(listOf("e2"), refs[PlaywrightAriaSnapshot.roleNameCorrelationKey("button", "Submit")])
  }

  @Test
  fun `paragraph and listitem colon-style lines parse correctly despite the bracket before the colon`() {
    // Regression pin: `paragraph [ref=e8]: some text` used to break COLON_ROLE_PATTERN
    // (the bracket sits between the role and the colon), falling through to the
    // "treat the whole line as text" fallback and corrupting both role and name.
    val yaml = """
      - document [ref=e1]:
        - paragraph [ref=e8]: Some paragraph text.
        - list [ref=e9]:
          - listitem [ref=e10]: Item one
    """.trimIndent()

    val refs = PlaywrightAriaSnapshot.buildAiRefsByRoleName(yaml)

    assertEquals(
      listOf("e8"),
      refs[PlaywrightAriaSnapshot.roleNameCorrelationKey("paragraph", "Some paragraph text.")],
    )
    assertEquals(
      listOf("e10"),
      refs[PlaywrightAriaSnapshot.roleNameCorrelationKey("listitem", "Item one")],
    )
  }

  @Test
  fun `roleNameCorrelationKey distinguishes role-only from same role with a name`() {
    val roleOnly = PlaywrightAriaSnapshot.roleNameCorrelationKey("navigation", null)
    val roleWithName = PlaywrightAriaSnapshot.roleNameCorrelationKey("navigation", "Main")

    assertNotEquals(roleOnly, roleWithName)
  }

  @Test
  fun `a literal bracket-shaped accessible name is preserved, not stripped as an AI annotation`() {
    // Regression pin: a naive "strip [ref=...]/[cursor=...]/[active] before parsing" pass
    // would also strip literal bracket text that's part of a REAL accessible name (e.g. a
    // status badge whose name is literally "Status [active]"), silently renaming or
    // dropping the element. QUOTED_ROLE_PATTERN must be tried on the raw line so its
    // trailing `.*$` absorbs the AI-mode annotation without touching the quoted name.
    val yaml = """
      - document [ref=e1]:
        - button "Status [active]" [ref=e2] [cursor=pointer]:
    """.trimIndent()

    val refs = PlaywrightAriaSnapshot.buildAiRefsByRoleName(yaml)

    assertEquals(
      listOf("e2"),
      refs[PlaywrightAriaSnapshot.roleNameCorrelationKey("button", "Status [active]")],
      "The literal \"[active]\" in the accessible name must survive, not be stripped",
    )
  }

  @Test
  fun `non-ref bracket state annotations before the colon do not break parsing`() {
    // Regression pin: AI mode can emit other ARIA state annotations (not just
    // ref/cursor/active) between the role and the colon on an unquoted line, e.g.
    // `[checked]`, `[expanded]`, `[disabled]`. COLON_ROLE_PATTERN/CONTAINER_ROLE_PATTERN
    // must tolerate any bracket group there, not just the three AI-only ones.
    val yaml = """
      - document [ref=e1]:
        - treeitem [expanded] [ref=e5]: Folder contents
        - checkbox [disabled] [checked] [ref=e6]: Accept terms
    """.trimIndent()

    val refs = PlaywrightAriaSnapshot.buildAiRefsByRoleName(yaml)

    assertEquals(
      listOf("e5"),
      refs[PlaywrightAriaSnapshot.roleNameCorrelationKey("treeitem", "Folder contents")],
    )
    assertEquals(
      listOf("e6"),
      refs[PlaywrightAriaSnapshot.roleNameCorrelationKey("checkbox", "Accept terms")],
    )
  }

  @Test
  fun `frame-nested refs are excluded from the correlation map`() {
    // AriaSnapshotMode.AI expands <iframe> content inline and prefixes those refs with the
    // owning frame's sequence number (e.g. "f1e2"), unlike main-frame refs ("e2"). The
    // default-mode snapshot used to build the compact list / viewHierarchy tree never
    // descends into iframes at all (it shows a leaf "- iframe" node), so a frame-nested ref
    // must never enter this correlation map -- if it did, an (role, name, nth) lookup could
    // resolve to content inside the iframe instead of the intended main-frame element.
    // REF_PATTERN's `e\d+` (not `\w+`) is what enforces this: it only matches bare "eN"
    // refs, never "f<N>eN"-shaped frame-nested ones.
    val yaml = """
      - document [ref=e1]:
        - button "Submit" [ref=e3]
        - iframe [ref=e4]:
          - button "Submit" [ref=f1e2]
    """.trimIndent()

    val refs = PlaywrightAriaSnapshot.buildAiRefsByRoleName(yaml)

    assertEquals(
      listOf("e3"),
      refs[PlaywrightAriaSnapshot.roleNameCorrelationKey("button", "Submit")],
      "Only the main-frame \"Submit\" button's ref should be in the map, not the iframe's",
    )
  }
}
