package xyz.block.trailblaze.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * [TrailblazeNodeSelectorGenerator.withMatchedElementText]: a recorded assertion's selector also
 * requires the text of the element it matches, so replay fails when that text changes.
 */
class TrailblazeNodeSelectorWithMatchedElementTextTest {

  @Test
  fun `adds the text of an element its id already identifies`() {
    val root = tree(
      DriverNodeDetail.AndroidAccessibility(resourceId = "total", text = "\$12.00"),
      DriverNodeDetail.AndroidAccessibility(resourceId = "tax", text = "\$1.00"),
    )
    val byId = TrailblazeNodeSelector.withMatch(DriverNodeMatch.AndroidAccessibility(resourceIdRegex = "total"))

    val pinned = TrailblazeNodeSelectorGenerator.withMatchedElementText(root, byId)

    assertEquals("total", pinned.androidAccessibility?.resourceIdRegex)
    assertResolvesTo(root, pinned, nodeId = 2)
    assertNoMatch(tree(DriverNodeDetail.AndroidAccessibility(resourceId = "total", text = "\$11.00")), pinned)
  }

  @Test
  fun `replaces a text pattern that tolerates a changed number`() {
    val root = tree(DriverNodeDetail.IosMaestro(resourceId = "model", accessibilityText = "Model Name, iPhone 17"))
    val tolerant = TrailblazeNodeSelector.withMatch(
      DriverNodeMatch.IosMaestro(accessibilityTextRegex = stableTextAnchorRegex("Model Name, iPhone 17")),
    )
    val iPhone18 = tree(DriverNodeDetail.IosMaestro(resourceId = "model", accessibilityText = "Model Name, iPhone 18"))
    assertResolvesTo(iPhone18, tolerant, nodeId = 2)

    val pinned = TrailblazeNodeSelectorGenerator.withMatchedElementText(root, tolerant)

    assertResolvesTo(root, pinned, nodeId = 2)
    assertNoMatch(iPhone18, pinned)
  }

  @Test
  fun `lets a trailing item count change or go away`() {
    val root = tree(DriverNodeDetail.AndroidAccessibility(resourceId = "review", text = "Review sale\n3 items"))
    val byId = TrailblazeNodeSelector.withMatch(DriverNodeMatch.AndroidAccessibility(resourceIdRegex = "review"))

    val pinned = TrailblazeNodeSelectorGenerator.withMatchedElementText(root, byId)

    assertResolvesTo(tree(DriverNodeDetail.AndroidAccessibility(resourceId = "review", text = "Review sale\n2 items")), pinned, nodeId = 2)
    assertResolvesTo(tree(DriverNodeDetail.AndroidAccessibility(resourceId = "review", text = "Review sale")), pinned, nodeId = 2)
    assertNoMatch(tree(DriverNodeDetail.AndroidAccessibility(resourceId = "review", text = "Refund sale\n3 items")), pinned)
  }

  @Test
  fun `keeps a count that is the whole text exact`() {
    val root = tree(DriverNodeDetail.AndroidAccessibility(resourceId = "count", text = "3 items"))
    val byId = TrailblazeNodeSelector.withMatch(DriverNodeMatch.AndroidAccessibility(resourceIdRegex = "count"))

    val pinned = TrailblazeNodeSelectorGenerator.withMatchedElementText(root, byId)

    assertNoMatch(tree(DriverNodeDetail.AndroidAccessibility(resourceId = "count", text = "2 items")), pinned)
  }

  @Test
  fun `reads the field the text is in`() {
    val root = tree(DriverNodeDetail.AndroidAccessibility(resourceId = "search", text = "", hintText = "Search items"))
    val byId = TrailblazeNodeSelector.withMatch(DriverNodeMatch.AndroidAccessibility(resourceIdRegex = "search"))

    val pinned = TrailblazeNodeSelectorGenerator.withMatchedElementText(root, byId)

    assertEquals("Search items", pinned.androidAccessibility?.hintTextRegex)
    assertResolvesTo(root, pinned, nodeId = 2)
    assertNoMatch(
      tree(DriverNodeDetail.AndroidAccessibility(resourceId = "search", text = "coffee", hintText = "Search items")),
      pinned,
    )
  }

