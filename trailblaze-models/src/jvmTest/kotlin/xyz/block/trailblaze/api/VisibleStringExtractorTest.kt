package xyz.block.trailblaze.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VisibleStringExtractorTest {

  private var nextId = 0L

  private fun node(
    detail: DriverNodeDetail,
    bounds: TrailblazeNode.Bounds? = TrailblazeNode.Bounds(0, 0, 100, 50),
    ref: String? = null,
    children: List<TrailblazeNode> = emptyList(),
  ): TrailblazeNode = TrailblazeNode(
    nodeId = nextId++,
    ref = ref,
    driverDetail = detail,
    bounds = bounds,
    children = children,
  )

  /** A bare root of the same driver as its children: a capture's tree comes from one driver. */
  private fun root(vararg children: TrailblazeNode): TrailblazeNode {
    val rootDetail = when (children.first().driverDetail) {
      is DriverNodeDetail.AndroidAccessibility -> DriverNodeDetail.AndroidAccessibility()
      is DriverNodeDetail.AndroidView -> DriverNodeDetail.AndroidView()
      is DriverNodeDetail.AndroidMaestro -> DriverNodeDetail.AndroidMaestro()
      is DriverNodeDetail.IosMaestro -> DriverNodeDetail.IosMaestro()
      is DriverNodeDetail.IosAxe -> DriverNodeDetail.IosAxe()
      is DriverNodeDetail.Compose -> DriverNodeDetail.Compose()
      is DriverNodeDetail.Web -> DriverNodeDetail.Web()
    }
    return node(rootDetail, bounds = null, children = children.toList())
  }

  private fun List<ExtractedString>.texts(): List<String> = map { it.text }

  // -- Per-driver field coverage --

  @Test
  fun `android accessibility contributes every localized field`() {
    val screen = root(
      node(
        DriverNodeDetail.AndroidAccessibility(
          text = "Sign in",
          contentDescription = "Close",
          hintText = "Email address",
          stateDescription = "Selected",
          error = "Wrong password",
          tooltipText = "More options",
          labeledByText = "Amount",
          roleDescription = "toggle",
          paneTitle = "Checkout",
        ),
      ),
    )

    assertEquals(
      listOf(
        "Sign in" to VisibleStringSource.TEXT,
        "Close" to VisibleStringSource.CONTENT_DESCRIPTION,
        "Email address" to VisibleStringSource.HINT,
        "Selected" to VisibleStringSource.STATE,
        "Wrong password" to VisibleStringSource.ERROR,
        "More options" to VisibleStringSource.TOOLTIP,
        "Amount" to VisibleStringSource.LABELED_BY,
        "toggle" to VisibleStringSource.ROLE_DESCRIPTION,
        "Checkout" to VisibleStringSource.PANE_TITLE,
      ),
      VisibleStringExtractor.extract(screen).map { it.text to it.source },
    )
  }

  @Test
  fun `ios axe reads label value title help and custom actions`() {
    val screen = root(
      node(
        DriverNodeDetail.IosAxe(
          role = "AXStaticText",
          roleDescription = "botón",
          label = "Balance",
          value = "$42.00",
          title = "Wallet",
          help = "Your available funds",
          customActions = listOf("Edit mode", "Today"),
        ),
      ),
    )

    assertEquals(
      listOf("Balance", "$42.00", "Wallet", "Your available funds", "Edit mode", "Today"),
      VisibleStringExtractor.extract(screen).texts(),
    )
  }

  @Test
  fun `ios maestro reads text accessibility text and hint`() {
    val screen = root(
      node(
        DriverNodeDetail.IosMaestro(
          text = "Continuar",
          accessibilityText = "Continuar al pago",
          hintText = "Correo electrónico",
        ),
      ),
    )

    assertEquals(
      listOf("Continuar", "Continuar al pago", "Correo electrónico"),
      VisibleStringExtractor.extract(screen).texts(),
    )
  }

  @Test
  fun `compose contributes every localized semantic, not just the first three`() {
    val screen = root(
      node(
        DriverNodeDetail.Compose(
          text = "Dark mode",
          editableText = "typed so far",
          contentDescription = "Settings",
          stateDescription = "3 of 10",
          paneTitle = "Appearance",
          errorText = "Pick a theme",
        ),
      ),
    )

    assertEquals(
      listOf("Dark mode", "typed so far", "Settings", "3 of 10", "Appearance", "Pick a theme"),
      VisibleStringExtractor.extract(screen).texts(),
    )
  }

  @Test
  fun `the compose toggle enum is a state token in fixed english, not copy`() {
    val screen = root(
      node(DriverNodeDetail.Compose(text = "Dark mode", toggleableState = "On", stateDescription = "Activado")),
    )

    assertEquals(listOf("Dark mode", "Activado"), VisibleStringExtractor.extract(screen).texts())
  }

  @Test
  fun `web reads the accessible name and not the playwright locator that restates it`() {
    val screen = root(
      node(DriverNodeDetail.Web(ariaRole = "button", ariaName = "Submit", ariaDescriptor = "button \"Submit\"")),
    )

    assertEquals(listOf("Submit"), VisibleStringExtractor.extract(screen).texts())
  }

  @Test
  fun `web reads a field's placeholder and the page's title, and never a link's address`() {
    val screen = node(
      DriverNodeDetail.Web(ariaRole = "document", url = "https://example.com/signup", title = "Open an account"),
      bounds = null,
      children = listOf(
        node(DriverNodeDetail.Web(ariaRole = "textbox", ariaName = "Business name", placeholder = "Hello Bakery")),
        // No label, so the placeholder is its name too: one string, not a text and a hint.
        node(DriverNodeDetail.Web(ariaRole = "textbox", ariaName = "Email", placeholder = "Email")),
        node(DriverNodeDetail.Web(ariaRole = "link", ariaName = "Account Terms", url = "/terms")),
      ),
    )

    assertEquals(
      setOf(
        "Open an account" to VisibleStringSource.TITLE,
        "Business name" to VisibleStringSource.TEXT,
        "Hello Bakery" to VisibleStringSource.HINT,
        "Email" to VisibleStringSource.TEXT,
        "Account Terms" to VisibleStringSource.TEXT,
      ),
      VisibleStringExtractor.extract(screen).map { it.text to it.source }.toSet(),
    )
  }

  // -- Visibility --

  @Test
  fun `a node the platform reports as hidden contributes nothing`() {
    val screen = root(
      node(DriverNodeDetail.AndroidAccessibility(text = "Behind a dialog", isVisibleToUser = false)),
      node(DriverNodeDetail.AndroidAccessibility(text = "On top")),
    )

    assertEquals(listOf("On top"), VisibleStringExtractor.extract(screen).texts())
  }

  @Test
  fun `hidden and covered is dropped, hidden and merely scrolled away is kept`() {
    val screen = root(
      node(
        DriverNodeDetail.AndroidAccessibility(text = "Behind a dialog", isVisibleToUser = false),
        bounds = TrailblazeNode.Bounds(0, 100, 100, 150),
      ),
      node(
        DriverNodeDetail.AndroidAccessibility(text = "Further down the list", isVisibleToUser = false),
        bounds = TrailblazeNode.Bounds(0, 3000, 100, 3050),
      ),
    )

    val extracted = VisibleStringExtractor.extract(screen, screenWidth = 1080, screenHeight = 1920)

    assertEquals(listOf("Further down the list"), extracted.texts())
    assertFalse(extracted.single().visible)
  }

  @Test
  fun `only isVisibleToUser gets read together with geometry, because only it conflates the two`() {
    val offscreen = TrailblazeNode.Bounds(0, 3000, 100, 3050)
    val screens = listOf(
      root(node(DriverNodeDetail.AndroidView(text = "Gone view", isShown = false), bounds = offscreen)),
      root(node(DriverNodeDetail.IosMaestro(text = "Hidden ios node", visible = false), bounds = offscreen)),
      root(node(DriverNodeDetail.AndroidAccessibility(text = "Scrolled away", isVisibleToUser = false), bounds = offscreen)),
    )

    assertEquals(
      listOf("Scrolled away"),
      screens.flatMap { VisibleStringExtractor.extract(it, screenWidth = 1080, screenHeight = 1920).texts() },
    )
  }

  @Test
  fun `system ui strings are not the app's strings`() {
    val screen = root(
      node(DriverNodeDetail.AndroidAccessibility(text = "9:41", packageName = "com.android.systemui")),
      node(DriverNodeDetail.AndroidAccessibility(text = "Checkout", packageName = "com.example.app")),
    )

    assertEquals(listOf("Checkout"), VisibleStringExtractor.extract(screen).texts())
  }

  @Test
  fun `the other android drivers name system ui through the resource id, and are filtered too`() {
    val screens = listOf(
      root(
        node(DriverNodeDetail.AndroidView(text = "9:41", resourceId = "com.android.systemui:id/clock")),
        node(DriverNodeDetail.AndroidView(text = "Checkout", resourceId = "com.example.app:id/title")),
      ),
      root(node(DriverNodeDetail.AndroidMaestro(text = "LTE", resourceId = "com.android.systemui:id/mobile"))),
      root(node(DriverNodeDetail.AndroidAccessibility(text = "Wifi", resourceId = "com.android.systemui:id/wifi"))),
    )

    assertEquals(listOf("Checkout"), screens.flatMap { VisibleStringExtractor.extract(it).texts() })
  }

  @Test
  fun `a hidden container still yields its visible children`() {
    val screen = root(
      node(
        DriverNodeDetail.AndroidAccessibility(text = "Wrapper", isVisibleToUser = false),
        children = listOf(node(DriverNodeDetail.AndroidAccessibility(text = "Real label"))),
      ),
    )

    assertEquals(listOf("Real label"), VisibleStringExtractor.extract(screen).texts())
  }

  @Test
  fun `an offscreen string is recorded rather than dropped`() {
    val screen = root(
      node(DriverNodeDetail.AndroidAccessibility(text = "Below the fold"), bounds = TrailblazeNode.Bounds(0, 2000, 100, 2050)),
    )

    val extracted = VisibleStringExtractor.extract(screen, screenWidth = 1080, screenHeight = 1920)

    assertEquals(listOf("Below the fold"), extracted.texts())
    assertFalse(extracted.single().visible)
  }

  // -- Secrets --

  @Test
  fun `a password field gives up its label and never its value`() {
    val screen = root(
      node(
        DriverNodeDetail.AndroidAccessibility(
          text = "hunter2",
          hintText = "Password",
          isPassword = true,
        ),
      ),
    )

    assertEquals(listOf("Password"), VisibleStringExtractor.extract(screen).texts())
  }

  @Test
  fun `an ios secure field marked only by subrole gives up its label and never its value`() {
    val screen = root(
      node(
        DriverNodeDetail.IosAxe(
          role = "AXTextField",
          subrole = "AXSecureTextField",
          label = "Password",
          value = "hunter2",
        ),
      ),
    )

    assertEquals(listOf("Password"), VisibleStringExtractor.extract(screen).texts())
  }

  @Test
  fun `an ios secure field marked only by role gives up its label and never its value`() {
    val screen = root(
      node(
        DriverNodeDetail.IosAxe(
          role = "AXSecureTextField",
          subrole = null,
          label = "Password",
          value = "hunter2",
        ),
      ),
    )

    assertEquals(listOf("Password"), VisibleStringExtractor.extract(screen).texts())
  }

  // -- Shape of the output --

  @Test
  fun `strings come out in reading order regardless of tree order`() {
    val screen = root(
      node(DriverNodeDetail.AndroidAccessibility(text = "Footer"), bounds = TrailblazeNode.Bounds(0, 900, 200, 950)),
      node(DriverNodeDetail.AndroidAccessibility(text = "Right of header"), bounds = TrailblazeNode.Bounds(300, 100, 500, 150)),
      node(DriverNodeDetail.AndroidAccessibility(text = "Header"), bounds = TrailblazeNode.Bounds(0, 100, 200, 150)),
    )

    assertEquals(
      listOf("Header", "Right of header", "Footer"),
      VisibleStringExtractor.extract(screen).texts(),
    )
  }

  @Test
  fun `a label that is both scrolled away and on screen is reported as on screen`() {
    val screen = root(
      node(DriverNodeDetail.AndroidAccessibility(text = "Recent"), bounds = TrailblazeNode.Bounds(0, -400, 200, -350)),
      node(DriverNodeDetail.AndroidAccessibility(text = "Recent"), bounds = TrailblazeNode.Bounds(0, 300, 200, 350)),
    )

    val extracted = VisibleStringExtractor.extract(screen, screenWidth = 1080, screenHeight = 1920).single()

    assertTrue(extracted.visible)
    assertEquals(listOf(0, 300, 200, 350), extracted.bounds)
  }

  @Test
  fun `the same label repeated across a list is reported once`() {
    val screen = root(
      node(DriverNodeDetail.AndroidAccessibility(text = "Refund"), bounds = TrailblazeNode.Bounds(0, 100, 200, 150)),
      node(DriverNodeDetail.AndroidAccessibility(text = "Refund"), bounds = TrailblazeNode.Bounds(0, 200, 200, 250)),
    )

    assertEquals(listOf("Refund"), VisibleStringExtractor.extract(screen).texts())
  }

  @Test
  fun `one string on two different properties stays two entries`() {
    val screen = root(
      node(DriverNodeDetail.AndroidAccessibility(text = "Search", contentDescription = "Search")),
    )

    assertEquals(
      listOf(VisibleStringSource.TEXT, VisibleStringSource.CONTENT_DESCRIPTION),
      VisibleStringExtractor.extract(screen).map { it.source },
    )
  }

  @Test
  fun `wrapped label text is normalized so a reflow is not a diff`() {
    val screen = root(
      node(DriverNodeDetail.AndroidAccessibility(text = "  Add a\n  new card  ")),
    )

    assertEquals(listOf("Add a new card"), VisibleStringExtractor.extract(screen).texts())
  }

  @Test
  fun `a blank property is not a string`() {
    val screen = root(
      node(DriverNodeDetail.AndroidAccessibility(text = "", contentDescription = "   ", hintText = "Name")),
    )

    assertEquals(listOf("Name"), VisibleStringExtractor.extract(screen).texts())
  }

  @Test
  fun `numeric strings read as volatile and worded ones do not`() {
    assertEquals(
      listOf("$1,204.55" to true, "Total due" to false, "2 items" to false),
      listOf("$1,204.55", "Total due", "2 items").map { it to VolatileText.looksVolatile(it) },
    )
  }

  @Test
  fun `a clock is volatile whether or not it spells out the meridiem`() {
    assertTrue(listOf("9:41", "9:41 PM", "9:41 a.m.", "12/25/2026").all { VolatileText.looksVolatile(it) })
  }

  /**
   * The two errors are not symmetric. A string wrongly judged volatile is dropped from every diff
   * and its regression is never reported; one wrongly left alone is noise a reader can ignore. So
   * a localized date stays copy rather than being guessed at from a month-name list this module
   * has no locale data to build.
   */
  @Test
  fun `a localized date is left as copy, because guessing wrong would hide a real regression`() {
    assertTrue(listOf("5 de enero de 2026", "Pedido n.º 4").none { VolatileText.looksVolatile(it) })
  }

  /** Judged when read, so the extractor records nothing about it: a better rule reaches old sessions. */
  @Test
  fun `extracted strings carry no volatile judgement of their own`() {
    val screen = root(node(DriverNodeDetail.AndroidAccessibility(text = "9:41"), bounds = TrailblazeNode.Bounds(0, 100, 200, 150)))
    val encoded = kotlinx.serialization.json.Json.encodeToString(ExtractedString.serializer(), VisibleStringExtractor.extract(screen).single())
    assertTrue("volatile" !in encoded, encoded)
  }

  @Test
  fun `each string carries the ref and bounds needed to point at its element`() {
    val screen = root(
      node(DriverNodeDetail.AndroidAccessibility(text = "Pay"), bounds = TrailblazeNode.Bounds(24, 180, 320, 224), ref = "k42"),
    )

    val extracted = VisibleStringExtractor.extract(screen).single()

    assertEquals("k42", extracted.ref)
    assertEquals(listOf(24, 180, 320, 224), extracted.bounds, "the corners, as TrailblazeNode.Bounds stores them")
  }

  // -- Legacy captures --

  @Test
  fun `a maestro-shaped capture still yields its strings`() {
    val legacy = ViewHierarchyTreeNode(
      children = listOf(
        ViewHierarchyTreeNode(nodeId = 2, text = "Continue", x1 = 0, y1 = 300, x2 = 200, y2 = 350),
        ViewHierarchyTreeNode(nodeId = 3, accessibilityText = "Back", x1 = 0, y1 = 100, x2 = 100, y2 = 150),
        ViewHierarchyTreeNode(nodeId = 4, text = "hunter2", password = true, x1 = 0, y1 = 200, x2 = 200, y2 = 250),
      ),
    )

    val extracted = VisibleStringExtractor.extract(legacy)

    assertEquals(listOf("Back", "Continue"), extracted.texts())
    assertTrue(extracted.all { it.visible })
  }

  @Test
  fun `a maestro-shaped capture drops system chrome too`() {
    val legacy = ViewHierarchyTreeNode(
      children = listOf(
        ViewHierarchyTreeNode(
          nodeId = 2,
          text = "9:41",
          resourceId = "com.android.systemui:id/clock",
          x1 = 0,
          y1 = 0,
          x2 = 100,
          y2 = 50,
        ),
        ViewHierarchyTreeNode(nodeId = 3, text = "Checkout", x1 = 0, y1 = 100, x2 = 200, y2 = 150),
      ),
    )

    assertEquals(listOf("Checkout"), VisibleStringExtractor.extract(legacy).texts())
  }

  /**
   * `PlaywrightTrailblazeNodeMapper` records `rect.y + scrollY`, a page coordinate, while the
   * dimensions handed to the extractor are the viewport's. Comparing the two marks a whole
   * scrolled page as below the fold.
   */
  @Test
  fun `a scrolled web page keeps its on-screen strings marked visible`() {
    val screen = root(
      node(
        DriverNodeDetail.Web(ariaName = "Checkout"),
        // Two viewports down the page, but on screen: the reader has scrolled to it.
        bounds = TrailblazeNode.Bounds(0, 3000, 200, 3050),
      ),
    )

    val extracted = VisibleStringExtractor.extract(screen, screenWidth = 800, screenHeight = 1200)

    assertEquals(listOf("Checkout"), extracted.texts())
    assertTrue(extracted.single().visible, "web bounds are page coordinates and cannot say otherwise")
  }

  /** Android bounds really are viewport-relative, so the geometry test still applies there. */
  @Test
  fun `an android node past the fold is still marked not visible`() {
    val screen = root(
      node(
        DriverNodeDetail.AndroidAccessibility(text = "Footer"),
        bounds = TrailblazeNode.Bounds(0, 3000, 200, 3050),
      ),
    )

    val extracted = VisibleStringExtractor.extract(screen, screenWidth = 800, screenHeight = 1200)

    assertFalse(extracted.single().visible)
  }

  /**
   * A `graphicsLayer` transform inverts an axis and `boundsInRoot` excludes it, so `width` and
   * `height` come out negative. A negative rectangle in the file is worse than no rectangle.
   */
  @Test
  fun `inverted compose bounds are omitted rather than written as a negative rectangle`() {
    val screen = root(
      node(
        DriverNodeDetail.Compose(text = "Rotated"),
        bounds = TrailblazeNode.Bounds(left = 200, top = 400, right = 100, bottom = 300),
      ),
    )

    val extracted = VisibleStringExtractor.extract(screen, screenWidth = 800, screenHeight = 1200)

    assertEquals(listOf("Rotated"), extracted.texts())
    assertEquals(null, extracted.single().bounds, "an inverted transform has no usable rectangle")
  }

  /**
   * Android returns the placeholder from `getText()` on an empty editable, which is the same
   * quirk `resolveExistingEditableText` works around on the input path. Left alone, one
   * placeholder is filed as two strings and a diff reports the copy twice.
   */
  @Test
  fun `a field showing its placeholder reports it once, as a hint`() {
    val screen = root(
      node(
        DriverNodeDetail.AndroidAccessibility(
          text = "Enter amount",
          hintText = "Enter amount",
          isShowingHintText = true,
        ),
      ),
    )

    val extracted = VisibleStringExtractor.extract(screen)

    assertEquals(listOf("Enter amount"), extracted.texts())
    assertEquals(VisibleStringSource.HINT, extracted.single().source)
  }

  /** With real content typed in, the text is the user's and both fields are the app's copy. */
  @Test
  fun `a field with typed content still reports its text and its hint`() {
    val screen = root(
      node(
        DriverNodeDetail.AndroidAccessibility(
          text = "12.00",
          hintText = "Enter amount",
          isShowingHintText = false,
        ),
      ),
    )

    assertEquals(
      listOf("12.00", "Enter amount"),
      VisibleStringExtractor.extract(screen).texts(),
    )
  }

  // -- Selection follows the agent's element list --

  private fun axe(
    label: String? = null,
    type: String? = "StaticText",
    bounds: TrailblazeNode.Bounds = TrailblazeNode.Bounds(0, 100, 200, 150),
    children: List<TrailblazeNode> = emptyList(),
    roleDescription: String? = null,
  ): TrailblazeNode = node(
    DriverNodeDetail.IosAxe(label = label, type = type, roleDescription = roleDescription),
    bounds = bounds,
    children = children,
  )

  private fun axeRoot(vararg children: TrailblazeNode): TrailblazeNode =
    node(DriverNodeDetail.IosAxe(type = null), bounds = TrailblazeNode.Bounds(0, 0, 402, 874), children = children.toList())

  /** The list caps a label at 120 characters for the prompt; the strings file keeps the copy. */
  @Test
  fun `an element the list shows yields its full text, even past the list's truncation`() {
    val longCopy = "By continuing you agree to the Terms of Service and acknowledge the Privacy Notice, " +
      "including how we use and share your information with partners."
    val screen = axeRoot(axe(label = longCopy))

    val listText = IosAxeCompactElementList.build(screen, screenHeight = 874, screenWidth = 402).text
    val extracted = VisibleStringExtractor.extract(screen, screenWidth = 402, screenHeight = 874)

    assertTrue(longCopy.length > 120 && longCopy !in listText, "the fixture must be one the list truncates")
    assertEquals(listOf(longCopy), extracted.texts())
  }

  @Test
  fun `a structural container the list skips contributes nothing, its labeled child does`() {
    // No label, value, title or type: the list walks through it without a line.
    val screen = axeRoot(
      node(
        DriverNodeDetail.IosAxe(type = null, help = "Container help"),
        children = listOf(axe(label = "Recipient")),
      ),
    )

    assertEquals(listOf("Recipient"), VisibleStringExtractor.extract(screen, 402, 874).texts())
  }

  /**
   * A zero-size wrapper reads as offscreen to the list, which hides the wrapper but still lists its
   * on-screen children. The contract under test is that the on-screen strings are exactly the list's
   * selection.
   */
  @Test
  fun `children of a zero-size wrapper are reported on screen exactly when the list shows them`() {
    val child = axe(label = "Send", type = "Button")
    val screen = axeRoot(
      axe(label = "Amount"),
      node(DriverNodeDetail.IosAxe(type = "Group"), bounds = TrailblazeNode.Bounds(0, 0, 0, 0), children = listOf(child)),
    )

    val shownIds = IosAxeCompactElementList.build(screen, screenHeight = 874, screenWidth = 402).elementNodeIds
    val onScreen = VisibleStringExtractor.extract(screen, 402, 874).filter { it.visible }.texts()

    assertEquals(child.nodeId in shownIds, "Send" in onScreen)
    assertTrue("Amount" in onScreen)
  }

  @Test
  fun `an axe role description is the system's role name and is not reported`() {
    val screen = axeRoot(axe(label = "Pay", type = "Button", roleDescription = "button"))

    assertEquals(
      listOf("Pay" to VisibleStringSource.TEXT),
      VisibleStringExtractor.extract(screen, 402, 874).map { it.text to it.source },
    )
  }

  @Test
  fun `an android role description is app-authored and is still reported`() {
    val screen = root(
      node(DriverNodeDetail.AndroidAccessibility(text = "Tip", roleDescription = "Stepper", isClickable = true)),
    )

    assertTrue(
      VisibleStringExtractor.extract(screen, 1080, 1920)
        .any { it.text == "Stepper" && it.source == VisibleStringSource.ROLE_DESCRIPTION },
    )
  }

  @Test
  fun `an axe element below the fold is reported as not visible`() {
    val screen = axeRoot(
      axe(label = "Recent activity"),
      axe(label = "Load more", type = "Button", bounds = TrailblazeNode.Bounds(0, 1200, 200, 1250)),
    )

    assertEquals(
      listOf("Recent activity" to true, "Load more" to false),
      VisibleStringExtractor.extract(screen, 402, 874).map { it.text to it.visible },
    )
  }

  @Test
  fun `an offscreen android accessibility element is reported as not visible`() {
    val screen = root(
      node(DriverNodeDetail.AndroidAccessibility(text = "Total", isClickable = true), bounds = TrailblazeNode.Bounds(0, 100, 200, 150)),
      node(
        DriverNodeDetail.AndroidAccessibility(text = "Terms apply", isVisibleToUser = false),
        bounds = TrailblazeNode.Bounds(0, 2400, 200, 2450),
      ),
    )

    assertEquals(
      listOf("Total" to true, "Terms apply" to false),
      VisibleStringExtractor.extract(screen, 1080, 1920).map { it.text to it.visible },
    )
  }

  /** The list prints a clickable row's label from its text child; that copy is the child's. */
  @Test
  fun `a label an android row absorbs from its child is reported, attributed to the child`() {
    val caption = node(
      DriverNodeDetail.AndroidAccessibility(text = "Network and internet"),
      bounds = TrailblazeNode.Bounds(40, 110, 300, 140),
      ref = "k7",
    )
    val screen = root(
      node(DriverNodeDetail.AndroidAccessibility(isClickable = true), children = listOf(caption)),
    )

    val extracted = VisibleStringExtractor.extract(screen, 1080, 1920).single()

    assertEquals("Network and internet", extracted.text)
    assertEquals("k7", extracted.ref)
  }

  @Test
  fun `text the android list quotes under its parent is reported`() {
    val screen = root(
      node(
        DriverNodeDetail.AndroidAccessibility(text = "Wi-Fi", isClickable = true),
        children = listOf(node(DriverNodeDetail.AndroidAccessibility(text = "Connected to Home"))),
      ),
    )

    assertEquals(listOf("Wi-Fi", "Connected to Home"), VisibleStringExtractor.extract(screen, 1080, 1920).texts())
  }

  @Test
  fun `an ios maestro container's header label is reported`() {
    val screen = root(
      node(
        DriverNodeDetail.IosMaestro(accessibilityText = "Payment methods", scrollable = true),
        children = listOf(node(DriverNodeDetail.IosMaestro(text = "Visa 4242", clickable = true))),
      ),
    )

    assertEquals(
      setOf("Payment methods", "Visa 4242"),
      VisibleStringExtractor.extract(screen, 402, 874).texts().toSet(),
    )
  }
}
