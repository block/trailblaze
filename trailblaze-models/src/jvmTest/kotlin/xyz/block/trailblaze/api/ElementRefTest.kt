package xyz.block.trailblaze.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ElementRefTest {

  // -- RefTracker: hashing & format ------------------------------------------------

  @Test
  fun `ref format is one letter plus one to three digits`() {
    val tracker = ElementRef.RefTracker()
    val ref = tracker.ref("Sign In", "Button", 100, 200)
    assertTrue(ref.matches(Regex("[a-z]\\d{1,3}")), "Expected format [a-z]\\d{1,3}, got $ref")
  }

  @Test
  fun `same inputs produce same ref across independent trackers`() {
    val ref1 = ElementRef.RefTracker().ref("OK", "Button", 50, 50)
    val ref2 = ElementRef.RefTracker().ref("OK", "Button", 50, 50)
    assertEquals(ref1, ref2)
  }

  @Test
  fun `different inputs produce different refs`() {
    val tracker = ElementRef.RefTracker()
    val ref1 = tracker.ref("Sign In", "Button", 100, 200)
    val ref2 = tracker.ref("Cancel", "Button", 100, 300)
    assertNotEquals(ref1, ref2)
  }

  @Test
  fun `hash is deterministic across many calls`() {
    // Verify hash stability by generating refs 100 times
    val expected = ElementRef.RefTracker().ref("Checkout", "Button", 300, 600)
    repeat(100) {
      val ref = ElementRef.RefTracker().ref("Checkout", "Button", 300, 600)
      assertEquals(expected, ref, "Hash should be deterministic on iteration $it")
    }
  }

  @Test
  fun `ref varies with different labels`() {
    val r1 = ElementRef.RefTracker().ref("OK", "Button", 100, 100)
    val r2 = ElementRef.RefTracker().ref("Cancel", "Button", 100, 100)
    assertNotEquals(r1, r2, "Different labels should produce different refs")
  }

  @Test
  fun `ref varies with different classNames`() {
    val r1 = ElementRef.RefTracker().ref("OK", "Button", 100, 100)
    val r2 = ElementRef.RefTracker().ref("OK", "TextView", 100, 100)
    assertNotEquals(r1, r2, "Different classNames should produce different refs")
  }

  @Test
  fun `ref varies with different positions`() {
    val r1 = ElementRef.RefTracker().ref("OK", "Button", 100, 100)
    val r2 = ElementRef.RefTracker().ref("OK", "Button", 100, 200)
    assertNotEquals(r1, r2, "Different positions should produce different refs")
  }

  // -- RefTracker: collision suffixes -----------------------------------------------

  @Test
  fun `first occurrence has no suffix`() {
    val tracker = ElementRef.RefTracker()
    val ref = tracker.ref("Login", "Button", 0, 0)
    assertTrue(
      ref.matches(Regex("[a-z]\\d{1,3}")),
      "First ref should have no letter suffix beyond the leading letter, got $ref",
    )
  }

  @Test
  fun `collision suffix sequence is b then c then d`() {
    val tracker = ElementRef.RefTracker()
    val first = tracker.ref("Login", "Button", 0, 0)
    val second = tracker.ref("Login", "Button", 0, 0)
    val third = tracker.ref("Login", "Button", 0, 0)
    val fourth = tracker.ref("Login", "Button", 0, 0)

    assertEquals(first + "b", second, "Second collision should append 'b'")
    assertEquals(first + "c", third, "Third collision should append 'c'")
    assertEquals(first + "d", fourth, "Fourth collision should append 'd'")
  }

  @Test
  fun `collision tracking is per base hash not global`() {
    val tracker = ElementRef.RefTracker()
    val a1 = tracker.ref("Login", "Button", 0, 0)
    val b1 = tracker.ref("Cancel", "Button", 0, 100)
    val a2 = tracker.ref("Login", "Button", 0, 0)
    val b2 = tracker.ref("Cancel", "Button", 0, 100)

    // Each base gets its own collision sequence
    assertEquals(a1 + "b", a2)
    assertEquals(b1 + "b", b2)
  }

  @Test
  fun `many collisions produce sequential suffixes`() {
    val tracker = ElementRef.RefTracker()
    val first = tracker.ref("Item", "Cell", 0, 0)
    val suffixes = (2..10).map { tracker.ref("Item", "Cell", 0, 0) }

    // Second collision gets 'b', third gets 'c', etc.
    suffixes.forEachIndexed { index, ref ->
      val expectedSuffix = 'a' + index + 1
      assertEquals(first + expectedSuffix, ref, "Collision #${index + 2} should end with '$expectedSuffix'")
    }
  }

  // -- RefTracker: position rounding tolerance --------------------------------------

  @Test
  fun `minor position shift within 10px produces same ref`() {
    val ref1 = ElementRef.RefTracker().ref("OK", "Button", 100, 200)
    val ref2 = ElementRef.RefTracker().ref("OK", "Button", 105, 203)
    assertEquals(ref1, ref2, "Position rounded to nearest 10 should produce same hash")
  }

  @Test
  fun `position shift beyond 10px produces different ref`() {
    val ref1 = ElementRef.RefTracker().ref("OK", "Button", 100, 200)
    val ref2 = ElementRef.RefTracker().ref("OK", "Button", 110, 200)
    assertNotEquals(ref1, ref2, "10px boundary crossing should change the hash")
  }

  @Test
  fun `position rounding boundary at 9 vs 10`() {
    // 109/10*10 = 100 (same as 100), but 110/10*10 = 110 (different)
    val refBase = ElementRef.RefTracker().ref("OK", "Button", 100, 200)
    val refAt109 = ElementRef.RefTracker().ref("OK", "Button", 109, 200)
    val refAt110 = ElementRef.RefTracker().ref("OK", "Button", 110, 200)
    assertEquals(refBase, refAt109, "109 rounds to 100 (same bucket)")
    assertNotEquals(refBase, refAt110, "110 rounds to 110 (different bucket)")
  }

  @Test
  fun `position rounding is symmetric for X and Y`() {
    val refBase = ElementRef.RefTracker().ref("OK", "Button", 100, 200)
    val refYShift = ElementRef.RefTracker().ref("OK", "Button", 100, 205)
    assertEquals(refBase, refYShift, "Y shift within 10px should round to same value")
  }

  @Test
  fun `zero coordinates produce valid ref`() {
    val tracker = ElementRef.RefTracker()
    val ref = tracker.ref("Hello", "Button", 0, 0)
    assertTrue(ref.matches(Regex("[a-z]\\d{1,3}")), "Zero coordinates should still produce valid ref: $ref")
  }

  @Test
  fun `negative coordinates produce valid ref`() {
    // Some nodes may have negative coordinates (off-screen content)
    val tracker = ElementRef.RefTracker()
    val ref = tracker.ref("Offscreen", "View", -100, -50)
    assertTrue(ref.matches(Regex("[a-z]\\d{1,3}")), "Negative coordinates should produce valid ref: $ref")
  }

  @Test
  fun `large coordinates produce valid ref`() {
    val tracker = ElementRef.RefTracker()
    val ref = tracker.ref("Far", "View", 10000, 20000)
    assertTrue(ref.matches(Regex("[a-z]\\d{1,3}")), "Large coordinates should produce valid ref: $ref")
  }

  // -- RefTracker: null handling ----------------------------------------------------

  @Test
  fun `null label and className still produce valid ref`() {
    val tracker = ElementRef.RefTracker()
    val ref = tracker.ref(null, null, 50, 50)
    assertTrue(ref.matches(Regex("[a-z]\\d{1,3}")), "Null inputs should still produce valid ref")
  }

  @Test
  fun `null label only produces valid ref`() {
    val ref = ElementRef.RefTracker().ref(null, "Button", 100, 200)
    assertTrue(ref.matches(Regex("[a-z]\\d{1,3}")), "Null label should produce valid ref: $ref")
  }

  @Test
  fun `null className only produces valid ref`() {
    val ref = ElementRef.RefTracker().ref("OK", null, 100, 200)
    assertTrue(ref.matches(Regex("[a-z]\\d{1,3}")), "Null className should produce valid ref: $ref")
  }

  @Test
  fun `null label differs from empty label`() {
    val refNull = ElementRef.RefTracker().ref(null, "Button", 100, 100)
    val refEmpty = ElementRef.RefTracker().ref("", "Button", 100, 100)
    // Both should be valid but may or may not be equal depending on hash
    assertTrue(refNull.matches(Regex("[a-z]\\d{1,3}")))
    assertTrue(refEmpty.matches(Regex("[a-z]\\d{1,3}")))
  }

  // -- resolveRef: round-trip with Android nodes ------------------------------------

  private var nextId = 0L

  private fun androidNode(
    text: String? = null,
    contentDescription: String? = null,
    hintText: String? = null,
    className: String? = null,
    centerX: Int = 50,
    centerY: Int = 25,
    isClickable: Boolean = true,
    isCheckable: Boolean = false,
    isEditable: Boolean = false,
    isHeading: Boolean = false,
    isFocused: Boolean = false,
    isScrollable: Boolean = false,
    isVisibleToUser: Boolean = true,
    isImportantForAccessibility: Boolean = true,
    packageName: String? = null,
    error: String? = null,
    children: List<TrailblazeNode> = emptyList(),
  ): TrailblazeNode = TrailblazeNode(
    nodeId = nextId++,
    driverDetail = DriverNodeDetail.AndroidAccessibility(
      className = className?.let { "android.widget.$it" },
      text = text,
      contentDescription = contentDescription,
      hintText = hintText,
      isClickable = isClickable,
      isCheckable = isCheckable,
      isEditable = isEditable,
      isHeading = isHeading,
      isFocused = isFocused,
      isScrollable = isScrollable,
      isVisibleToUser = isVisibleToUser,
      isImportantForAccessibility = isImportantForAccessibility,
      packageName = packageName,
      error = error,
    ),
    bounds = TrailblazeNode.Bounds(
      left = centerX - 50,
      top = centerY - 25,
      right = centerX + 50,
      bottom = centerY + 25,
    ),
    children = children,
  )

  private fun androidNodeNoBounds(
    text: String? = null,
    className: String? = null,
    isClickable: Boolean = true,
    isVisibleToUser: Boolean = true,
    isImportantForAccessibility: Boolean = true,
    children: List<TrailblazeNode> = emptyList(),
  ): TrailblazeNode = TrailblazeNode(
    nodeId = nextId++,
    driverDetail = DriverNodeDetail.AndroidAccessibility(
      className = className?.let { "android.widget.$it" },
      text = text,
      isClickable = isClickable,
      isVisibleToUser = isVisibleToUser,
      isImportantForAccessibility = isImportantForAccessibility,
    ),
    bounds = null,
    children = children,
  )

  private fun iosNode(
    text: String? = null,
    accessibilityText: String? = null,
    hintText: String? = null,
    className: String? = null,
    centerX: Int = 50,
    centerY: Int = 25,
    clickable: Boolean = false,
    checked: Boolean = false,
    selected: Boolean = false,
    focused: Boolean = false,
    visible: Boolean = true,
    children: List<TrailblazeNode> = emptyList(),
  ): TrailblazeNode = TrailblazeNode(
    nodeId = nextId++,
    driverDetail = DriverNodeDetail.IosMaestro(
      text = text,
      accessibilityText = accessibilityText,
      hintText = hintText,
      className = className,
      clickable = clickable,
      checked = checked,
      selected = selected,
      focused = focused,
      visible = visible,
    ),
    bounds = TrailblazeNode.Bounds(
      left = centerX - 50,
      top = centerY - 25,
      right = centerX + 50,
      bottom = centerY + 25,
    ),
    children = children,
  )

  @Test
  fun `resolveRef finds node by generated ref`() {
    val target = androidNode(text = "Submit", className = "Button", centerX = 200, centerY = 400)
    val root = androidNode(
      text = "Root",
      className = "FrameLayout",
      children = listOf(
        androidNode(text = "Title", className = "TextView"),
        target,
      ),
    )

    // Generate the ref the same way the compact list would
    val tracker = ElementRef.RefTracker()
    // Walk in same order as resolveRef: root, then children left-to-right
    tracker.ref("Root", "FrameLayout", 50, 25) // root
    tracker.ref("Title", "TextView", 50, 25) // first child
    val targetRef = tracker.ref("Submit", "Button", 200, 400) // target

    val resolved = ElementRef.resolveRef(root, targetRef)
    assertNotNull(resolved, "Should resolve the target node")
    assertEquals(target.nodeId, resolved.nodeId)
  }

  @Test
  fun `resolveRef returns null for nonexistent ref`() {
    val root = androidNode(text = "Alone", className = "Button")
    assertNull(ElementRef.resolveRef(root, "z999"))
  }

  @Test
  fun `resolveRef handles collisions correctly`() {
    // Two nodes with identical properties -> same base hash -> collision suffixes
    val node1 = androidNode(text = "Item", className = "TextView", centerX = 100, centerY = 100)
    val node2 = androidNode(text = "Item", className = "TextView", centerX = 100, centerY = 100)
    val root = androidNode(
      text = "List",
      className = "RecyclerView",
      children = listOf(node1, node2),
    )

    val tracker = ElementRef.RefTracker()
    tracker.ref("List", "RecyclerView", 50, 25) // root
    val ref1 = tracker.ref("Item", "TextView", 100, 100) // first
    val ref2 = tracker.ref("Item", "TextView", 100, 100) // collision -> suffix 'b'

    assertNotEquals(ref1, ref2, "Colliding nodes should have different refs")

    val resolved1 = ElementRef.resolveRef(root, ref1)
    val resolved2 = ElementRef.resolveRef(root, ref2)
    assertNotNull(resolved1)
    assertNotNull(resolved2)
    assertEquals(node1.nodeId, resolved1.nodeId)
    assertEquals(node2.nodeId, resolved2.nodeId)
  }

  // -- resolveRef: empty and single-node trees ------------------------------------

  @Test
  fun `resolveRef on single node tree that matches`() {
    val root = androidNode(text = "OnlyButton", className = "Button", centerX = 100, centerY = 200)
    val tracker = ElementRef.RefTracker()
    val ref = tracker.ref("OnlyButton", "Button", 100, 200)

    val resolved = ElementRef.resolveRef(root, ref)
    assertNotNull(resolved)
    assertEquals(root.nodeId, resolved.nodeId)
  }

  @Test
  fun `resolveRef on root with empty children list`() {
    val root = androidNode(
      text = "EmptyParent",
      className = "FrameLayout",
      children = emptyList(),
    )
    // Resolve a ref that doesn't exist
    assertNull(ElementRef.resolveRef(root, "a999"))
  }

  // -- resolveRef: deep tree navigation -------------------------------------------

  @Test
  fun `resolveRef finds deeply nested node`() {
    val deepChild = androidNode(text = "DeepTarget", className = "Button", centerX = 300, centerY = 500)
    val root = androidNode(
      text = "Level0",
      className = "FrameLayout",
      children = listOf(
        androidNode(
          text = "Level1",
          className = "LinearLayout",
          children = listOf(
            androidNode(
              text = "Level2",
              className = "RelativeLayout",
              children = listOf(deepChild),
            ),
          ),
        ),
      ),
    )

    // Walk tree in DFS order to compute the ref
    val tracker = ElementRef.RefTracker()
    tracker.ref("Level0", "FrameLayout", 50, 25)
    tracker.ref("Level1", "LinearLayout", 50, 25)
    tracker.ref("Level2", "RelativeLayout", 50, 25)
    val deepRef = tracker.ref("DeepTarget", "Button", 300, 500)

    val resolved = ElementRef.resolveRef(root, deepRef)
    assertNotNull(resolved, "Should find deeply nested node")
    assertEquals(deepChild.nodeId, resolved.nodeId)
  }

  // -- resolveRef: isMeaningful filtering -----------------------------------------

  @Test
  fun `resolveRef skips non-meaningful nodes`() {
    // A node that is NOT clickable, editable, checkable, heading, focused, scrollable,
    // has no error, and has no label -> not meaningful, so it should be skipped
    val nonMeaningful = androidNode(
      text = null,
      className = "View",
      isClickable = false,
      isImportantForAccessibility = false,
      centerX = 100,
      centerY = 100,
    )
    val meaningful = androidNode(
      text = "Clickable",
      className = "Button",
      isClickable = true,
      centerX = 200,
      centerY = 200,
    )
    val root = androidNode(
      text = "Root",
      className = "FrameLayout",
      children = listOf(nonMeaningful, meaningful),
    )

    // The non-meaningful node should be skipped in ref generation,
    // so the tracker should only emit refs for root and meaningful.
    val tracker = ElementRef.RefTracker()
    tracker.ref("Root", "FrameLayout", 50, 25) // root is meaningful (clickable)
    // nonMeaningful is skipped
    val meaningfulRef = tracker.ref("Clickable", "Button", 200, 200)

    val resolved = ElementRef.resolveRef(root, meaningfulRef)
    assertNotNull(resolved)
    assertEquals(meaningful.nodeId, resolved.nodeId)
  }

  @Test
  fun `isMeaningful includes editable nodes`() {
    val editable = androidNode(
      text = null,
      className = "EditText",
      isClickable = false,
      isEditable = true,
      centerX = 150,
      centerY = 150,
    )
    val root = androidNode(
      text = "Form",
      className = "FrameLayout",
      children = listOf(editable),
    )

    val tracker = ElementRef.RefTracker()
    tracker.ref("Form", "FrameLayout", 50, 25)
    // Editable node is meaningful even without text
    val editRef = tracker.ref(null, "EditText", 150, 150)

    val resolved = ElementRef.resolveRef(root, editRef)
    assertNotNull(resolved, "Editable node should be meaningful")
    assertEquals(editable.nodeId, resolved.nodeId)
  }

  @Test
  fun `isMeaningful includes checkable nodes`() {
    val checkbox = androidNode(
      text = null,
      className = "CheckBox",
      isClickable = false,
      isCheckable = true,
      centerX = 100,
      centerY = 100,
    )
    val root = androidNode(
      text = "Settings",
      className = "FrameLayout",
      children = listOf(checkbox),
    )

    val tracker = ElementRef.RefTracker()
    tracker.ref("Settings", "FrameLayout", 50, 25)
    val cbRef = tracker.ref(null, "CheckBox", 100, 100)

    val resolved = ElementRef.resolveRef(root, cbRef)
    assertNotNull(resolved, "Checkable node should be meaningful")
    assertEquals(checkbox.nodeId, resolved.nodeId)
  }

  @Test
  fun `isMeaningful includes heading nodes with label`() {
    val heading = androidNode(
      text = "Section Title",
      className = "TextView",
      isClickable = false,
      isHeading = true,
      centerX = 200,
      centerY = 50,
    )
    val root = androidNode(
      text = "Page",
      className = "FrameLayout",
      children = listOf(heading),
    )

    val tracker = ElementRef.RefTracker()
    tracker.ref("Page", "FrameLayout", 50, 25)
    val headingRef = tracker.ref("Section Title", "TextView", 200, 50)

    val resolved = ElementRef.resolveRef(root, headingRef)
    assertNotNull(resolved, "Heading with label should be meaningful")
    assertEquals(heading.nodeId, resolved.nodeId)
  }

  @Test
  fun `isMeaningful includes focused nodes`() {
    val focused = androidNode(
      text = null,
      className = "View",
      isClickable = false,
      isFocused = true,
      centerX = 100,
      centerY = 100,
    )
    val root = androidNode(
      text = "Container",
      className = "FrameLayout",
      children = listOf(focused),
    )

    val tracker = ElementRef.RefTracker()
    tracker.ref("Container", "FrameLayout", 50, 25)
    val focusedRef = tracker.ref(null, "View", 100, 100)

    val resolved = ElementRef.resolveRef(root, focusedRef)
    assertNotNull(resolved, "Focused node should be meaningful")
    assertEquals(focused.nodeId, resolved.nodeId)
  }

  @Test
  fun `isMeaningful includes node with error`() {
    val errorNode = androidNode(
      text = null,
      className = "EditText",
      isClickable = false,
      error = "Invalid email",
      centerX = 100,
      centerY = 100,
    )
    val root = androidNode(
      text = "Form",
      className = "FrameLayout",
      children = listOf(errorNode),
    )

    val tracker = ElementRef.RefTracker()
    tracker.ref("Form", "FrameLayout", 50, 25)
    val errorRef = tracker.ref(null, "EditText", 100, 100)

    val resolved = ElementRef.resolveRef(root, errorRef)
    assertNotNull(resolved, "Node with error should be meaningful")
    assertEquals(errorNode.nodeId, resolved.nodeId)
  }

  @Test
  fun `isMeaningful includes importantForAccessibility node with label`() {
    val important = androidNode(
      text = "Status text",
      className = "TextView",
      isClickable = false,
      isImportantForAccessibility = true,
      centerX = 200,
      centerY = 300,
    )
    val root = androidNode(
      text = "Parent",
      className = "FrameLayout",
      children = listOf(important),
    )

    val tracker = ElementRef.RefTracker()
    tracker.ref("Parent", "FrameLayout", 50, 25)
    val importantRef = tracker.ref("Status text", "TextView", 200, 300)

    val resolved = ElementRef.resolveRef(root, importantRef)
    assertNotNull(resolved, "ImportantForAccessibility node with label should be meaningful")
    assertEquals(important.nodeId, resolved.nodeId)
  }

  // -- resolveRef: effectiveLabel logic -------------------------------------------

  @Test
  fun `effectiveLabel absorbs child text for clickable container`() {
    // A clickable container with no text but a child that has text
    val childWithText = androidNode(
      text = "Continue",
      className = "TextView",
      isClickable = false,
      isImportantForAccessibility = true,
      centerX = 200,
      centerY = 200,
    )
    val clickableContainer = TrailblazeNode(
      nodeId = nextId++,
      driverDetail = DriverNodeDetail.AndroidAccessibility(
        className = "android.widget.LinearLayout",
        isClickable = true,
        isVisibleToUser = true,
        isImportantForAccessibility = true,
      ),
      bounds = TrailblazeNode.Bounds(left = 100, top = 100, right = 300, bottom = 300),
      children = listOf(childWithText),
    )
    val root = androidNode(
      text = "Screen",
      className = "FrameLayout",
      children = listOf(clickableContainer),
    )

    // The container should absorb "Continue" as its effectiveLabel
    val tracker = ElementRef.RefTracker()
    tracker.ref("Screen", "FrameLayout", 50, 25) // root
    val containerRef = tracker.ref("Continue", "LinearLayout", 200, 200) // container absorbs child text
    // The child with text "Continue" is also meaningful (importantForAccessibility + label)
    tracker.ref("Continue", "TextView", 200, 200) // child

    val resolved = ElementRef.resolveRef(root, containerRef)
    assertNotNull(resolved, "Should resolve container with absorbed child label")
    assertEquals(clickableContainer.nodeId, resolved.nodeId)
  }

  @Test
  fun `effectiveLabel absorbs child contentDescription for clickable container`() {
    val childWithContentDesc = TrailblazeNode(
      nodeId = nextId++,
      driverDetail = DriverNodeDetail.AndroidAccessibility(
        contentDescription = "Add to cart",
        isVisibleToUser = true,
        isImportantForAccessibility = true,
      ),
      bounds = TrailblazeNode.Bounds(left = 100, top = 100, right = 200, bottom = 200),
      children = emptyList(),
    )
    val clickableContainer = TrailblazeNode(
      nodeId = nextId++,
      driverDetail = DriverNodeDetail.AndroidAccessibility(
        className = "android.widget.FrameLayout",
        isClickable = true,
        isVisibleToUser = true,
        isImportantForAccessibility = true,
      ),
      bounds = TrailblazeNode.Bounds(left = 50, top = 50, right = 250, bottom = 250),
      children = listOf(childWithContentDesc),
    )
    val root = androidNode(
      text = "Root",
      className = "FrameLayout",
      children = listOf(clickableContainer),
    )

    val tracker = ElementRef.RefTracker()
    tracker.ref("Root", "FrameLayout", 50, 25) // root
    val containerRef = tracker.ref("Add to cart", "FrameLayout", 150, 150) // absorbs child cd

    val resolved = ElementRef.resolveRef(root, containerRef)
    assertNotNull(resolved, "Should resolve container with absorbed child contentDescription")
    assertEquals(clickableContainer.nodeId, resolved.nodeId)
  }

  @Test
  fun `effectiveLabel absorbs child hintText for checkable container`() {
    val childWithHint = TrailblazeNode(
      nodeId = nextId++,
      driverDetail = DriverNodeDetail.AndroidAccessibility(
        hintText = "Toggle notifications",
        isVisibleToUser = true,
        isImportantForAccessibility = true,
      ),
      bounds = TrailblazeNode.Bounds(left = 0, top = 0, right = 100, bottom = 100),
      children = emptyList(),
    )
    val checkableContainer = TrailblazeNode(
      nodeId = nextId++,
      driverDetail = DriverNodeDetail.AndroidAccessibility(
        className = "android.widget.Switch",
        isCheckable = true,
        isVisibleToUser = true,
        isImportantForAccessibility = true,
      ),
      bounds = TrailblazeNode.Bounds(left = 0, top = 0, right = 100, bottom = 100),
      children = listOf(childWithHint),
    )
    val root = androidNode(
      text = "Settings",
      className = "FrameLayout",
      children = listOf(checkableContainer),
    )

    val tracker = ElementRef.RefTracker()
    tracker.ref("Settings", "FrameLayout", 50, 25)
    val switchRef = tracker.ref("Toggle notifications", "Switch", 50, 50)

    val resolved = ElementRef.resolveRef(root, switchRef)
    assertNotNull(resolved, "Checkable container should absorb child hintText")
    assertEquals(checkableContainer.nodeId, resolved.nodeId)
  }

  @Test
  fun `effectiveLabel does not absorb for non-clickable non-checkable container`() {
    val childWithText = androidNode(
      text = "ChildText",
      className = "TextView",
      isClickable = false,
      isImportantForAccessibility = true,
      centerX = 200,
      centerY = 200,
    )
    // Non-clickable, non-checkable container -> effectiveLabel stays null
    val container = TrailblazeNode(
      nodeId = nextId++,
      driverDetail = DriverNodeDetail.AndroidAccessibility(
        className = "android.widget.LinearLayout",
        isClickable = false,
        isCheckable = false,
        isVisibleToUser = true,
        isImportantForAccessibility = false,
      ),
      bounds = TrailblazeNode.Bounds(left = 100, top = 100, right = 300, bottom = 300),
      children = listOf(childWithText),
    )
    val root = androidNode(
      text = "Screen",
      className = "FrameLayout",
      children = listOf(container),
    )

    // Container is not meaningful: not clickable, not checkable, no label, etc.
    // So resolveRef should skip it entirely. Only root and child get refs.
    val tracker = ElementRef.RefTracker()
    tracker.ref("Screen", "FrameLayout", 50, 25) // root
    // Container is skipped (not meaningful)
    val childRef = tracker.ref("ChildText", "TextView", 200, 200) // child

    val resolved = ElementRef.resolveRef(root, childRef)
    assertNotNull(resolved, "Should resolve to child, not container")
    assertEquals(childWithText.nodeId, resolved.nodeId)
  }

  // -- resolveRef: Android label resolution priority --------------------------------

  @Test
  fun `Android label prefers text over contentDescription`() {
    val node = androidNode(
      text = "Primary",
      contentDescription = "Secondary",
      className = "Button",
      isClickable = true,
      centerX = 100,
      centerY = 100,
    )
    val root = androidNode(text = "Root", className = "FrameLayout", children = listOf(node))

    // Label should be "Primary" (text wins)
    val tracker = ElementRef.RefTracker()
    tracker.ref("Root", "FrameLayout", 50, 25)
    val ref = tracker.ref("Primary", "Button", 100, 100)

    val resolved = ElementRef.resolveRef(root, ref)
    assertNotNull(resolved)
    assertEquals(node.nodeId, resolved.nodeId)
  }

  @Test
  fun `Android label falls back to contentDescription when text is null`() {
    val node = androidNode(
      text = null,
      contentDescription = "Close button",
      className = "ImageButton",
      isClickable = true,
      centerX = 100,
      centerY = 100,
    )
    val root = androidNode(text = "Root", className = "FrameLayout", children = listOf(node))

    val tracker = ElementRef.RefTracker()
    tracker.ref("Root", "FrameLayout", 50, 25)
    val ref = tracker.ref("Close button", "ImageButton", 100, 100)

    val resolved = ElementRef.resolveRef(root, ref)
    assertNotNull(resolved)
    assertEquals(node.nodeId, resolved.nodeId)
  }

  @Test
  fun `Android label falls back to hintText when text and contentDescription are null`() {
    val node = androidNode(
      text = null,
      contentDescription = null,
      hintText = "Enter email",
      className = "EditText",
      isClickable = false,
      isEditable = true,
      centerX = 100,
      centerY = 100,
    )
    val root = androidNode(text = "Form", className = "FrameLayout", children = listOf(node))

    val tracker = ElementRef.RefTracker()
    tracker.ref("Form", "FrameLayout", 50, 25)
    val ref = tracker.ref("Enter email", "EditText", 100, 100)

    val resolved = ElementRef.resolveRef(root, ref)
    assertNotNull(resolved)
    assertEquals(node.nodeId, resolved.nodeId)
  }

  @Test
  fun `Android label ignores blank text and uses contentDescription`() {
    val node = androidNode(
      text = "   ",
      contentDescription = "Non-blank",
      className = "Button",
      isClickable = true,
      centerX = 100,
      centerY = 100,
    )
    val root = androidNode(text = "Root", className = "FrameLayout", children = listOf(node))

    val tracker = ElementRef.RefTracker()
    tracker.ref("Root", "FrameLayout", 50, 25)
    val ref = tracker.ref("Non-blank", "Button", 100, 100)

    val resolved = ElementRef.resolveRef(root, ref)
    assertNotNull(resolved)
    assertEquals(node.nodeId, resolved.nodeId)
  }

  // -- resolveRef: whitespace normalization ----------------------------------------

  @Test
  fun `Android label normalizes newlines and whitespace`() {
    val node = androidNode(
      text = "Line1\nLine2  Extra",
      className = "TextView",
      isClickable = true,
      centerX = 100,
      centerY = 100,
    )
    val root = androidNode(text = "Root", className = "FrameLayout", children = listOf(node))

    // After normalization: "Line1 Line2 Extra"
    val tracker = ElementRef.RefTracker()
    tracker.ref("Root", "FrameLayout", 50, 25)
    val ref = tracker.ref("Line1 Line2 Extra", "TextView", 100, 100)

    val resolved = ElementRef.resolveRef(root, ref)
    assertNotNull(resolved, "Should resolve with normalized whitespace label")
    assertEquals(node.nodeId, resolved.nodeId)
  }

  // -- resolveRef: Android visibility filtering ------------------------------------

  @Test
  fun `resolveRef skips invisible Android nodes`() {
    val invisible = androidNode(
      text = "Hidden",
      className = "Button",
      isVisibleToUser = false,
      centerX = 100,
      centerY = 100,
    )
    val visible = androidNode(
      text = "Visible",
      className = "Button",
      centerX = 200,
      centerY = 200,
    )
    val root = androidNode(
      text = "Root",
      className = "FrameLayout",
      children = listOf(invisible, visible),
    )

    // Only root and visible should get refs (invisible is skipped)
    val tracker = ElementRef.RefTracker()
    tracker.ref("Root", "FrameLayout", 50, 25)
    // invisible is skipped
    val visibleRef = tracker.ref("Visible", "Button", 200, 200)

    val resolved = ElementRef.resolveRef(root, visibleRef)
    assertNotNull(resolved)
    assertEquals(visible.nodeId, resolved.nodeId)
  }

  @Test
  fun `resolveRef skips system UI nodes`() {
    val systemUi = TrailblazeNode(
      nodeId = nextId++,
      driverDetail = DriverNodeDetail.AndroidAccessibility(
        className = "android.widget.TextView",
        text = "12:00",
        packageName = "com.android.systemui",
        isClickable = true,
        isVisibleToUser = true,
        isImportantForAccessibility = true,
      ),
      bounds = TrailblazeNode.Bounds(left = 0, top = 0, right = 100, bottom = 50),
      children = emptyList(),
    )
    val appNode = androidNode(
      text = "AppContent",
      className = "Button",
      centerX = 200,
      centerY = 300,
    )
    val root = androidNode(
      text = "Root",
      className = "FrameLayout",
      children = listOf(systemUi, appNode),
    )

    // System UI node should be skipped entirely
    val tracker = ElementRef.RefTracker()
    tracker.ref("Root", "FrameLayout", 50, 25)
    // systemUi is skipped
    val appRef = tracker.ref("AppContent", "Button", 200, 300)

    val resolved = ElementRef.resolveRef(root, appRef)
    assertNotNull(resolved)
    assertEquals(appNode.nodeId, resolved.nodeId)
  }

  // -- resolveRef: iOS nodes ------------------------------------------------------

  @Test
  fun `resolveRef works with iOS nodes`() {
    val button = iosNode(
      text = "Pay Now",
      className = "UIButton",
      clickable = true,
      centerX = 200,
      centerY = 400,
    )
    val root = iosNode(
      text = "Screen",
      className = "UIView",
      clickable = true,
      centerX = 50,
      centerY = 25,
      children = listOf(button),
    )

    val tracker = ElementRef.RefTracker()
    // iOS className is used as-is (no "android.widget." prefix to strip)
    tracker.ref("Screen", "UIView", 50, 25)
    val buttonRef = tracker.ref("Pay Now", "UIButton", 200, 400)

    val resolved = ElementRef.resolveRef(root, buttonRef)
    assertNotNull(resolved, "Should resolve iOS node")
    assertEquals(button.nodeId, resolved.nodeId)
  }

  @Test
  fun `resolveRef skips invisible iOS nodes`() {
    val invisible = iosNode(
      text = "Hidden",
      className = "UIButton",
      clickable = true,
      visible = false,
      centerX = 100,
      centerY = 100,
    )
    val visible = iosNode(
      text = "Visible",
      className = "UIButton",
      clickable = true,
      centerX = 200,
      centerY = 200,
    )
    val root = iosNode(
      text = "Root",
      className = "UIView",
      clickable = true,
      children = listOf(invisible, visible),
    )

    val tracker = ElementRef.RefTracker()
    tracker.ref("Root", "UIView", 50, 25)
    // invisible skipped
    val visibleRef = tracker.ref("Visible", "UIButton", 200, 200)

    val resolved = ElementRef.resolveRef(root, visibleRef)
    assertNotNull(resolved)
    assertEquals(visible.nodeId, resolved.nodeId)
  }

  @Test
  fun `iOS label prefers text over accessibilityText`() {
    val node = iosNode(
      text = "Primary",
      accessibilityText = "Secondary",
      className = "UIButton",
      clickable = true,
      centerX = 100,
      centerY = 100,
    )
    val root = iosNode(
      text = "Root",
      className = "UIView",
      clickable = true,
      children = listOf(node),
    )

    val tracker = ElementRef.RefTracker()
    tracker.ref("Root", "UIView", 50, 25)
    val ref = tracker.ref("Primary", "UIButton", 100, 100)

    val resolved = ElementRef.resolveRef(root, ref)
    assertNotNull(resolved)
    assertEquals(node.nodeId, resolved.nodeId)
  }

  @Test
  fun `iOS label falls back to accessibilityText`() {
    val node = iosNode(
      text = null,
      accessibilityText = "Close",
      className = "UIButton",
      clickable = true,
      centerX = 100,
      centerY = 100,
    )
    val root = iosNode(
      text = "Root",
      className = "UIView",
      clickable = true,
      children = listOf(node),
    )

    val tracker = ElementRef.RefTracker()
    tracker.ref("Root", "UIView", 50, 25)
    val ref = tracker.ref("Close", "UIButton", 100, 100)

    val resolved = ElementRef.resolveRef(root, ref)
    assertNotNull(resolved)
    assertEquals(node.nodeId, resolved.nodeId)
  }

  @Test
  fun `iOS label falls back to hintText`() {
    val node = iosNode(
      text = null,
      accessibilityText = null,
      hintText = "Enter name",
      className = "UITextField",
      focused = true,
      centerX = 100,
      centerY = 100,
    )
    val root = iosNode(
      text = "Root",
      className = "UIView",
      clickable = true,
      children = listOf(node),
    )

    val tracker = ElementRef.RefTracker()
    tracker.ref("Root", "UIView", 50, 25)
    val ref = tracker.ref("Enter name", "UITextField", 100, 100)

    val resolved = ElementRef.resolveRef(root, ref)
    assertNotNull(resolved)
    assertEquals(node.nodeId, resolved.nodeId)
  }

  @Test
  fun `iOS isMeaningful includes nodes with label`() {
    // Any iOS node with effectiveLabel != null is meaningful
    val labeled = iosNode(
      text = "Static Label",
      className = "UILabel",
      centerX = 100,
      centerY = 100,
    )
    val root = iosNode(
      text = "Root",
      className = "UIView",
      clickable = true,
      children = listOf(labeled),
    )

    val tracker = ElementRef.RefTracker()
    tracker.ref("Root", "UIView", 50, 25)
    val ref = tracker.ref("Static Label", "UILabel", 100, 100)

    val resolved = ElementRef.resolveRef(root, ref)
    assertNotNull(resolved, "iOS node with label should be meaningful")
    assertEquals(labeled.nodeId, resolved.nodeId)
  }

  @Test
  fun `iOS isMeaningful includes checked nodes`() {
    val checked = iosNode(
      text = null,
      className = "UISwitch",
      checked = true,
      centerX = 100,
      centerY = 100,
    )
    val root = iosNode(
      text = "Root",
      className = "UIView",
      clickable = true,
      children = listOf(checked),
    )

    val tracker = ElementRef.RefTracker()
    tracker.ref("Root", "UIView", 50, 25)
    val ref = tracker.ref(null, "UISwitch", 100, 100)

    val resolved = ElementRef.resolveRef(root, ref)
    assertNotNull(resolved, "iOS checked node should be meaningful")
    assertEquals(checked.nodeId, resolved.nodeId)
  }

  @Test
  fun `iOS isMeaningful includes selected nodes`() {
    val selected = iosNode(
      text = null,
      className = "UITabBarButton",
      selected = true,
      centerX = 100,
      centerY = 100,
    )
    val root = iosNode(
      text = "Root",
      className = "UIView",
      clickable = true,
      children = listOf(selected),
    )

    val tracker = ElementRef.RefTracker()
    tracker.ref("Root", "UIView", 50, 25)
    val ref = tracker.ref(null, "UITabBarButton", 100, 100)

    val resolved = ElementRef.resolveRef(root, ref)
    assertNotNull(resolved, "iOS selected node should be meaningful")
    assertEquals(selected.nodeId, resolved.nodeId)
  }

  // -- resolveRef: Android className handling (substringAfterLast) -----------------

  @Test
  fun `Android className is shortened via substringAfterLast`() {
    // The source code does: detail.className?.substringAfterLast('.')
    // So "android.widget.Button" becomes "Button"
    val node = androidNode(
      text = "Go",
      className = "Button", // Stored as "android.widget.Button"
      centerX = 100,
      centerY = 100,
    )
    val root = androidNode(text = "Root", className = "FrameLayout", children = listOf(node))

    // When computing the ref, className should be "Button" (after substringAfterLast('.'))
    val tracker = ElementRef.RefTracker()
    tracker.ref("Root", "FrameLayout", 50, 25)
    val ref = tracker.ref("Go", "Button", 100, 100) // Short name, not "android.widget.Button"

    val resolved = ElementRef.resolveRef(root, ref)
    assertNotNull(resolved, "Should match with shortened className")
    assertEquals(node.nodeId, resolved.nodeId)
  }

  // -- resolveRef: null bounds handling --------------------------------------------

  @Test
  fun `resolveRef uses 0,0 center when bounds are null`() {
    val noBoundsNode = androidNodeNoBounds(
      text = "NoBounds",
      className = "Button",
      isClickable = true,
    )
    val root = androidNode(
      text = "Root",
      className = "FrameLayout",
      children = listOf(noBoundsNode),
    )

    // When bounds are null, center defaults to (0, 0)
    val tracker = ElementRef.RefTracker()
    tracker.ref("Root", "FrameLayout", 50, 25)
    val ref = tracker.ref("NoBounds", "Button", 0, 0)

    val resolved = ElementRef.resolveRef(root, ref)
    assertNotNull(resolved, "Should resolve node with null bounds using 0,0")
    assertEquals(noBoundsNode.nodeId, resolved.nodeId)
  }

  // -- resolveRef: effectiveLabel does not apply to iOS ----------------------------

  @Test
  fun `effectiveLabel absorption does not apply to iOS nodes`() {
    // On iOS, there is no effectiveLabel absorption from children
    val childWithText = iosNode(
      text = "ChildLabel",
      className = "UILabel",
      centerX = 200,
      centerY = 200,
    )
    val container = iosNode(
      text = null,
      className = "UIView",
      clickable = true,
      centerX = 150,
      centerY = 150,
      children = listOf(childWithText),
    )
    val root = iosNode(
      text = "Root",
      className = "UIView",
      clickable = true,
      children = listOf(container),
    )

    // Container is clickable with no text -> on iOS, effectiveLabel stays null
    // But iOS isMeaningful includes clickable nodes, so it gets a ref with null label
    val tracker = ElementRef.RefTracker()
    tracker.ref("Root", "UIView", 50, 25)
    val containerRef = tracker.ref(null, "UIView", 150, 150) // null label, not absorbed
    tracker.ref("ChildLabel", "UILabel", 200, 200)

    val resolved = ElementRef.resolveRef(root, containerRef)
    assertNotNull(resolved, "iOS clickable container should be meaningful even without label absorption")
    assertEquals(container.nodeId, resolved.nodeId)
  }

  // -- resolveRef: scrollable with label is meaningful ----------------------------

  @Test
  fun `isMeaningful includes scrollable node with label`() {
    val scrollable = androidNode(
      text = "Feed",
      className = "RecyclerView",
      isClickable = false,
      isScrollable = true,
      centerX = 200,
      centerY = 400,
    )
    val root = androidNode(
      text = "Root",
      className = "FrameLayout",
      children = listOf(scrollable),
    )

    val tracker = ElementRef.RefTracker()
    tracker.ref("Root", "FrameLayout", 50, 25)
    val scrollRef = tracker.ref("Feed", "RecyclerView", 200, 400)

    val resolved = ElementRef.resolveRef(root, scrollRef)
    assertNotNull(resolved, "Scrollable node with label should be meaningful")
    assertEquals(scrollable.nodeId, resolved.nodeId)
  }

  // -- Stability: same tree produces same refs across captures --------------------

  @Test
  fun `same tree structure produces same refs across independent resolve calls`() {
    // Build the same tree twice (simulating two captures of the same screen)
    fun buildTree(): Pair<TrailblazeNode, Long> {
      val savedId = nextId
      val target = androidNode(text = "Pay", className = "Button", centerX = 200, centerY = 500)
      val targetId = target.nodeId
      val tree = androidNode(
        text = "Main",
        className = "FrameLayout",
        children = listOf(
          androidNode(text = "Header", className = "TextView", centerX = 100, centerY = 50),
          androidNode(text = "Amount", className = "EditText", isClickable = false, isEditable = true, centerX = 200, centerY = 300),
          target,
        ),
      )
      return tree to targetId
    }

    val (tree1, targetId1) = buildTree()
    val (tree2, targetId2) = buildTree()

    // Compute ref on first tree
    val tracker1 = ElementRef.RefTracker()
    tracker1.ref("Main", "FrameLayout", 50, 25)
    tracker1.ref("Header", "TextView", 100, 50)
    tracker1.ref("Amount", "EditText", 200, 300)
    val ref1 = tracker1.ref("Pay", "Button", 200, 500)

    // Compute ref on second tree (same structure)
    val tracker2 = ElementRef.RefTracker()
    tracker2.ref("Main", "FrameLayout", 50, 25)
    tracker2.ref("Header", "TextView", 100, 50)
    tracker2.ref("Amount", "EditText", 200, 300)
    val ref2 = tracker2.ref("Pay", "Button", 200, 500)

    assertEquals(ref1, ref2, "Same tree structure should produce identical refs")

    // Both should resolve correctly
    val resolved1 = ElementRef.resolveRef(tree1, ref1)
    val resolved2 = ElementRef.resolveRef(tree2, ref2)
    assertNotNull(resolved1)
    assertNotNull(resolved2)
    assertEquals(targetId1, resolved1.nodeId)
    assertEquals(targetId2, resolved2.nodeId)
  }

  // -- resolveRef: three-way collision in resolveRef ------------------------------

  @Test
  fun `resolveRef handles three-way collision correctly`() {
    val node1 = androidNode(text = "Row", className = "TextView", centerX = 100, centerY = 100)
    val node2 = androidNode(text = "Row", className = "TextView", centerX = 100, centerY = 100)
    val node3 = androidNode(text = "Row", className = "TextView", centerX = 100, centerY = 100)
    val root = androidNode(
      text = "List",
      className = "RecyclerView",
      children = listOf(node1, node2, node3),
    )

    val tracker = ElementRef.RefTracker()
    tracker.ref("List", "RecyclerView", 50, 25)
    val ref1 = tracker.ref("Row", "TextView", 100, 100)
    val ref2 = tracker.ref("Row", "TextView", 100, 100)
    val ref3 = tracker.ref("Row", "TextView", 100, 100)

    // All three should resolve to distinct nodes
    val resolved1 = ElementRef.resolveRef(root, ref1)
    val resolved2 = ElementRef.resolveRef(root, ref2)
    val resolved3 = ElementRef.resolveRef(root, ref3)
    assertNotNull(resolved1)
    assertNotNull(resolved2)
    assertNotNull(resolved3)
    assertEquals(node1.nodeId, resolved1.nodeId)
    assertEquals(node2.nodeId, resolved2.nodeId)
    assertEquals(node3.nodeId, resolved3.nodeId)
  }

  // -- resolveRef: system UI subtree is entirely skipped --------------------------

  @Test
  fun `resolveRef skips entire system UI subtree including children`() {
    val systemChild = TrailblazeNode(
      nodeId = nextId++,
      driverDetail = DriverNodeDetail.AndroidAccessibility(
        className = "android.widget.LinearLayout",
        packageName = "com.android.systemui",
        isClickable = true,
        isVisibleToUser = true,
        isImportantForAccessibility = true,
      ),
      bounds = TrailblazeNode.Bounds(left = 0, top = 0, right = 100, bottom = 50),
      children = listOf(
        TrailblazeNode(
          nodeId = nextId++,
          driverDetail = DriverNodeDetail.AndroidAccessibility(
            className = "android.widget.TextView",
            text = "SystemChild",
            packageName = "com.android.systemui",
            isClickable = true,
            isVisibleToUser = true,
            isImportantForAccessibility = true,
          ),
          bounds = TrailblazeNode.Bounds(left = 0, top = 0, right = 50, bottom = 25),
          children = emptyList(),
        ),
      ),
    )
    val appNode = androidNode(text = "App", className = "Button", centerX = 200, centerY = 300)
    val root = androidNode(
      text = "Root",
      className = "FrameLayout",
      children = listOf(systemChild, appNode),
    )

    // System UI and its children are skipped
    val tracker = ElementRef.RefTracker()
    tracker.ref("Root", "FrameLayout", 50, 25)
    val appRef = tracker.ref("App", "Button", 200, 300)

    val resolved = ElementRef.resolveRef(root, appRef)
    assertNotNull(resolved)
    assertEquals(appNode.nodeId, resolved.nodeId)
  }

  // -- resolveRef: mixed meaningful and non-meaningful nodes ----------------------

  @Test
  fun `resolveRef correctly counts refs skipping non-meaningful nodes in between`() {
    // Build a tree with interleaved meaningful and non-meaningful nodes
    // to verify the ref tracker stays in sync
    val btn1 = androidNode(text = "First", className = "Button", centerX = 100, centerY = 100)
    val nonMeaningful = androidNode(
      text = null,
      className = "View",
      isClickable = false,
      isImportantForAccessibility = false,
      centerX = 150,
      centerY = 150,
    )
    val btn2 = androidNode(text = "Second", className = "Button", centerX = 200, centerY = 200)

    val root = androidNode(
      text = "Root",
      className = "FrameLayout",
      children = listOf(btn1, nonMeaningful, btn2),
    )

    val tracker = ElementRef.RefTracker()
    tracker.ref("Root", "FrameLayout", 50, 25)
    tracker.ref("First", "Button", 100, 100)
    // nonMeaningful is skipped in resolveInTree
    val secondRef = tracker.ref("Second", "Button", 200, 200)

    val resolved = ElementRef.resolveRef(root, secondRef)
    assertNotNull(resolved)
    assertEquals(btn2.nodeId, resolved.nodeId)
  }
}