  @Test
  fun `an empty android field named only by its label must stay empty`() {
    val field = { text: String ->
      DriverNodeDetail.AndroidAccessibility(resourceId = "name", text = text, contentDescription = "Name")
    }
    val byId = TrailblazeNodeSelector.withMatch(DriverNodeMatch.AndroidAccessibility(resourceIdRegex = "name"))

    val pinned = TrailblazeNodeSelectorGenerator.withMatchedElementText(tree(field("")), byId)

    assertResolvesTo(tree(field("")), pinned, nodeId = 2)
    assertNoMatch(tree(field("coffee")), pinned)
  }

  @Test
  fun `a maestro label pins its case in the text the legacy driver reads`() {
    val root = tree(DriverNodeDetail.IosMaestro(accessibilityText = "Active"))
    val byText = TrailblazeNodeSelector.withMatch(DriverNodeMatch.IosMaestro(textRegex = "Active"))

    val pinned = TrailblazeNodeSelectorGenerator.withMatchedElementText(root, byText)

    assertResolvesTo(root, pinned, nodeId = 2)
    val lowered = Regex(pinned.toTrailblazeElementSelector().textRegex!!, RegexOption.IGNORE_CASE)
    assertEquals(true, lowered.matches("Active"))
    assertEquals(false, lowered.matches("active"))
  }

  @Test
  fun `pins what an axe text field holds as well as its name`() {
    val field = { value: String -> DriverNodeDetail.IosAxe(uniqueId = "email", label = "Email", value = value) }
    val byId = TrailblazeNodeSelector.withMatch(DriverNodeMatch.IosAxe(uniqueId = "email"))

    val pinned = TrailblazeNodeSelectorGenerator.withMatchedElementText(tree(field("jane@example.com")), byId)

    assertResolvesTo(tree(field("jane@example.com")), pinned, nodeId = 2)
    assertNoMatch(tree(field("john@example.com")), pinned)
  }

  @Test
  fun `an empty axe text field must stay empty`() {
    val field = { value: String -> DriverNodeDetail.IosAxe(uniqueId = "email", label = "Email", value = value) }
    val byId = TrailblazeNodeSelector.withMatch(DriverNodeMatch.IosAxe(uniqueId = "email"))

    val pinned = TrailblazeNodeSelectorGenerator.withMatchedElementText(tree(field("")), byId)

    assertResolvesTo(tree(field("")), pinned, nodeId = 2)
    assertNoMatch(tree(field("jane@example.com")), pinned)
  }

  @Test
  fun `an empty compose text field must stay empty`() {
    val field = { value: String ->
      DriverNodeDetail.Compose(testTag = "email", text = "Email", editableText = value)
    }
    val byTag = TrailblazeNodeSelector.withMatch(DriverNodeMatch.Compose(testTag = "email"))

    val pinned = TrailblazeNodeSelectorGenerator.withMatchedElementText(tree(field("")), byTag)

    assertResolvesTo(tree(field("")), pinned, nodeId = 2)
    assertNoMatch(tree(field("jane@example.com")), pinned)
  }

  @Test
  fun `a maestro selector's text keeps its case`() {
    val row = { text: String -> DriverNodeDetail.IosMaestro(resourceId = "status", accessibilityText = text) }
    val byId = TrailblazeNodeSelector.withMatch(DriverNodeMatch.IosMaestro(resourceIdRegex = "status"))

    val pinned = TrailblazeNodeSelectorGenerator.withMatchedElementText(tree(row("Active")), byId)

    assertResolvesTo(tree(row("Active")), pinned, nodeId = 2)
    assertNoMatch(tree(row("active")), pinned)
  }

  @Test
  fun `drops an index the text makes unnecessary`() {
    val field = { text: String -> DriverNodeDetail.AndroidAccessibility(className = "android.widget.EditText", text = text) }
    val root = tree(field("Jane Doe"), field("jane@example.com"))
    val second = TrailblazeNodeSelector(
      androidAccessibility = DriverNodeMatch.AndroidAccessibility(classNameRegex = "android.widget.EditText"),
      index = 1,
    )

    val pinned = TrailblazeNodeSelectorGenerator.withMatchedElementText(root, second)

    assertEquals(null, pinned.index)
    assertResolvesTo(root, pinned, nodeId = 3)
    assertNoMatch(tree(field("Jane Doe"), field("john@example.com")), pinned)
  }

