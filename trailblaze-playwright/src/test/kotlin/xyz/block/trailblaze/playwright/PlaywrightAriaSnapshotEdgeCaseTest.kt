package xyz.block.trailblaze.playwright

import org.junit.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Edge case and corner case tests for [PlaywrightAriaSnapshot.buildCompactElementList] and
 * [PlaywrightAriaSnapshot.ariaSnapshotToViewHierarchy].
 *
 * Exercises defensive parsing logic, unusual YAML structures, and boundary conditions
 * that were hardened in the Playwright-native bug fix pass.
 */
class PlaywrightAriaSnapshotEdgeCaseTest {

  // -- Malformed / unusual YAML inputs --

  @Test
  fun `YAML with only whitespace lines is treated as empty`() {
    val yaml = "   \n\t\n   \n"
    val result = PlaywrightAriaSnapshot.buildCompactElementList(yaml)
    assertContains(result.text, "(empty page)")
    assertEquals(emptyMap(), result.elementIdMapping)
  }

  @Test
  fun `YAML with inconsistent indentation still parses elements`() {
    // Simulate unusual indentation from non-standard ARIA snapshots
    val yaml = """
      - document:
        - navigation:
              - link "Deep Link"
        - button "Normal"
    """.trimIndent()

    val result = PlaywrightAriaSnapshot.buildCompactElementList(yaml)

    assertContains(result.text, "link \"Deep Link\"")
    assertContains(result.text, "button \"Normal\"")
    assertTrue(result.elementIdMapping.isNotEmpty())
  }

  @Test
  fun `YAML lines with only dashes and spaces produce no spurious elements`() {
    val yaml = """
      - document:
        -
        - button "OK"
        -
    """.trimIndent()

    val result = PlaywrightAriaSnapshot.buildCompactElementList(yaml)

    // Should contain the button but no spurious entries from empty dash lines
    assertContains(result.text, "button \"OK\"")
    assertEquals(1, result.elementIdMapping.size)
  }

  @Test
  fun `single-line YAML without document wrapper`() {
    val yaml = "- button \"Solo\""

    val result = PlaywrightAriaSnapshot.buildCompactElementList(yaml)

    assertContains(result.text, "button \"Solo\"")
    assertEquals(1, result.elementIdMapping.size)
  }

  // -- Unicode and special character handling --

  @Test
  fun `emoji in element names are preserved`() {
    val yaml = """
      - document:
        - button "🔒 Lock Account"
        - link "📧 Contact Us"
    """.trimIndent()

    val result = PlaywrightAriaSnapshot.buildCompactElementList(yaml)

    assertContains(result.text, "🔒 Lock Account")
    assertContains(result.text, "📧 Contact Us")
    assertEquals(2, result.elementIdMapping.size)
  }

  @Test
  fun `CJK characters in element names are preserved`() {
    val yaml = """
      - document:
        - button "保存"
        - link "ホーム"
        - heading "환영합니다" [level=1]
    """.trimIndent()

    val result = PlaywrightAriaSnapshot.buildCompactElementList(yaml)

    assertContains(result.text, "保存")
    assertContains(result.text, "ホーム")
    assertContains(result.text, "환영합니다")
    assertEquals(3, result.elementIdMapping.size)
  }

  @Test
  fun `element text with embedded quotes is preserved`() {
    // ARIA snapshot escapes inner quotes differently; verify our parsing handles it
    val yaml = """
      - document:
        - heading "What's New" [level=1]
    """.trimIndent()

    val result = PlaywrightAriaSnapshot.buildCompactElementList(yaml)

    assertContains(result.text, "What's New")
  }

  @Test
  fun `text content with colon format is parsed into view hierarchy`() {
    // Text nodes (role="text") map to AriaRole.GENERIC which is structural,
    // so they are correctly filtered from the compact element list.
    // Verify they still parse into the view hierarchy tree.
    val yaml = """
      - document:
        - text: Hello world
        - text: Price: $19.99
    """.trimIndent()

    val root = PlaywrightAriaSnapshot.ariaSnapshotToViewHierarchy(yaml)

    assertEquals("document", root.className)
    val textNodes = root.children.filter { it.className == "text" }
    assertTrue(textNodes.isNotEmpty(), "Should have text child nodes")
    val helloNode = textNodes.find { it.text == "Hello world" }
    assertNotNull(helloNode, "Should find 'Hello world' text node")
  }

