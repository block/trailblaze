package xyz.block.trailblaze.capture.logcat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.block.trailblaze.util.IosAppProcess

class IosLogStreamPredicateTest {

  @Test
  fun `builds a three-way OR predicate scoped to the app`() {
    val predicate = buildIosLogStreamPredicate("com.example.sampleapp")
    assertEquals(
      "(process ==[c] \"sampleapp\" OR " +
        "subsystem BEGINSWITH[c] \"com.example.sampleapp\" OR " +
        "processImagePath CONTAINS[c] \"sampleapp\")" +
        NOISE_EXCLUDED,
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
    assertTrue(predicate.startsWith("(process ==[c] \"MyApp\""))
    assertTrue(predicate.contains("subsystem BEGINSWITH[c] \"com.example.MyApp\""))
  }

  @Test
  fun `appId with no dot uses the whole id as the process name`() {
    val predicate = buildIosLogStreamPredicate("MyApp")!!
    assertTrue(predicate.startsWith("(process ==[c] \"MyApp\""))
  }

  @Test
  fun `an installed app is scoped to its own install directory, not its bundle-id tail`() {
    // The bug this guards: an app that runs as `Wallet` from `Wallet.app` under a `.dogfood` bundle
    // id. The bundle-id guess `dogfood` matched none of its lines, so device.log missed its crashes.
    val predicate = buildIosLogStreamPredicate(
      "com.example.wallet.dogfood",
      IosAppProcess(executableName = "Wallet", installDir = "/DEF456/Wallet.app/"),
    )
    assertEquals(
      "(processImagePath CONTAINS[c] \"/DEF456/Wallet.app/\" OR " +
        "subsystem ==[c] \"com.example.wallet.dogfood\" OR " +
        "subsystem BEGINSWITH[c] \"com.example.wallet.dogfood.\")" +
        NOISE_EXCLUDED,
      predicate,
    )
  }

  @Test
  fun `an installed app's scope excludes a co-installed build of the same app`() {
    // A release build installed alongside shares the executable and `.app` names, so only the
    // container and the exact bundle id tell its lines apart. Its crash must not fail this run.
    val predicate = buildIosLogStreamPredicate(
      "com.example.wallet",
      IosAppProcess(executableName = "Wallet", installDir = "/AAA111/Wallet.app/"),
    )!!
    assertFalse(predicate.contains("process ==[c] \"Wallet\""), "a bare process clause matches every Wallet")
    assertFalse(predicate.contains("\"Wallet.app"), "an unqualified .app clause matches every install")
    assertFalse(
      predicate.contains("subsystem BEGINSWITH[c] \"com.example.wallet\""),
      "a bare prefix also matches com.example.wallet.dogfood",
    )
    assertTrue(predicate.contains("subsystem BEGINSWITH[c] \"com.example.wallet.\""))
  }

  @Test
  fun `an installed app with no known install directory falls back to its executable name`() {
    val predicate = buildIosLogStreamPredicate(
      "com.example.wallet.dogfood",
      IosAppProcess(executableName = "Wallet", installDir = null),
    )
    assertEquals(
      "(process ==[c] \"Wallet\" OR " +
        "subsystem BEGINSWITH[c] \"com.example.wallet.dogfood\" OR " +
        "processImagePath CONTAINS[c] \"Wallet\")" +
        NOISE_EXCLUDED,
      predicate,
    )
  }

  @Test
  fun `the command scopes to the installed app`() {
    val installed = IosAppProcess(executableName = "Wallet", installDir = "/DEF456/Wallet.app/")
    val command = buildIosLogStreamCommand("SIM-1", "com.example.wallet.dogfood", installed)
    assertEquals(buildIosLogStreamPredicate("com.example.wallet.dogfood", installed), command.predicate())
    assertTrue(command.isAppScoped)
  }

  @Test
  fun `XCTest's and the network stack's chatter inside the app are left out of every app scope`() {
    // XCTest reads the screen from inside the app's process, so an app-scoped clause matched its
    // lines: 2.7 million of them in one iPad session's device.log, beside 93,000 of the app's own.
    val installed = IosAppProcess(executableName = "Wallet", installDir = "/DEF456/Wallet.app/")
    for (predicate in listOf(
      buildIosLogStreamPredicate("com.example.wallet", installed)!!,
      buildIosLogStreamPredicate("com.example.wallet")!!,
    )) {
      assertTrue(predicate.endsWith(NOISE_EXCLUDED), predicate)
      // The exclusion must bind to the whole OR, not to its last clause.
      assertTrue(predicate.startsWith("("), predicate)
    }
  }

  @Test
  fun `returns null for null appId`() {
    assertNull(buildIosLogStreamPredicate(null))
  }

  @Test
  fun `returns null when the last component is blank`() {
    // A trailing dot yields a blank process name; `process == ""` matches nothing, so the caller
    // falls back to errors and faults rather than silently recording zero lines.
    assertNull(buildIosLogStreamPredicate("com.example."))
  }

  @Test
  fun `a known app scopes the stream to that app`() {
    val command = buildIosLogStreamCommand("SIM-1", "com.example.sampleapp")
    assertEquals(buildIosLogStreamPredicate("com.example.sampleapp"), command.predicate())
    assertTrue(command.isAppScoped)
    assertEquals(listOf("xcrun", "simctl", "spawn", "SIM-1", "log", "stream"), command.argv.take(6))
  }

  @Test
  fun `no app keeps only errors and faults instead of the whole simulator log`() {
    val command = buildIosLogStreamCommand("SIM-1", null)
    assertEquals("messageType == error OR messageType == fault", command.predicate())
    assertFalse(command.isAppScoped)
  }

  @Test
  fun `an app id with no usable process name also keeps only errors and faults`() {
    val command = buildIosLogStreamCommand("SIM-1", "com.example.")
    assertEquals(IOS_UNSCOPED_LOG_PREDICATE, command.predicate())
    assertFalse(command.isAppScoped)
  }

  private companion object {
    const val NOISE_EXCLUDED = " AND NOT subsystem BEGINSWITH[c] \"com.apple.dt.xctest\" AND " +
      "NOT (subsystem BEGINSWITH[c] \"com.apple.network\" AND messageType != error AND messageType != fault)"
  }

  private fun IosLogStreamCommand.predicate(): String? {
    val flags = argv.withIndex().filter { it.value == "--predicate" }
    assertEquals(1, flags.size, "expected exactly one --predicate in $argv")
    return argv.getOrNull(flags.single().index + 1)
  }
}