  @Test
  fun `counts the index again among elements showing the same text`() {
    val row = DriverNodeDetail.AndroidAccessibility(className = "android.widget.TextView", text = "Coffee")
    val root = tree(
      DriverNodeDetail.AndroidAccessibility(className = "android.widget.TextView", text = "Tea"),
      row,
      row,
    )
    val third = TrailblazeNodeSelector(
      androidAccessibility = DriverNodeMatch.AndroidAccessibility(classNameRegex = "android.widget.TextView"),
      index = 2,
    )

    val pinned = TrailblazeNodeSelectorGenerator.withMatchedElementText(root, third)

    assertEquals(1, pinned.index)
    assertResolvesTo(root, pinned, nodeId = 4)
  }

  @Test
  fun `matches regex metacharacters in the text literally`() {
    val root = tree(DriverNodeDetail.IosAxe(uniqueId = "email", value = "a+b@example.com"))
    val byId = TrailblazeNodeSelector.withMatch(DriverNodeMatch.IosAxe(uniqueId = "email"))

    val pinned = TrailblazeNodeSelectorGenerator.withMatchedElementText(root, byId)

    assertResolvesTo(root, pinned, nodeId = 2)
    assertNoMatch(tree(DriverNodeDetail.IosAxe(uniqueId = "email", value = "aab@example.com")), pinned)
  }

  @Test
  fun `never records a password field's value`() {
    val root = tree(
      DriverNodeDetail.AndroidAccessibility(resourceId = "password", text = "hunter2", hintText = "Password", isPassword = true),
    )
    val byId = TrailblazeNodeSelector.withMatch(DriverNodeMatch.AndroidAccessibility(resourceIdRegex = "password"))

    assertSame(byId, TrailblazeNodeSelectorGenerator.withMatchedElementText(root, byId))
  }

  @Test
  fun `keeps a selector whose element shows no text`() {
    val root = tree(DriverNodeDetail.AndroidAccessibility(resourceId = "spinner"))
    val byId = TrailblazeNodeSelector.withMatch(DriverNodeMatch.AndroidAccessibility(resourceIdRegex = "spinner"))

    assertSame(byId, TrailblazeNodeSelectorGenerator.withMatchedElementText(root, byId))
  }

  @Test
  fun `keeps a selector that matches more than one element`() {
    val root = tree(
      DriverNodeDetail.AndroidAccessibility(resourceId = "row", text = "Coffee"),
      DriverNodeDetail.AndroidAccessibility(resourceId = "row", text = "Tea"),
    )
    val byId = TrailblazeNodeSelector.withMatch(DriverNodeMatch.AndroidAccessibility(resourceIdRegex = "row"))

    assertSame(byId, TrailblazeNodeSelectorGenerator.withMatchedElementText(root, byId))
  }

  private fun tree(vararg children: DriverNodeDetail): TrailblazeNode = TrailblazeNode(
    nodeId = 1,
    bounds = TrailblazeNode.Bounds(0, 0, 1000, 1000),
    driverDetail = when (children.first()) {
      is DriverNodeDetail.IosAxe -> DriverNodeDetail.IosAxe()
      is DriverNodeDetail.IosMaestro -> DriverNodeDetail.IosMaestro()
      is DriverNodeDetail.Compose -> DriverNodeDetail.Compose()
      else -> DriverNodeDetail.AndroidAccessibility()
    },
    children = children.mapIndexed { i, detail ->
      TrailblazeNode(
        nodeId = i + 2L,
        bounds = TrailblazeNode.Bounds(0, i * 100, 1000, i * 100 + 100),
        driverDetail = detail,
      )
    },
  )

  private fun assertResolvesTo(root: TrailblazeNode, selector: TrailblazeNodeSelector, nodeId: Long) {
    val match = assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(
      TrailblazeNodeSelectorResolver.resolve(root, selector),
    )
    assertEquals(nodeId, match.node.nodeId)
  }

  private fun assertNoMatch(root: TrailblazeNode, selector: TrailblazeNodeSelector) {
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.NoMatch>(TrailblazeNodeSelectorResolver.resolve(root, selector))
  }
}
