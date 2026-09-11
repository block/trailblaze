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

  private fun root(vararg children: TrailblazeNode): TrailblazeNode =
    node(DriverNodeDetail.AndroidAccessibility(), bounds = null, children = children.toList())

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
  fun `ios axe reads label value title help role description and custom actions`() {
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
      listOf("Balance", "$42.00", "Wallet", "Your available funds", "botón", "Edit mode", "Today"),
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
    val screen = root(
      node(DriverNodeDetail.AndroidView(text = "Gone view", isShown = false), bounds = offscreen),
      node(DriverNodeDetail.IosMaestro(text = "Hidden ios node", visible = false), bounds = offscreen),
      node(DriverNodeDetail.AndroidAccessibility(text = "Scrolled away", isVisibleToUser = false), bounds = offscreen),
    )

    assertEquals(
      listOf("Scrolled away"),
      VisibleStringExtractor.extract(screen, screenWidth = 1080, screenHeight = 1920).texts(),
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
    val screen = root(
      node(DriverNodeDetail.AndroidView(text = "9:41", resourceId = "com.android.systemui:id/clock")),
      node(DriverNodeDetail.AndroidMaestro(text = "LTE", resourceId = "com.android.systemui:id/mobile")),
      node(DriverNodeDetail.AndroidAccessibility(text = "Wifi", resourceId = "com.android.systemui:id/wifi")),
      node(DriverNodeDetail.AndroidView(text = "Checkout", resourceId = "com.example.app:id/title")),
    )

    assertEquals(listOf("Checkout"), VisibleStringExtractor.extract(screen).texts())
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
    assertEquals(listOf(0, 300, 200, 50), extracted.bounds)
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
  fun `numeric strings are flagged volatile and worded ones are not`() {
    val screen = root(
      node(DriverNodeDetail.AndroidAccessibility(text = "$1,204.55"), bounds = TrailblazeNode.Bounds(0, 100, 200, 150)),
      node(DriverNodeDetail.AndroidAccessibility(text = "Total due"), bounds = TrailblazeNode.Bounds(0, 200, 200, 250)),
      node(DriverNodeDetail.AndroidAccessibility(text = "2 items"), bounds = TrailblazeNode.Bounds(0, 300, 200, 350)),
    )

    assertEquals(
      listOf("$1,204.55" to true, "Total due" to false, "2 items" to false),
      VisibleStringExtractor.extract(screen).map { it.text to it.volatile },
    )
  }

  @Test
  fun `a clock is volatile whether or not it spells out the meridiem`() {
    val screen = root(
      node(DriverNodeDetail.AndroidAccessibility(text = "9:41"), bounds = TrailblazeNode.Bounds(0, 100, 200, 150)),
      node(DriverNodeDetail.AndroidAccessibility(text = "9:41 PM"), bounds = TrailblazeNode.Bounds(0, 200, 200, 250)),
      node(DriverNodeDetail.AndroidAccessibility(text = "9:41 a.m."), bounds = TrailblazeNode.Bounds(0, 300, 200, 350)),
      node(DriverNodeDetail.AndroidAccessibility(text = "12/25/2026"), bounds = TrailblazeNode.Bounds(0, 400, 200, 450)),
    )

    assertTrue(VisibleStringExtractor.extract(screen).all { it.volatile })
  }

  /**
   * The two errors are not symmetric. A string wrongly marked volatile is dropped from every diff
   * and its regression is never reported; one wrongly left alone is noise a reader can ignore. So
   * a localized date stays copy rather than being guessed at from a month-name list this module
   * has no locale data to build.
   */
  @Test
  fun `a localized date is left as copy, because guessing wrong would hide a real regression`() {
    val screen = root(
      node(DriverNodeDetail.AndroidAccessibility(text = "5 de enero de 2026"), bounds = TrailblazeNode.Bounds(0, 100, 200, 150)),
      node(DriverNodeDetail.AndroidAccessibility(text = "Pedido n.º 4"), bounds = TrailblazeNode.Bounds(0, 200, 200, 250)),
    )

    assertTrue(VisibleStringExtractor.extract(screen).none { it.volatile })
  }

  @Test
  fun `each string carries the ref and bounds needed to point at its element`() {
    val screen = root(
      node(DriverNodeDetail.AndroidAccessibility(text = "Pay"), bounds = TrailblazeNode.Bounds(24, 180, 320, 224), ref = "k42"),
    )

    val extracted = VisibleStringExtractor.extract(screen).single()

    assertEquals("k42", extracted.ref)
    assertEquals(listOf(24, 180, 296, 44), extracted.bounds)
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
}
