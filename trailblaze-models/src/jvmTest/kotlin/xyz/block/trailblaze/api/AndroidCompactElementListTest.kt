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
  fun `unchecked checkbox shows unchecked annotation`() {
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

    assertContains(result.text, "CheckBox \"Accept terms\" [unchecked]")
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
  fun `non-important labeled leaf is surfaced (Compose mergeDescendants fallback)`() {
    // Compose's default a11y handling sets isImportantForAccessibility=false on inner
    // Text leaves because their content is supposed to be readable via the merged parent's
    // contentDescription. When that merge doesn't fire (or the parent never gets a usable
    // label) the dropped child carries the only readable text on screen. isMeaningful() now
    // surfaces label-only nodes regardless of the importance flag — the upstream
    // TrailblazeNodeMapper.filterImportantForAccessibility() already restricts kept non-
    // important nodes to LEAVES, so anything reaching this point with a label is a node
    // the LLM needs to see.
    val text = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        className = "android.widget.TextView",
        text = "Compose merged-descendant text",
        isImportantForAccessibility = false,
      ),
    )
    val root = node(children = listOf(text))
    val result = AndroidCompactElementList.build(root)

    assertContains(result.text, "Compose merged-descendant text")
  }

  @Test
  fun `ALL_ELEMENTS detail includes normally-filtered labeled nodes`() {
    // Same TextView as above — should now appear under ALL_ELEMENTS.
    val text = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        className = "android.widget.TextView",
        text = "Decorative text",
        isImportantForAccessibility = false,
      ),
    )
    val root = node(children = listOf(text))
    val result = AndroidCompactElementList.build(root, setOf(SnapshotDetail.ALL_ELEMENTS))

    assertContains(result.text, "Decorative text")
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

  // -- Stable identifier annotation (port of AXe [id=…] treatment) --

  @Test
  fun `resourceId surfaces as id= annotation`() {
    val btn =
      node(
        detail =
          DriverNodeDetail.AndroidAccessibility(
            className = "android.widget.Button",
            text = "Sign In",
            resourceId = "com.example:id/btn_signin",
            isClickable = true,
          ),
      )
    val root = node(children = listOf(btn))
    val result = AndroidCompactElementList.build(root)

    assertContains(result.text, "Button \"Sign In\"")
    assertContains(result.text, "[id=com.example:id/btn_signin]")
  }

  @Test
  fun `uniqueId is preferred over resourceId when both are set`() {
    val btn =
      node(
        detail =
          DriverNodeDetail.AndroidAccessibility(
            className = "android.widget.Button",
            text = "Submit",
            resourceId = "com.example:id/btn_submit",
            uniqueId = "SubmitButton",
            isClickable = true,
          ),
      )
    val root = node(children = listOf(btn))
    val result = AndroidCompactElementList.build(root)

    assertContains(result.text, "[id=SubmitButton]")
    assertTrue(
      !result.text.contains("[id=com.example:id/btn_submit]"),
      "uniqueId should take precedence over resourceId",
    )
  }

  @Test
  fun `id annotation comes before state annotations`() {
    val btn =
      node(
        detail =
          DriverNodeDetail.AndroidAccessibility(
            className = "android.widget.Button",
            text = "Delete",
            resourceId = "com.example:id/btn_delete",
            isClickable = true,
            isEnabled = false,
          ),
      )
    val root = node(children = listOf(btn))
    val result = AndroidCompactElementList.build(root)

    val line = result.text.lines().first { "Delete" in it }
    val idIdx = line.indexOf("[id=")
    val disabledIdx = line.indexOf("[disabled]")
    assertTrue(idIdx > 0 && idIdx < disabledIdx, "id before disabled in: $line")
  }

  // -- Label + value composite for input fields (port of AXe label: value) --

  @Test
  fun `EditText with hintText and text composes as 'hint, text'`() {
    val input =
      node(
        detail =
          DriverNodeDetail.AndroidAccessibility(
            className = "android.widget.EditText",
            text = "user@example.com",
            hintText = "Email address",
            isEditable = true,
          ),
      )
    val root = node(children = listOf(input))
    val result = AndroidCompactElementList.build(root)

    assertContains(result.text, "EditText \"Email address: user@example.com\"")
  }

  @Test
  fun `EditText with only hintText still renders as hint`() {
    val input =
      node(
        detail =
          DriverNodeDetail.AndroidAccessibility(
            className = "android.widget.EditText",
            hintText = "Search",
            isEditable = true,
          ),
      )
    val root = node(children = listOf(input))
    val result = AndroidCompactElementList.build(root)

    assertContains(result.text, "EditText \"Search\"")
    assertTrue(!result.text.contains(":"), "no colon when only hintText present")
  }

  @Test
  fun `labeledByText composes with text when field has no own label`() {
    val input =
      node(
        detail =
          DriverNodeDetail.AndroidAccessibility(
            className = "android.widget.EditText",
            text = "hunter2",
            labeledByText = "Password",
            isEditable = true,
          ),
      )
    val root = node(children = listOf(input))
    val result = AndroidCompactElementList.build(root)

    assertContains(result.text, "EditText \"Password: hunter2\"")
  }

  @Test
  fun `labeledByText is used as label when no text hint or contentDescription`() {
    val input =
      node(
        detail =
          DriverNodeDetail.AndroidAccessibility(
            className = "android.widget.EditText",
            labeledByText = "Email",
            isEditable = true,
          ),
      )
    val root = node(children = listOf(input))
    val result = AndroidCompactElementList.build(root)

    assertContains(result.text, "EditText \"Email\"")
  }

  // -- Scroll-to-reveal affordance for offscreen editable fields --

  @Test
  fun `offscreen editable field above viewport emits scroll up to reveal with no ref`() {
    val search = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        className = "android.widget.EditText",
        hintText = "Search all items",
        isEditable = true,
      ),
      bounds = TrailblazeNode.Bounds(0, -120, 200, -70),
    )
    val root = node(children = listOf(search))
    val result = AndroidCompactElementList.build(root, screenHeight = 800)

    assertContains(result.text, "(scroll up to reveal) EditText \"Search all items\"")
    assertTrue(
      result.elementNodeIds.isEmpty(),
      "Offscreen editable affordance must not get a ref. Actual:\n${result.text}",
    )
    assertTrue(
      !result.text.contains("[ref"),
      "Scroll-to-reveal line must not carry a [ref]. Actual:\n${result.text}",
    )
  }

  @Test
  fun `offscreen editable field below viewport emits scroll down to reveal`() {
    val search = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        className = "android.widget.EditText",
        hintText = "Search all items",
        isEditable = true,
      ),
      bounds = TrailblazeNode.Bounds(0, 900, 200, 950),
    )
    val root = node(children = listOf(search))
    val result = AndroidCompactElementList.build(root, screenHeight = 800)

    assertContains(result.text, "(scroll down to reveal) EditText \"Search all items\"")
    assertTrue(result.elementNodeIds.isEmpty())
  }

  @Test
  fun `offscreen editable field hidden behind overlay still emits scroll to reveal`() {
    // Trips gate 203 (isVisibleToUser=false) rather than gate 210; the bounds-based
    // offscreen signal still classifies it as scrolled-away, so the affordance must fire.
    val search = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        className = "android.widget.EditText",
        hintText = "Search all items",
        isEditable = true,
        isVisibleToUser = false,
      ),
      bounds = TrailblazeNode.Bounds(0, 900, 200, 950),
    )
    val root = node(children = listOf(search))
    val result = AndroidCompactElementList.build(root, screenHeight = 800)

    assertContains(result.text, "(scroll down to reveal) EditText \"Search all items\"")
    assertTrue(result.elementNodeIds.isEmpty())
  }

  @Test
  fun `on-screen editable field still gets a normal ref`() {
    val search = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        className = "android.widget.EditText",
        hintText = "Search all items",
        isEditable = true,
      ),
      bounds = TrailblazeNode.Bounds(0, 100, 200, 150),
    )
    val root = node(children = listOf(search))
    val result = AndroidCompactElementList.build(root, screenHeight = 800)

    assertContains(result.text, "EditText \"Search all items\"")
    assertTrue(!result.text.contains("scroll"), "On-screen field must not get an affordance line")
    assertTrue(result.elementNodeIds.contains(0L), "On-screen editable should still get a ref")
  }

  @Test
  fun `offscreen non-interactive node only increments hidden counter`() {
    val label = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        className = "android.widget.TextView",
        text = "Section header",
        isClickable = true,
      ),
      bounds = TrailblazeNode.Bounds(0, 900, 200, 950),
    )
    val root = node(children = listOf(label))
    val result = AndroidCompactElementList.build(root, screenHeight = 800)

    assertTrue(!result.text.contains("scroll"), "Non-editable offscreen node must not emit an affordance")
    assertContains(result.text, "offscreen elements hidden")
    assertTrue(result.elementNodeIds.isEmpty())
  }

  // -- Invisible container with visible children --

  @Test
  fun `visible children of invisible container still appear in compact list`() {
    // Reproduces a real-world failure where a Compose ViewFactoryHolder wrapper is
    // isVisibleToUser=false but its children (modal title and Dismiss button) have the
    // default isVisibleToUser=true. The compact list must recurse into children rather
    // than dropping the entire subtree.
    val dismissButton = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        className = "android.widget.Button",
        contentDescription = "Dismiss",
        isClickable = true,
        isEnabled = true,
        // isVisibleToUser defaults to true
      ),
    )
    val modalTitle = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        className = "android.widget.TextView",
        text = "Finish setting up your account",
      ),
    )
    val invisibleContainer = node(
      bounds = TrailblazeNode.Bounds(0, 135, 1080, 2132),
      detail = DriverNodeDetail.AndroidAccessibility(
        className = "androidx.compose.ui.viewinterop.ViewFactoryHolder",
        isVisibleToUser = false,
      ),
      children = listOf(modalTitle, dismissButton),
    )
    val navItem = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        className = "android.widget.Button",
        contentDescription = "Checkout",
        isClickable = true,
      ),
    )
    val root = node(children = listOf(invisibleContainer, navItem))
    val result = AndroidCompactElementList.build(root)

    // Dismiss must be emitted as a ref-labeled interactive element, not just as
    // plain text — the agent needs a ref to be able to act on it.
    assertTrue(
      result.elementNodeIds.contains(dismissButton.nodeId),
      "Dismiss button must be emitted as a ref-labeled element",
    )
    assertContains(result.text, "Checkout")
    assertTrue(
      !result.text.contains("ViewFactoryHolder"),
      "Invisible container itself must not be emitted",
    )
  }

  @Test
  fun `invisible leaf node is still filtered out`() {
    // A leaf node (no children) with isVisibleToUser=false should still be excluded —
    // only the "recurse into children" part is the new behaviour; the node itself is skipped.
    val hidden = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        className = "android.widget.Button",
        contentDescription = "Hidden",
        isClickable = true,
        isVisibleToUser = false,
      ),
    )
    val root = node(children = listOf(hidden))
    val result = AndroidCompactElementList.build(root)

    assertTrue(!result.text.contains("Hidden"), "Invisible leaf node must not appear")
  }

  @Test
  fun `hintText equal to text does not duplicate`() {
    // Some inputs echo hint into text; don't render "Search: Search".
    val input =
      node(
        detail =
          DriverNodeDetail.AndroidAccessibility(
            className = "android.widget.EditText",
            text = "Search",
            hintText = "Search",
            isEditable = true,
          ),
      )
    val root = node(children = listOf(input))
    val result = AndroidCompactElementList.build(root)

    assertContains(result.text, "EditText \"Search\"")
    assertTrue(!result.text.contains("Search: Search"), "identical hint/text should dedupe")
  }

  // -- Compose graphicsLayer inverted bounds (right<left / bottom<top) --

  @Test
  fun `horizontally-inverted node stays visible and is tagged bounds-transformed`() {
    // scaleX=-1f flips a visually-centered node to right<left; it must stay in the list (so the
    // resolver can pick it) with coords flagged unreliable, not be dropped as offscreen.
    val flipped =
      node(
        detail =
          DriverNodeDetail.AndroidAccessibility(
            className = "android.widget.TextView",
            text = "Digit1",
            isClickable = true,
          ),
        bounds = TrailblazeNode.Bounds(left = 375, top = 300, right = -705, bottom = 450),
      )
    val root = node(children = listOf(flipped))
    val result = AndroidCompactElementList.build(root, screenHeight = 800)

    assertContains(result.text, "\"Digit1\"")
    assertContains(result.text, "(bounds-transformed)")
  }

  @Test
  fun `vertically-inverted node with negative bottom is not dropped as offscreen`() {
    // bottom<top would trip the old `b.bottom <= 0` offscreen check and drop the node; the vertical
    // axis is unreliable from the transform, so the node must be kept and tagged instead.
    val flipped =
      node(
        detail =
          DriverNodeDetail.AndroidAccessibility(
            className = "android.widget.TextView",
            text = "AmountField",
            isClickable = true,
          ),
        bounds = TrailblazeNode.Bounds(left = 0, top = 100, right = 200, bottom = -50),
      )
    val root = node(children = listOf(flipped))
    val result = AndroidCompactElementList.build(root, screenHeight = 800)

    assertContains(result.text, "\"AmountField\"")
    assertContains(result.text, "(bounds-transformed)")
  }

  @Test
  fun `node genuinely offscreen on a reliable axis is still dropped when only the other axis is inverted`() {
    // Below the fold (top >= screenHeight) plus a horizontal flip: the vertical axis is reliable and
    // says offscreen, so it must still drop — an inversion on one axis is not a free pass on the other.
    val offscreenFlipped =
      node(
        detail =
          DriverNodeDetail.AndroidAccessibility(
            className = "android.widget.Button",
            text = "BelowAndFlipped",
            isClickable = true,
          ),
        bounds = TrailblazeNode.Bounds(left = 200, top = 900, right = -50, bottom = 950),
      )
    val root = node(children = listOf(offscreenFlipped))
    val result = AndroidCompactElementList.build(root, screenHeight = 800)

    assertTrue(
      !result.text.contains("BelowAndFlipped"),
      "A genuinely offscreen node must stay dropped despite a horizontal-bounds inversion",
    )
  }

  @Test
  fun `normal below-fold node is still dropped`() {
    // Regression guard for the offscreen / scroll-to-reveal path: a valid-bounds node below the fold
    // must still be classified offscreen and dropped.
    val below =
      node(
        detail =
          DriverNodeDetail.AndroidAccessibility(
            className = "android.widget.Button",
            text = "BelowFold",
            isClickable = true,
          ),
        bounds = TrailblazeNode.Bounds(left = 0, top = 900, right = 200, bottom = 950),
      )
    val root = node(children = listOf(below))
    val result = AndroidCompactElementList.build(root, screenHeight = 800)

    assertTrue(!result.text.contains("\"BelowFold\""), "Normal below-fold node must stay offscreen-dropped")
  }
}
