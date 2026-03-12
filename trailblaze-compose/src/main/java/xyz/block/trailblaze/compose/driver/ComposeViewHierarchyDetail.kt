package xyz.block.trailblaze.compose.driver

import kotlinx.serialization.Serializable

/**
 * Detail types that can be requested for the next Compose view hierarchy snapshot.
 *
 * When the LLM needs more information than the compact semantics tree provides, it can call
 * [ComposeRequestDetailsTool][xyz.block.trailblaze.compose.driver.tools.ComposeRequestDetailsTool]
 * with one or more of these detail types. The next screen state capture will then enrich the view
 * hierarchy text with the requested information for ALL elements.
 *
 * This is a progressive disclosure pattern: the default compact list is token-efficient for most
 * turns, and the LLM can upgrade fidelity when it needs spatial info.
 */
@Serializable
enum class ComposeViewHierarchyDetail {
  /**
   * Include bounding box coordinates for each element. Adds `{x,y,w,h}` after each element's
   * descriptor.
   *
   * Example:
   * ```
   * - Button "Submit" [testTag=submit_btn] {x:120,y:450,w:200,h:40}
   * - View "Home" [testTag=home_link] {x:50,y:10,w:80,h:24}
   * ```
   */
  BOUNDS,
}
