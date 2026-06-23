package xyz.block.trailblaze.capture.logcat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IosLogStreamPredicateTest {

  @Test
  fun `builds a three-way OR predicate scoped to the app`() {
    val predicate = buildIosLogStreamPredicate("com.example.sampleapp")
    assertEquals(
      "process ==[c] \"sampleapp\" OR " +
        "subsystem BEGINSWITH[c] \"com.example.sampleapp\" OR " +
        "processImagePath CONTAINS[c] \"sampleapp\"",
      predicate,
    )
  }

  @Test
  fun `matchers are case-insensitive`() {
    // The bug this guards (PR #3944): exact-case `process == "mobilesafari"` matched nothing
    // because the real process is "MobileSafari". Every matcher must carry the [c] modifier.
    val predicate = buildIosLogStreamPredicate("com.apple.mobilesafari")!!
    assertTrue(predicate.contains("process ==[c] "), "process match must be case-insensitive")
    assertTrue(predicate.contains("subsystem BEGINSWITH[c] "), "subsystem match must be case-insensitive")
    assertTrue(predicate.contains("processImagePath CONTAINS[c] "), "image-path match must be case-insensitive")
  }

  @Test
  fun `uses the last bundle-id component as the process name`() {
    val predicate = buildIosLogStreamPredicate("com.example.MyApp")!!
    assertTrue(predicate.startsWith("process ==[c] \"MyApp\""))
    assertTrue(predicate.contains("subsystem BEGINSWITH[c] \"com.example.MyApp\""))
  }

  @Test
  fun `appId with no dot uses the whole id as the process name`() {
    val predicate = buildIosLogStreamPredicate("MyApp")!!
    assertTrue(predicate.startsWith("process ==[c] \"MyApp\""))
  }

  @Test
  fun `returns null for null appId so the caller captures unfiltered`() {
    assertNull(buildIosLogStreamPredicate(null))
  }

  @Test
  fun `returns null when the last component is blank`() {
    // A trailing dot yields a blank process name; `process == ""` matches nothing, so we'd
    // rather capture unfiltered than silently record zero lines.
    assertNull(buildIosLogStreamPredicate("com.example."))
  }
}
