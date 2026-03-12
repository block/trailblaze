package xyz.block.trailblaze.playwright

import org.junit.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pure unit tests for [PlaywrightAriaSnapshot.buildCompactElementList] and
 * [PlaywrightAriaSnapshot.ariaSnapshotToViewHierarchy].
 *
 * These tests exercise the ARIA snapshot parsing logic without launching a browser.
 */
class PlaywrightAriaSnapshotTest {

  // -- buildCompactElementList tests --

  @Test
  fun `empty YAML returns empty element list without crashing`() {
    val result = PlaywrightAriaSnapshot.buildCompactElementList("")
    assertEquals(emptyMap(), result.elementIdMapping)
    assertContains(result.text, "(empty page)")
  }

  @Test
  fun `blank YAML returns empty element list without crashing`() {
    val result = PlaywrightAriaSnapshot.buildCompactElementList("   \n  \n  ")
    assertEquals(emptyMap(), result.elementIdMapping)
    assertContains(result.text, "(empty page)")
  }

  @Test
  fun `single interactive element gets an element ID`() {
    val yaml = """
      - document:
        - button "Submit"
    """.trimIndent()

    val result = PlaywrightAriaSnapshot.buildCompactElementList(yaml)

    assertContains(result.text, "[e1]")
    assertContains(result.text, "button \"Submit\"")
    assertEquals(1, result.elementIdMapping.size)
    val ref = result.elementIdMapping["e1"]
    assertNotNull(ref)
    assertEquals("button \"Submit\"", ref.descriptor)
    assertEquals(0, ref.nthIndex)
  }

  @Test
  fun `non-interactive structural elements are excluded from compact list`() {
    // A paragraph/text node without a meaningful ARIA role should not get an ID
    // when it lacks text content (structural containers are filtered out)
    val yaml = """
      - document:
        - generic:
          - button "OK"
    """.trimIndent()

    val result = PlaywrightAriaSnapshot.buildCompactElementList(yaml)

    // "generic" should not appear in the output
    assertFalse(result.text.contains("generic"))
    // But the button should
    assertContains(result.text, "button \"OK\"")
  }

  @Test
  fun `landmark sections create section headers`() {
    val yaml = """
      - document:
        - navigation:
          - link "Home"
          - link "About"
        - main:
          - heading "Welcome" [level=1]
          - button "Submit"
    """.trimIndent()

    val result = PlaywrightAriaSnapshot.buildCompactElementList(yaml)

    assertContains(result.text, "navigation:")
    assertContains(result.text, "main:")
    assertContains(result.text, "link \"Home\"")
    assertContains(result.text, "button \"Submit\"")
  }

  @Test
  fun `nested elements have proper indentation`() {
    val yaml = """
      - document:
        - navigation:
          - link "Home"
        - main:
          - heading "Title" [level=1]
    """.trimIndent()

    val result = PlaywrightAriaSnapshot.buildCompactElementList(yaml)
    val lines = result.text.lines()

    // Navigation header should have less indentation than its children
    val navLine = lines.find { it.trimStart().startsWith("navigation:") }
    val linkLine = lines.find { it.contains("link \"Home\"") }
    assertNotNull(navLine)
    assertNotNull(linkLine)

    val navIndent = navLine.length - navLine.trimStart().length
    val linkIndent = linkLine.length - linkLine.trimStart().length
    assertTrue(linkIndent > navIndent, "Link should be indented more than navigation header")
  }

  @Test
  fun `duplicate roles get distinct element IDs`() {
    val yaml = """
      - document:
        - navigation:
          - link "Hardware"
        - main:
          - link "Hardware"
    """.trimIndent()

    val result = PlaywrightAriaSnapshot.buildCompactElementList(yaml)

    // Both links should have IDs
    assertContains(result.text, "[e1]")
    assertContains(result.text, "[e2]")

    // First occurrence: nthIndex=0, second: nthIndex=1
    val e1 = result.elementIdMapping["e1"]!!
    val e2 = result.elementIdMapping["e2"]!!
    assertEquals("link \"Hardware\"", e1.descriptor)
    assertEquals("link \"Hardware\"", e2.descriptor)
    assertEquals(0, e1.nthIndex)
    assertEquals(1, e2.nthIndex)
  }

