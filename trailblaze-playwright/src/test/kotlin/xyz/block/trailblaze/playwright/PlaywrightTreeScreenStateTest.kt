package xyz.block.trailblaze.playwright

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import org.junit.After
import org.junit.Before
import org.junit.Test
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.api.TrailblazeNodeSelectorResolver
import xyz.block.trailblaze.api.DriverNodeMatch

/**
 * [PlaywrightTreeScreenState] is the per-action capture of a recorded replay: the accessibility
 * tree with every element's box, from one snapshot call.
 */
class PlaywrightTreeScreenStateTest {

  private lateinit var playwright: Playwright
  private lateinit var browser: Browser
  private lateinit var page: Page

  @Before
  fun setUp() {
    playwright = Playwright.create()
    browser = playwright.chromium().launch(BrowserType.LaunchOptions().setHeadless(true))
    page = browser.newPage(Browser.NewPageOptions().setViewportSize(1280, 800))
    page.setContent(
      """
      <html><head><title>Open an account</title></head><body>
        <h1>Open a checking account</h1>
        <p id="legal">By continuing you agree to the Account Terms [box=1,2,3,4]</p>
        <a href="/terms">Account Terms</a>
        <label>Email <input type="email"></label>
        <div style="display: contents"><button>Continue</button></div>
        <button id="save">Save</button>
        <button data-testid="later">Later</button>
        <button>Save</button>
        <button aria-label="Close: now">x</button>
        <select><option>English</option><option>Español</option></select>
        <input aria-label="Business name" placeholder="Hello Bakery">
        <iframe id="frame" style="position: absolute; left: 400px; top: 300px; border: 5px solid; padding: 3px"
          srcdoc="<p id=inner>Inside the frame</p><button>Continue</button>"></iframe>
        <div style="height: 3000px"></div>
        <p>Footer</p>
      </body></html>
      """.trimIndent(),
    )
  }

  @After
  fun tearDown() {
    browser.close()
    playwright.close()
  }

  @Test
  fun `the snapshot with its boxes removed is the snapshot taken without them`() {
    val plain = page.locator(":root").ariaSnapshot().lines().filter { it.isNotBlank() }
    val boxed = PlaywrightAriaSnapshot.captureBoxedAriaSnapshot(page)!!
    assertThat(boxed.lines).isEqualTo(plain)
  }

  @Test
  fun `every element node is located, in viewport coordinates`() {
    page.evaluate("window.scrollTo(0, 100)")
    val state = capture(withScreenshot = false)
    val paragraph = state.find("paragraph", "By continuing you agree to the Account Terms [box=1,2,3,4]")
    val rect = page.locator("#legal").boundingBox()!!
    // Playwright rounds each of x, y, width and height, so allow a pixel.
    val b = paragraph.bounds!!
    for ((actual, expected) in listOf(b.left to rect.x, b.top to rect.y, b.right to rect.x + rect.width, b.bottom to rect.y + rect.height)) {
      assertThat(kotlin.math.abs(actual - expected) <= 1.0, "$b vs $rect").isTrue()
    }
    // Scrolled 100px, so the paragraph's viewport top is above its page top.
    assertThat(paragraph.bounds!!.top < 100).isTrue()
    for ((role, name) in listOf("link" to "Account Terms", "textbox" to "Email", "button" to "Continue", "heading" to "Open a checking account")) {
      assertThat(state.find(role, name).bounds, "$role $name").isNotNull()
    }
    // The legacy tree carries the same boxes.
    val legacyButton = state.viewHierarchy.aggregate().first { it.className == "button" && it.text == "Continue" }
    assertThat(legacyButton.x2 > legacyButton.x1).isTrue()
  }