  // -- Deeply nested and complex structures --

  @Test
  fun `deeply nested elements (10+ levels) parse without stack overflow`() {
    // Build a YAML with 20 levels of nesting
    val sb = StringBuilder()
    sb.appendLine("- document:")
    for (i in 1..20) {
      val indent = "  ".repeat(i)
      sb.appendLine("$indent- navigation:")
    }
    val deepIndent = "  ".repeat(21)
    sb.appendLine("$deepIndent- button \"Deep Button\"")

    val result = PlaywrightAriaSnapshot.buildCompactElementList(sb.toString())

    assertContains(result.text, "button \"Deep Button\"")
    assertTrue(result.elementIdMapping.isNotEmpty())
  }

  @Test
  fun `deeply nested view hierarchy builds correct tree`() {
    val yaml = """
      - document:
        - main:
          - navigation:
            - list:
              - listitem:
                - link "Nested"
    """.trimIndent()

    val root = PlaywrightAriaSnapshot.ariaSnapshotToViewHierarchy(yaml)

    assertEquals("document", root.className)
    // Walk down the tree to verify nesting
    val main = root.children.firstOrNull()
    assertNotNull(main)
    assertEquals("main", main.className)
  }

  // -- Element filtering edge cases --

  @Test
  fun `page with only structural containers returns no interactive elements`() {
    val yaml = """
      - document:
        - generic:
          - generic:
            - generic:
    """.trimIndent()

    val result = PlaywrightAriaSnapshot.buildCompactElementList(yaml)

    assertContains(result.text, "(no interactive elements found)")
    assertEquals(emptyMap(), result.elementIdMapping)
  }

  @Test
  fun `unnamed landmark without meaningful descendants is omitted`() {
    // A navigation with only generic children should not emit a header
    val yaml = """
      - document:
        - navigation:
          - generic:
        - main:
          - button "Click"
    """.trimIndent()

    val result = PlaywrightAriaSnapshot.buildCompactElementList(yaml)

    // Navigation header should NOT appear since it has no meaningful descendants
    assertFalse(
      result.text.contains("navigation:"),
      "Navigation with no meaningful content should be omitted, got:\n${result.text}",
    )
    // Main and button should appear
    assertContains(result.text, "main:")
    assertContains(result.text, "button \"Click\"")
  }

  @Test
  fun `named non-structural elements are included even with unusual roles`() {
    // An element with a non-standard role but a name should still be included
    val yaml = """
      - document:
        - status "Loading..."
        - alert "Error occurred"
    """.trimIndent()

    val result = PlaywrightAriaSnapshot.buildCompactElementList(yaml)

    assertContains(result.text, "Loading...")
    assertContains(result.text, "Error occurred")
  }

  // -- Duplicate descriptor disambiguation at scale --

  @Test
  fun `ten duplicate buttons get correct nthIndex values`() {
    val sb = StringBuilder()
    sb.appendLine("- document:")
    for (i in 1..10) {
      sb.appendLine("  - button \"Next\"")
    }

    val result = PlaywrightAriaSnapshot.buildCompactElementList(sb.toString())

    assertEquals(10, result.elementIdMapping.size)
    for (i in 1..10) {
      val ref = result.elementIdMapping["e$i"]
      assertNotNull(ref, "Should have mapping for e$i")
      assertEquals("button \"Next\"", ref.descriptor)
      assertEquals(i - 1, ref.nthIndex, "e$i should have nthIndex=${i - 1}")
    }
  }

  @Test
  fun `mixed duplicate and unique elements get correct mappings`() {
    val yaml = """
      - document:
        - button "Save"
        - link "Home"
        - button "Save"
        - link "About"
        - button "Save"
    """.trimIndent()

    val result = PlaywrightAriaSnapshot.buildCompactElementList(yaml)

    assertEquals(5, result.elementIdMapping.size)

    // Verify Save buttons have incrementing nthIndex
    val saveButtons = result.elementIdMapping.values
      .filter { it.descriptor == "button \"Save\"" }
      .sortedBy { it.nthIndex }
    assertEquals(3, saveButtons.size)
    assertEquals(listOf(0, 1, 2), saveButtons.map { it.nthIndex })

    // Verify unique links have nthIndex=0
    val homeLink = result.elementIdMapping.values.find { it.descriptor == "link \"Home\"" }
    assertEquals(0, homeLink?.nthIndex)
  }