  @Test
  fun `element ID mapping maps IDs back to correct ElementRef with nthIndex`() {
    val yaml = """
      - document:
        - button "Save"
        - textbox "Email"
        - button "Save"
        - link "Help"
    """.trimIndent()

    val result = PlaywrightAriaSnapshot.buildCompactElementList(yaml)

    // Should have 4 elements
    assertEquals(4, result.elementIdMapping.size)

    // Verify each mapping
    val e1 = result.elementIdMapping["e1"]!!
    assertEquals("button \"Save\"", e1.descriptor)
    assertEquals(0, e1.nthIndex)

    val e2 = result.elementIdMapping["e2"]!!
    assertEquals("textbox \"Email\"", e2.descriptor)
    assertEquals(0, e2.nthIndex)

    val e3 = result.elementIdMapping["e3"]!!
    assertEquals("button \"Save\"", e3.descriptor)
    assertEquals(1, e3.nthIndex) // Second occurrence

    val e4 = result.elementIdMapping["e4"]!!
    assertEquals("link \"Help\"", e4.descriptor)
    assertEquals(0, e4.nthIndex)
  }

  @Test
  fun `special characters in text are preserved`() {
    val yaml = """
      - document:
        - button "Save & Continue"
        - link "Terms [v2]"
        - heading "What's New?" [level=1]
    """.trimIndent()

    val result = PlaywrightAriaSnapshot.buildCompactElementList(yaml)

    assertContains(result.text, "Save & Continue")
    // The heading should appear with its text
    assertContains(result.text, "What's New?")
  }

  // -- ariaSnapshotToViewHierarchy tests --

  @Test
  fun `empty YAML produces single document node`() {
    val root = PlaywrightAriaSnapshot.ariaSnapshotToViewHierarchy("")
    assertEquals("document", root.className)
    assertEquals("(empty page)", root.text)
  }

  @Test
  fun `blank YAML produces single document node`() {
    val root = PlaywrightAriaSnapshot.ariaSnapshotToViewHierarchy("   \n   \n   ")
    assertEquals("document", root.className)
    assertEquals("(empty page)", root.text)
  }

  @Test
  fun `view hierarchy tree has correct structure`() {
    val yaml = """
      - document:
        - navigation:
          - link "Home"
          - link "About"
        - main:
          - heading "Welcome" [level=1]
          - button "Submit"
    """.trimIndent()

    val root = PlaywrightAriaSnapshot.ariaSnapshotToViewHierarchy(yaml)

    assertEquals("document", root.className)
    assertTrue(root.children.isNotEmpty(), "Root should have children")

    // Find navigation child
    val nav = root.children.find { it.className == "navigation" }
    assertNotNull(nav, "Should have navigation child")
    assertEquals(2, nav.children.size, "Navigation should have 2 link children")
    assertEquals("link", nav.children[0].className)
    assertEquals("Home", nav.children[0].text)

    // Find main child
    val main = root.children.find { it.className == "main" }
    assertNotNull(main, "Should have main child")
    assertTrue(main.children.size >= 2, "Main should have at least 2 children")
  }

  @Test
  fun `view hierarchy marks interactive elements as clickable`() {
    val yaml = """
      - document:
        - button "Click Me"
        - link "Go"
    """.trimIndent()

    val root = PlaywrightAriaSnapshot.ariaSnapshotToViewHierarchy(yaml)

    val button = root.children.find { it.className == "button" }
    assertNotNull(button)
    assertTrue(button.clickable == true, "Buttons should be clickable")

    val link = root.children.find { it.className == "link" }
    assertNotNull(link)
    assertTrue(link.clickable == true, "Links should be clickable")
  }

  @Test
  fun `view hierarchy marks input elements as focusable`() {
    val yaml = """
      - document:
        - textbox "Email"
    """.trimIndent()

    val root = PlaywrightAriaSnapshot.ariaSnapshotToViewHierarchy(yaml)

    val textbox = root.children.find { it.className == "textbox" }
    assertNotNull(textbox)
    assertTrue(textbox.focusable == true, "Textboxes should be focusable")
  }
}