  /** Recorded selectors were generated against the live capture's tree, so this one must be it. */
  @Test
  fun `the replay tree is the live capture's tree, with more boxes`() {
    val live = PlaywrightTrailblazeNodeMapper.mapWithBounds(
      yaml = PlaywrightAriaSnapshot.captureAriaSnapshot(page).yaml,
      page = page,
      viewportWidth = 1280,
      viewportHeight = 800,
    )!!
    val replay = capture(withScreenshot = false).trailblazeNodeTree!!

    // The page's URL and title on the root are all it may differ by, boxes aside.
    val replayAsLive = replay.copy(driverDetail = (replay.driverDetail as DriverNodeDetail.Web).copy(url = null, title = null))
    assertThat(replayAsLive.withoutBounds()).isEqualTo(live.withoutBounds())
    val liveLocated = live.aggregate().filter { it.bounds != null }.map { it.nodeId }.toSet()
    val replayLocated = replay.aggregate().filter { it.bounds != null }.map { it.nodeId }.toSet()
    assertThat(replayLocated.containsAll(liveLocated), "$liveLocated not all in $replayLocated").isTrue()

    // Selectors from the generator's first strategy, and the duplicate-name one, land on the
    // same node in both trees.
    for (match in listOf(
      DriverNodeMatch.Web(cssSelector = "#save"),
      DriverNodeMatch.Web(dataTestId = "later"),
      DriverNodeMatch.Web(ariaRole = "button", ariaNameRegex = "Save", nthIndex = 1),
    )) {
      val selector = TrailblazeNodeSelector(web = match)
      val inLive = TrailblazeNodeSelectorResolver.resolve(live, selector)
      val inReplay = TrailblazeNodeSelectorResolver.resolve(replay, selector)
      assertThat(inLive is TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch, "$match in live: $inLive").isTrue()
      assertThat(
        (inReplay as TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch).node.nodeId,
        "$match",
      ).isEqualTo((inLive as TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch).node.nodeId)
    }
  }

  @Test
  fun `a link's target and a field's placeholder are properties, not page text`() {
    val tree = capture(withScreenshot = false).trailblazeNodeTree!!
    val web = tree.aggregate().mapNotNull { it.driverDetail as? DriverNodeDetail.Web }
    assertThat(web.none { it.ariaName.orEmpty().startsWith("/") }, "${web.map { it.ariaName }}").isTrue()
    assertThat(web.single { it.ariaRole == "link" && it.ariaName == "Account Terms" }.url).isEqualTo("/terms")
    assertThat(web.single { it.ariaRole == "textbox" && it.ariaName == "Business name" }.placeholder)
      .isEqualTo("Hello Bakery")
  }

  @Test
  fun `a text run is boxed where its text is, not where its nearest element is`() {
    val tree = capture(withScreenshot = false).trailblazeNodeTree!!
    // `<label>` isn't in the accessibility tree, so "Email" would otherwise take the page's box.
    val run = tree.findFirst { (it.driverDetail as? DriverNodeDetail.Web)?.let { w -> w.ariaRole == "text" && w.ariaName == "Email" } == true }!!
    @Suppress("UNCHECKED_CAST")
    val rect = page.evaluate(
      """() => { const l = [...document.querySelectorAll('label')].find(e => e.textContent.includes('Email'));
        const r = document.createRange(); r.selectNodeContents(l.firstChild); const b = r.getBoundingClientRect();
        return [b.x, b.y, b.width, b.height]; }""",
    ) as List<Number>
    val b = run.bounds!!
    assertThat(b != tree.bounds, "run still has the page's box").isTrue()
    assertThat(kotlin.math.abs(b.left - rect[0].toDouble()) <= 1 && kotlin.math.abs(b.top - rect[1].toDouble()) <= 1, "$b vs $rect").isTrue()
  }

  @Test
  fun `a rendered duplicate outside the run's element does not keep the run from its own text`() {
    page.setContent(
      """
      <div aria-hidden="true">Terms apply</div>
      <section id="legal" aria-label="Legal" style="margin-top: 300px; height: 200px">Terms apply <a href="/terms">here</a></section>
      """.trimIndent(),
    )
    val tree = capture(withScreenshot = false).trailblazeNodeTree!!
    val run = tree.findFirst { (it.driverDetail as? DriverNodeDetail.Web)?.let { w -> w.ariaRole == "text" && w.ariaName == "Terms apply" } == true }!!
    val section = page.locator("#legal").boundingBox()!!
    val b = run.bounds!!
    // Its own text's box: inside the section, and a line tall rather than the section's 200px.
    assertThat(b.top >= section.y - 1 && b.bottom - b.top < 50, "$b vs section at ${section.y}").isTrue()
  }

  @Test
  fun `text in a shadow root and text slotted into one get their own boxes`() {
    page.setContent(
      """
      <section id="legal" aria-label="Legal" style="margin-top: 300px; height: 300px">
        <div id="host"><span>Slotted terms <a href="/b">two</a></span></div>
      </section>
      <script>
        document.getElementById('host').attachShadow({ mode: 'open' }).innerHTML =
          '<div>Shadow terms <a href="/a">one</a></div><div style="margin-top: 100px"><slot></slot></div>';
      </script>
      """.trimIndent(),
    )
    val tree = capture(withScreenshot = false).trailblazeNodeTree!!
    val section = page.locator("#legal").boundingBox()!!
    for (text in listOf("Shadow terms", "Slotted terms")) {
      val run = tree.findFirst { (it.driverDetail as? DriverNodeDetail.Web)?.let { w -> w.ariaRole == "text" && w.ariaName == text } == true }
      assertThat(run, "no '$text' run in $tree").isNotNull()
      val b = run!!.bounds!!
      assertThat(b.top >= section.y - 1 && b.bottom - b.top < 50, "'$text' $b vs section at ${section.y}").isTrue()
    }
  }

