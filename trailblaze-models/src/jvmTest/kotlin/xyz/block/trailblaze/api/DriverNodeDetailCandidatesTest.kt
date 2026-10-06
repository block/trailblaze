package xyz.block.trailblaze.api

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins [DriverNodeDetail.textCandidates] / [DriverNodeDetail.identifierCandidates] — the
 * dialect-neutral lists the driver-migration selector rewrite scores a recorded selector's text
 * against.
 *
 * The contract that matters here is coverage, not formatting: a field the OLD dialect's selector
 * could be recorded against must be reachable in the NEW dialect's candidate list, or the rewrite
 * scores the right node zero and picks whichever wrong node sits nearest the tap. Every case below
 * names the recorded-selector field it is protecting.
 */
class DriverNodeDetailCandidatesTest {

  @Test
  fun `IosAxe offers AXHelp, because a recorded iOS hint can land only there`() {
    // `hintTextRegex` on an iosMaestro selector matches `.help` on an Axe node
    // (TrailblazeNodeSelectorResolver). A node carrying the hint ONLY in help used to contribute
    // no candidates at all, so the rewrite could not tell it from an unrelated node.
    val detail = DriverNodeDetail.IosAxe(role = "AXTextField", help = "Search contacts")

    assertEquals(listOf("Search contacts"), detail.textCandidates())
  }

  @Test
  fun `IosAxe orders help last, behind the fields a native selector can match`() {
    val detail = DriverNodeDetail.IosAxe(
      label = "Search",
      value = "Ada",
      title = "Contacts",
      help = "Search contacts",
    )

    // Most-specific first: help is the weakest signal and the only one no native `iosAxe`
    // selector can be recorded against.
    assertEquals(listOf("Search", "Ada", "Contacts", "Search contacts"), detail.textCandidates())
  }

  @Test
  fun `IosAxe drops blank fields rather than offering empty candidates`() {
    val detail = DriverNodeDetail.IosAxe(label = "  ", value = "", title = null, help = "Help me")

    // An empty string matches every regex, so a blank field left in would make the node score
    // full marks against any recorded text.
    assertEquals(listOf("Help me"), detail.textCandidates())
  }

  @Test
  fun `IosAxe keeps the accessibility identifier out of the text candidates`() {
    val detail = DriverNodeDetail.IosAxe(label = "Search", uniqueId = "search_field")

    assertEquals(listOf("Search"), detail.textCandidates())
    assertEquals(listOf("search_field"), detail.identifierCandidates())
  }

  @Test
  fun `IosMaestro offers the hint, which is what an Axe help lands in going the other way`() {
    // The reverse direction of the first case: migrating an iosAxe trail back to iosMaestro has
    // to find the same words under `hintText`.
    val detail = DriverNodeDetail.IosMaestro(hintText = "Search contacts")

    assertEquals(listOf("Search contacts"), detail.textCandidates())
  }
}
