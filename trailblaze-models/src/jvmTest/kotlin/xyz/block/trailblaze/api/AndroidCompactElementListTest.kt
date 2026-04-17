package xyz.block.trailblaze.api

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AndroidCompactElementListTest {

  private var nextId = 0L

  private fun node(
    detail: DriverNodeDetail.AndroidAccessibility = DriverNodeDetail.AndroidAccessibility(),
    bounds: TrailblazeNode.Bounds? = TrailblazeNode.Bounds(0, 0, 100, 50),
    children: List<TrailblazeNode> = emptyList(),
  ): TrailblazeNode = TrailblazeNode(
    nodeId = nextId++,
    driverDetail = detail,
    bounds = bounds,
    children = children,
  )

  @Test
  fun `clickable button with text gets element ref`() {
    val btn = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        className = "android.widget.Button",
        text = "Sign In",
        isClickable = true,
      ),
    )
    val root = node(children = listOf(btn))
    val result = AndroidCompactElementList.build(root)

    assertContains(result.text, "Button \"Sign In\"")
    assertTrue(result.elementNodeIds.contains(0L))
  }

  @Test
  fun `editable text field gets element ref`() {
    val input = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        className = "android.widget.EditText",
        hintText = "Email address",
        isEditable = true,
      ),
    )
    val root = node(children = listOf(input))
    val result = AndroidCompactElementList.build(root)

    assertContains(result.text, "EditText \"Email address\"")
  }

  // -- State annotations --

  @Test
  fun `checked switch shows checked annotation`() {
    val toggle = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        className = "android.widget.Switch",
        text = "Dark Mode",
        isCheckable = true,
        isChecked = true,
        isClickable = true,
      ),
    )
    val root = node(children = listOf(toggle))
    val result = AndroidCompactElementList.build(root)

    assertContains(result.text, "Switch \"Dark Mode\" [checked]")
  }

  @Test
  fun `unchecked checkbox has no checked annotation`() {
    val cb = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        className = "android.widget.CheckBox",
        text = "Accept terms",
        isCheckable = true,
        isChecked = false,
        isClickable = true,
      ),
    )
    val root = node(children = listOf(cb))
    val result = AndroidCompactElementList.build(root)

    assertContains(result.text, "CheckBox \"Accept terms\"")
    assertTrue(!result.text.contains("[checked]"), "Unchecked should have no [checked] annotation")
  }

  @Test
  fun `selected tab shows selected annotation`() {
    val tab = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        className = "android.widget.TabWidget",
        text = "Home",
        isSelected = true,
        isClickable = true,
      ),
    )
    val root = node(children = listOf(tab))
    val result = AndroidCompactElementList.build(root)

    assertContains(result.text, "TabWidget \"Home\" [selected]")
  }

  @Test
  fun `focused input shows focused annotation`() {
    val input = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        className = "android.widget.EditText",
        text = "hello@example.com",
        isEditable = true,
        isFocused = true,
      ),
    )
    val root = node(children = listOf(input))
    val result = AndroidCompactElementList.build(root)

    assertContains(result.text, "EditText \"hello@example.com\" [focused]")
  }

  @Test
  fun `disabled button shows disabled annotation`() {
    val btn = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        className = "android.widget.Button",
        text = "Submit",
        isClickable = true,
        isEnabled = false,
      ),
    )
    val root = node(children = listOf(btn))
    val result = AndroidCompactElementList.build(root)

    assertContains(result.text, "Button \"Submit\" [disabled]")
  }

  @Test
  fun `password field shows password annotation`() {
    val pw = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        className = "android.widget.EditText",
        hintText = "Password",
        isEditable = true,
        isPassword = true,
      ),
    )
    val root = node(children = listOf(pw))
    val result = AndroidCompactElementList.build(root)

    assertContains(result.text, "EditText \"Password\" [password]")
  }

  @Test
  fun `heading shows heading annotation`() {
    val heading = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        className = "android.widget.TextView",
        text = "Account",
        isHeading = true,
      ),
    )
    val root = node(children = listOf(heading))
    val result = AndroidCompactElementList.build(root)

    assertContains(result.text, "TextView \"Account\" [heading]")
  }

  @Test
  fun `error field shows error annotation`() {
    val input = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        className = "android.widget.EditText",
        text = "bad",
        isEditable = true,
        error = "Invalid email",
      ),
    )
    val root = node(children = listOf(input))
    val result = AndroidCompactElementList.build(root)

    assertContains(result.text, "[error: \"Invalid email\"]")
  }

  @Test
  fun `range info shows value percentage`() {
    val seekbar = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        className = "android.widget.SeekBar",
        contentDescription = "Volume",
        isScrollable = true,
        rangeInfo = DriverNodeDetail.AndroidAccessibility.RangeInfo(
          type = 0, min = 0f, max = 100f, current = 75f,
        ),
      ),
    )
    val root = node(children = listOf(seekbar))
    val result = AndroidCompactElementList.build(root)

    assertContains(result.text, "[value: 75%]")
  }

  @Test
  fun `multiple annotations combine`() {
    val input = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        className = "android.widget.EditText",
        hintText = "Password",
        isEditable = true,
        isFocused = true,
        isPassword = true,
      ),
    )
    val root = node(children = listOf(input))
    val result = AndroidCompactElementList.build(root)

    assertContains(result.text, "[focused]")
    assertContains(result.text, "[password]")
  }

  @Test
  fun `collection info shows item count on container`() {
    val item1 = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        className = "android.widget.LinearLayout",
        text = "Network & internet",
        isClickable = true,
      ),
    )
    val item2 = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        className = "android.widget.LinearLayout",
        text = "Connected devices",
        isClickable = true,
      ),
    )
    val recycler = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        className = "androidx.recyclerview.widget.RecyclerView",
        isScrollable = true,
        collectionInfo = DriverNodeDetail.AndroidAccessibility.CollectionInfo(
          rowCount = 10,
          columnCount = 1,
          isHierarchical = false,
        ),
      ),
      children = listOf(item1, item2),
    )
    val root = node(children = listOf(recycler))
    val result = AndroidCompactElementList.build(root)

    val lines = result.text.lines()
    assertTrue(
      lines.any { it.trim() == "RecyclerView [10 items]:" },
      "Should have RecyclerView container header with item count. Actual:\n${result.text}",
    )
    assertTrue(
      lines.any { it.contains("\"Network & internet\"") },
      "Should have indented child element. Actual:\n${result.text}",
    )
    assertTrue(
      lines.any { it.contains("\"Connected devices\"") },
      "Should have second indented child. Actual:\n${result.text}",
    )
  }

  @Test
  fun `system ui nodes are filtered out`() {
    val sysUi = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        className = "android.widget.FrameLayout",
        packageName = "com.android.systemui",
        text = "Status Bar",
        isClickable = true,
      ),
    )
    val appBtn = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        className = "android.widget.Button",
        text = "App Button",
        isClickable = true,
      ),
    )
    val root = node(children = listOf(sysUi, appBtn))
    val result = AndroidCompactElementList.build(root)

    assertTrue(!result.text.contains("Status Bar"), "System UI should be filtered")
    assertContains(result.text, "App Button")
  }

  @Test
  fun `non-visible nodes are filtered out`() {
    val hidden = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        className = "android.widget.Button",
        text = "Hidden",
        isClickable = true,
        isVisibleToUser = false,
      ),
    )
    val visible = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        className = "android.widget.Button",
        text = "Visible",
        isClickable = true,
      ),
    )
    val root = node(children = listOf(hidden, visible))
    val result = AndroidCompactElementList.build(root)

    assertTrue(!result.text.contains("Hidden"))
    assertContains(result.text, "Visible")
  }

  @Test
  fun `structural wrapper nodes are transparent - children promoted`() {
    val btn = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        className = "android.widget.Button",
        text = "Submit",
        isClickable = true,
      ),
    )
    // FrameLayout wrapper: not clickable, no text — should be transparent
    val wrapper = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        className = "android.widget.FrameLayout",
      ),
      children = listOf(btn),
    )
    val root = node(children = listOf(wrapper))
    val result = AndroidCompactElementList.build(root)

    assertTrue(!result.text.contains("FrameLayout"), "Wrapper should not appear")
    assertContains(result.text, "Button \"Submit\"")
  }

  @Test
  fun `child text nodes under clickable parent show as quoted strings`() {
    val subtitle = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        className = "android.widget.TextView",
        text = "Mobile, Wi-Fi, hotspot",
      ),
    )
    val parent = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        className = "android.widget.LinearLayout",
        text = "Network & internet",
        isClickable = true,
      ),
      children = listOf(subtitle),
    )
    val root = node(children = listOf(parent))
    val result = AndroidCompactElementList.build(root)

    assertTrue(result.text.contains("\"Network & internet\""))
    assertContains(result.text, "\"Mobile, Wi-Fi, hotspot\"")
  }

  @Test
  fun `heading nodes are included with heading annotation`() {
    val heading = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        className = "android.widget.TextView",
        text = "Settings",
        isHeading = true,
      ),
    )
    val root = node(children = listOf(heading))
    val result = AndroidCompactElementList.build(root)

    assertContains(result.text, "TextView \"Settings\" [heading]")
  }

  @Test
  fun `empty tree returns no elements found`() {
    val root = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        className = "android.widget.FrameLayout",
      ),
    )
    val result = AndroidCompactElementList.build(root)

    assertEquals("(no elements found)", result.text)
    assertTrue(result.elementNodeIds.isEmpty())
  }

  @Test
  fun `interactive child with same label as parent is still emitted`() {
    // Reproduces the CVV field bug: FrameLayout has content-desc="CVV" and its
    // child EditText has text="CVV". The EditText must not be skipped — the agent
    // needs to see the editable field to know it can type into it.
    val editText = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        className = "android.widget.EditText",
        text = "CVV",
        hintText = "CVV",
        isEditable = true,
        isClickable = true,
        isFocused = true,
      ),
      bounds = TrailblazeNode.Bounds(282, 398, 346, 494),
    )
    val frameLayout = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        className = "android.widget.FrameLayout",
        contentDescription = "CVV",
      ),
      bounds = TrailblazeNode.Bounds(272, 352, 356, 494),
      children = listOf(editText),
    )
    val root = node(children = listOf(frameLayout))
    val result = AndroidCompactElementList.build(root)

    assertContains(result.text, "EditText \"CVV\"")
    assertContains(result.text, "[focused]")
  }

  @Test
  fun `non-interactive child with same label as parent is still skipped`() {
    // A non-interactive TextView with the same text as its parent should still be
    // deduplicated (the original optimization) since the agent can't interact with it.
    val textView = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        className = "android.widget.TextView",
        text = "Settings",
      ),
    )
    val parent = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        className = "android.widget.LinearLayout",
        text = "Settings",
        isClickable = true,
      ),
      children = listOf(textView),
    )
    val root = node(children = listOf(parent))
    val result = AndroidCompactElementList.build(root)

    // The parent "Settings" should appear, but the child should be deduplicated
    val settingsCount = Regex("\"Settings\"").findAll(result.text).count()
    assertEquals(1, settingsCount, "Non-interactive child with same label should be deduplicated")
  }

  @Test
  fun `content description used as fallback label`() {
    val icon = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        className = "android.widget.ImageView",
        contentDescription = "Back",
        isClickable = true,
      ),
    )
    val root = node(children = listOf(icon))
    val result = AndroidCompactElementList.build(root)

    assertContains(result.text, "ImageView \"Back\"")
  }

  // -- Progressive disclosure --

  @Test
  fun `bounds detail adds coordinates to elements`() {
    val btn = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        className = "android.widget.Button",
        text = "Submit",
        isClickable = true,
      ),
      bounds = TrailblazeNode.Bounds(120, 450, 320, 490),
    )
    val root = node(children = listOf(btn))
    val result = AndroidCompactElementList.build(root, setOf(SnapshotDetail.BOUNDS))

    assertContains(result.text, "{x:120,y:450,w:200,h:40}")
  }

  @Test
  fun `no bounds by default`() {
    val btn = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        className = "android.widget.Button",
        text = "Submit",
        isClickable = true,
      ),
      bounds = TrailblazeNode.Bounds(120, 450, 320, 490),
    )
    val root = node(children = listOf(btn))
    val result = AndroidCompactElementList.build(root)

    assertTrue(!result.text.contains("{x:"), "Bounds should not appear by default")
  }

  @Test
  fun `offscreen detail marks elements below viewport`() {
    val visible = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        className = "android.widget.Button",
        text = "Visible",
        isClickable = true,
      ),
      bounds = TrailblazeNode.Bounds(0, 100, 200, 150),
    )
    val offscreen = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        className = "android.widget.Button",
        text = "Below",
        isClickable = true,
      ),
      bounds = TrailblazeNode.Bounds(0, 900, 200, 950),
    )
    val root = node(children = listOf(visible, offscreen))
    val result = AndroidCompactElementList.build(root, setOf(SnapshotDetail.OFFSCREEN), screenHeight = 800)

    assertTrue(!result.text.contains("\"Visible\" (offscreen)"), "Visible element should not be offscreen")
    assertContains(result.text, "\"Below\" (offscreen)")
  }
}