  @Test
  fun `an iframe's content is a tree of its own, in the page's viewport coordinates`() {
    val state = capture(withScreenshot = false)
    val frame = state.frameTrees.single()
    assertThat((frame.driverDetail as DriverNodeDetail.Web).url).isEqualTo("about:srcdoc")
    val inner = frame.findFirst { (it.driverDetail as? DriverNodeDetail.Web)?.ariaName == "Inside the frame" }
    assertThat(inner, "frame tree: $frame").isNotNull()
    // Playwright's own box for the element, which is already relative to the page's viewport.
    val box = page.frameLocator("#frame").locator("#inner").boundingBox()!!
    val b = inner!!.bounds!!
    assertThat(kotlin.math.abs(b.left - box.x) <= 1 && kotlin.math.abs(b.top - box.y) <= 1, "$b vs ${box.x},${box.y}").isTrue()
    // The page tree keeps none of it, so the page's own "Continue" stays the only one there.
    val iframe = state.trailblazeNodeTree!!.findFirst { (it.driverDetail as? DriverNodeDetail.Web)?.ariaRole == "iframe" }!!
    assertThat(iframe.children).isEqualTo(emptyList())
  }

  @Test
  fun `an iframe inside a shadow root does not shift the others' boxes`() {
    page.setContent(
      """
      <div id="host"></div>
      <iframe id="light" style="position: absolute; left: 600px; top: 400px" srcdoc="<p id=inner>Light frame</p>"></iframe>
      <script>
        document.getElementById('host').attachShadow({ mode: 'open' }).innerHTML =
          '<iframe style="position: absolute; left: 20px; top: 20px" srcdoc="<p>Shadow frame</p>"></iframe>';
      </script>
      """.trimIndent(),
    )
    page.frameLocator("#light").locator("#inner").waitFor()
    val frames = capture(withScreenshot = false).frameTrees
    val light = frames.firstNotNullOfOrNull { f -> f.findFirst { (it.driverDetail as? DriverNodeDetail.Web)?.ariaName == "Light frame" } }
    assertThat(light, "frames: $frames").isNotNull()
    val box = page.frameLocator("#light").locator("#inner").boundingBox()!!
    val b = light!!.bounds!!
    assertThat(kotlin.math.abs(b.left - box.x) <= 1 && kotlin.math.abs(b.top - box.y) <= 1, "$b vs ${box.x},${box.y}").isTrue()
  }

  @Test
  fun `a filled secret is kept out of the capture while the page holds it`() {
    val secret = "otp-83716"
    page.getByLabel("Business name").fill(secret)
    page.frameLocator("#frame").locator("body").evaluate("b => { const i = document.createElement('input'); i.value = 'otp-83716'; b.append(i) }")
    // An app can also copy the value into an attribute the tree keeps, like a test id.
    page.evaluate("() => { const b = document.createElement('button'); b.textContent = 'Verify'; b.dataset.testid = 'otp-83716'; document.body.append(b) }")
    val held = PlaywrightTreeScreenState(page, 1280, 800, BrowserEngine.CHROMIUM, withScreenshot = true, secrets = setOf(secret))
    assertThat(held.screenshotBytes).isNull()
    val dump = held.trailblazeNodeTree.toString() + held.frameTrees + held.viewHierarchy
    assertThat(secret !in dump, dump).isTrue()
    // Once the field no longer holds it, the screenshot comes back.
    page.getByLabel("Business name").fill("")
    page.frameLocator("#frame").locator("input").fill("")
    page.evaluate("() => document.querySelector('[data-testid=\"otp-83716\"]').remove()")
    assertThat(PlaywrightTreeScreenState(page, 1280, 800, BrowserEngine.CHROMIUM, withScreenshot = true, secrets = setOf(secret)).screenshotBytes).isNotNull()
  }

  @Test
  fun `a secret the page shows outside a field, or in a contenteditable, keeps the screenshot out`() {
    val secret = "otp-83716"
    val echoed = "b => { const d = document.createElement('div'); d.textContent = 'Code otp-83716 accepted'; b.append(d) }"
    page.locator("body").evaluate(echoed)
    assertThat(PlaywrightTreeScreenState(page, 1280, 800, BrowserEngine.CHROMIUM, withScreenshot = true, secrets = setOf(secret)).screenshotBytes).isNull()
    page.locator("body").evaluate("b => { b.lastElementChild.remove(); const e = document.createElement('div'); e.contentEditable = 'true'; e.setAttribute('aria-hidden', 'true'); e.textContent = 'otp-83716'; b.append(e) }")
    assertThat(PlaywrightTreeScreenState(page, 1280, 800, BrowserEngine.CHROMIUM, withScreenshot = true, secrets = setOf(secret)).screenshotBytes).isNull()
  }