  // -- ARIA attribute handling --

  @Test
  fun `trailing ARIA attributes are stripped from descriptors`() {
    val yaml = """
      - document:
        - heading "Welcome" [level=1]
        - checkbox "Accept" [checked=true]
        - textbox "Name" [required]
    """.trimIndent()

    val result = PlaywrightAriaSnapshot.buildCompactElementList(yaml)

    // Descriptors should not contain the attribute brackets
    val descriptors = result.elementIdMapping.values.map { it.descriptor }
    assertTrue(
      descriptors.none { it.contains("[level=") || it.contains("[checked=") || it.contains("[required") },
      "Descriptors should not contain ARIA attributes: $descriptors",
    )
  }

  // -- View hierarchy edge cases --

  @Test
  fun `view hierarchy from single element YAML`() {
    val yaml = "- button \"Solo\""
    val root = PlaywrightAriaSnapshot.ariaSnapshotToViewHierarchy(yaml)

    assertEquals("button", root.className)
    assertEquals("Solo", root.text)
    assertTrue(root.clickable == true)
  }

  @Test
  fun `view hierarchy preserves all children`() {
    val yaml = """
      - document:
        - navigation:
          - link "One"
          - link "Two"
        - main:
          - button "Three"
    """.trimIndent()

    val root = PlaywrightAriaSnapshot.ariaSnapshotToViewHierarchy(yaml)

    // Collect all text nodes recursively
    val texts = mutableListOf<String?>()
    fun collectTexts(node: xyz.block.trailblaze.api.ViewHierarchyTreeNode) {
      texts.add(node.text)
      node.children.forEach { collectTexts(it) }
    }
    collectTexts(root)

    assertTrue(texts.contains("One"), "Should contain link 'One'")
    assertTrue(texts.contains("Two"), "Should contain link 'Two'")
    assertTrue(texts.contains("Three"), "Should contain button 'Three'")
  }

  // -- Landmark ordering edge cases --

  @Test
  fun `multiple landmarks at same level appear in source order`() {
    val yaml = """
      - document:
        - banner:
          - link "Logo"
        - navigation:
          - link "Menu"
        - main:
          - button "Action"
        - contentinfo:
          - link "Footer"
    """.trimIndent()

    val result = PlaywrightAriaSnapshot.buildCompactElementList(yaml)
    val lines = result.text.lines()

    // Find indices of landmark headers
    val bannerIdx = lines.indexOfFirst { it.trimStart().startsWith("banner:") }
    val navIdx = lines.indexOfFirst { it.trimStart().startsWith("navigation:") }
    val mainIdx = lines.indexOfFirst { it.trimStart().startsWith("main:") }
    val footerIdx = lines.indexOfFirst { it.trimStart().startsWith("contentinfo:") }

    assertTrue(bannerIdx < navIdx, "banner should come before navigation")
    assertTrue(navIdx < mainIdx, "navigation should come before main")
    assertTrue(mainIdx < footerIdx, "main should come before contentinfo")
  }

  @Test
  fun `sibling landmarks do not leak into each other`() {
    val yaml = """
      - document:
        - navigation:
          - link "Nav Link"
        - main:
          - button "Main Button"
    """.trimIndent()

    val result = PlaywrightAriaSnapshot.buildCompactElementList(yaml)
    val lines = result.text.lines()

    // Find the navigation section boundary
    val navIdx = lines.indexOfFirst { it.trimStart().startsWith("navigation:") }
    val mainIdx = lines.indexOfFirst { it.trimStart().startsWith("main:") }
    val navLinkIdx = lines.indexOfFirst { it.contains("Nav Link") }
    val mainBtnIdx = lines.indexOfFirst { it.contains("Main Button") }

    // Nav link should be between navigation: and main:
    assertTrue(navLinkIdx > navIdx && navLinkIdx < mainIdx, "Nav link should be under navigation")
    // Main button should be after main:
    assertTrue(mainBtnIdx > mainIdx, "Main button should be under main")
  }
}