  @Test
  fun `a secret inside a longer one is redacted without leaving the rest of the longer one`() {
    page.getByLabel("Business name").fill("otp-12345")
    val state = PlaywrightTreeScreenState(page, 1280, 800, BrowserEngine.CHROMIUM, withScreenshot = false, secrets = linkedSetOf("otp-1", "otp-12345"))
    val dump = state.trailblazeNodeTree.toString()
    assertThat("2345" !in dump, dump).isTrue()
  }

  @Test
  fun `without secrets a filled value reads as it does on the live tree`() {
    page.getByLabel("Business name").fill("otp-83716")
    assertThat("otp-83716" in capture(withScreenshot = false).trailblazeNodeTree.toString()).isTrue()
  }

  @Test
  fun `the root says which page this is`() {
    val root = capture(withScreenshot = false).trailblazeNodeTree!!.driverDetail as DriverNodeDetail.Web
    assertThat(root.title).isEqualTo("Open an account")
    assertThat(root.url).isEqualTo(page.url())
  }

  @Test
  fun `a recorded selector resolves against the tree to the element's center`() {
    val state = capture(withScreenshot = false)
    val selector = TrailblazeNodeSelector(web = DriverNodeMatch.Web(ariaRole = "button", ariaNameRegex = "Continue"))
    val result = TrailblazeNodeSelectorResolver.resolve(state.trailblazeNodeTree!!, selector)
    val node = (result as TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch).node
    val box = page.getByText("Continue").boundingBox(Locator.BoundingBoxOptions())!!
    assertThat(node.bounds!!.centerX).isEqualTo((box.x + box.width / 2).toInt())
  }

  @Test
  fun `the screenshot is a browser-encoded JPEG only when asked for`() {
    assertThat(capture(withScreenshot = false).screenshotBytes).isNull()
    val jpeg = capture(withScreenshot = true).screenshotBytes!!
    assertThat(jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte()).isTrue()
  }

  @Test
  fun `box-shaped page text outside the key is kept`() {
    val split = PlaywrightAriaSnapshot.splitBoxes(
      """
      - main [box=0,0,100,50]:
        - paragraph [box=0,0,10,10]: see [box=1,2,3,4]
        - text: literal [box=1,2,3,4]
        - 'button "a: b" [box=5,6,7,8]'
        - generic [box=0,0,0,0]:
          - /url: /x [box=1,2,3,4]
      """.trimIndent(),
    )
    assertThat(split.lines).isEqualTo(
      listOf(
        "- main:",
        "  - paragraph: see [box=1,2,3,4]",
        "  - text: literal [box=1,2,3,4]",
        "  - 'button \"a: b\"'",
        "  - generic:",
        "    - /url: /x [box=1,2,3,4]",
      ),
    )
    assertThat(split.boxes).isEqualTo(
      listOf(
        TrailblazeNode.Bounds(0, 0, 100, 50),
        TrailblazeNode.Bounds(0, 0, 10, 10),
        null,
        TrailblazeNode.Bounds(5, 6, 12, 14),
        null,
        null,
      ),
    )
  }

  @Test
  fun `replay capture defaults to the tree alone`() {
    assertThat(WebReplayCapture.fromEnv(null)).isEqualTo(WebReplayCapture.TREE)
    assertThat(WebReplayCapture.fromEnv("tree+jpeg")).isEqualTo(WebReplayCapture.TREE_JPEG)
    assertThat(WebReplayCapture.fromEnv(" OFF ")).isEqualTo(WebReplayCapture.OFF)
  }

  private fun TrailblazeNode.withoutBounds(): TrailblazeNode =
    copy(bounds = null, children = children.map { it.withoutBounds() })

  private fun capture(withScreenshot: Boolean) =
    PlaywrightTreeScreenState(page, 1280, 800, BrowserEngine.CHROMIUM, withScreenshot)

  private fun PlaywrightTreeScreenState.find(role: String, name: String): TrailblazeNode =
    trailblazeNodeTree!!.findFirst { node ->
      val web = node.driverDetail as? DriverNodeDetail.Web
      web?.ariaRole == role && web.ariaName == name
    } ?: error("no $role \"$name\" in the tree")
}
