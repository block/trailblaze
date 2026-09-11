package xyz.block.trailblaze.device

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Pins the outcome contract of [verifyPmClearSucceeded] and its install probe
 * [installedAccordingToPmList] — the check behind `clearAppData` on both transports. Rationale for
 * the contract lives on those two declarations; these tests pin its edges.
 *
 * `pm clear`'s reporting was confirmed against a device and an emulator: `Success` with exit 0,
 * `Failed` with exit 1, both on stdout only.
 */
class PmClearOutcomeTest {

  /** An unfiltered `pm list packages` that reports [appIds] (plus enough noise to be realistic). */
  private fun pmList(vararg appIds: String): () -> String = {
    (listOf("com.android.systemui", "com.android.settings") + appIds)
      .joinToString("\n") { "package:$it" }
  }

  private val installed = pmList("com.example.app")
  private val notInstalled = pmList("com.other.app")

  @Test
  fun `Success is accepted`() {
    assertEquals(
      PmClearOutcome.CLEARED,
      verifyPmClearSucceeded(appId = "com.example.app", output = "Success", pmListPackagesOutput = installed),
    )
  }

  @Test
  fun `the probe command carries no filter argument`() {
    // The whole safety argument rests on the listing being UNFILTERED (see
    // `installedAccordingToPmList`). Both transports build their probe from this constant, so
    // pinning it here is what stops a future `pm list packages $appId` from inverting the
    // tolerance path into a hard failure with every other test still green.
    assertEquals(listOf("pm", "list", "packages"), PM_LIST_PACKAGES_ARGV)
  }

  @Test
  fun `CRLF line endings are accepted on both the clear output and the listing`() {
    // adb hands back \r\n. Both parsers rely on trim() absorbing the \r; pin it, because a later
    // switch to trimEnd('\n') would fail every successful clear on a real device.
    assertEquals(
      PmClearOutcome.CLEARED,
      verifyPmClearSucceeded(
        appId = "com.example.app",
        output = "Success\r\n",
        pmListPackagesOutput = installed,
      ),
    )
    assertEquals(true, installedAccordingToPmList("com.example.app", "package:com.example.app\r\n"))
  }

  @Test
  fun `a blank appId is rejected rather than read as not installed`() {
    // A blank id matches no `package:` line, so without this guard it would read as absent and
    // turn a malformed call into a tolerated no-op — the outcome this check exists to prevent.
    assertFailsWith<IllegalArgumentException> {
      verifyPmClearSucceeded(appId = "  ", output = "Failed", pmListPackagesOutput = installed)
    }
  }

  @Test
  fun `an appId carrying a second shell word is rejected`() {
    assertFailsWith<IllegalArgumentException> {
      verifyPmClearSucceeded(
        appId = "com.example.app --user 0",
        output = "Failed",
        pmListPackagesOutput = installed,
      )
    }
  }

  @Test
  fun `an appId carrying shell metacharacters is rejected before it can reach a shell`() {
    // The host transport joins its argv into one string the device's `sh` interprets, so an id like
    // this would run `id` as a second command. Both transports call the validator BEFORE executing;
    // a check inside the verifier alone would be too late, since Kotlin evaluates the `pm clear`
    // output argument first.
    listOf(
      "com.example.app;id",
      "com.example.app && id",
      "com.example.app\$(id)",
      "com.example.app`id`",
      "com.example.app|id",
      "com.example.app\nid",
    ).forEach { hostile ->
      assertFailsWith<IllegalArgumentException>("expected rejection of '$hostile'") {
        validateClearAppDataAppId(hostile)
      }
    }
  }

  @Test
  fun `an ordinary package id passes the pre-exec validator`() {
    validateClearAppDataAppId("com.example.app")
    validateClearAppDataAppId("com.example.app.debug")
  }

  @Test
  fun `Success is accepted with surrounding whitespace and a trailing newline`() {
    verifyPmClearSucceeded(
      appId = "com.example.app",
      output = "  Success  \n",
      pmListPackagesOutput = installed,
    )
  }

  @Test
  fun `Success is accepted when transport noise precedes it`() {
    verifyPmClearSucceeded(
      appId = "com.example.app",
      output = "WARNING: linker: unused DT entry\nSuccess\n",
      pmListPackagesOutput = installed,
    )
  }

  @Test
  fun `Failed on an installed package throws`() {
    val error = assertFailsWith<IllegalStateException> {
      verifyPmClearSucceeded(appId = "com.example.app", output = "Failed", pmListPackagesOutput = installed)
    }
    // The message must name the package and quote the raw output — this error is the only place a
    // failed reset is reported, so it has to be diagnosable from the log line alone.
    assertTrue(error.message!!.contains("com.example.app"), error.message)
    assertTrue(error.message!!.contains("Failed"), error.message)
  }

  @Test
  fun `Failed on a package that is not installed is tolerated`() {
    // The standing case: a companion package that only exists on some hardware. The caller
    // documents the clear as a no-op off that hardware, and honoring that here is what keeps every
    // such caller working without a pre-check of its own. Reported as a distinct outcome so a
    // caller can say "nothing to clear" instead of claiming it cleared something.
    assertEquals(
      PmClearOutcome.PACKAGE_NOT_INSTALLED,
      verifyPmClearSucceeded(
        appId = "com.example.absent",
        output = "Failed",
        pmListPackagesOutput = notInstalled,
      ),
    )
  }

  @Test
  fun `the probe runs exactly once on the failure path`() {
    // Each probe call is a device round-trip, and this path is hot: a launch step that clears a
    // hardware-specific companion package pays it on every run where that package is absent.
    var calls = 0
    verifyPmClearSucceeded(
      appId = "com.example.absent",
      output = "Failed",
      pmListPackagesOutput = { calls++; notInstalled() },
    )
    assertEquals(1, calls)
  }

  @Test
  fun `a probe that throws surfaces the clear output and keeps the cause`() {
    // Without this the probe's own exception replaces the report, losing the package id and what
    // `pm clear` actually said — the diagnosability this check exists to add — precisely when the
    // transport is sickest.
    val probeError = IllegalStateException("shell is wedged")
    val error = assertFailsWith<IllegalStateException> {
      verifyPmClearSucceeded(
        appId = "com.example.app",
        output = "Failed",
        pmListPackagesOutput = { throw probeError },
      )
    }
    assertTrue(error.message!!.contains("com.example.app"), error.message)
    assertTrue(error.message!!.contains("Failed"), error.message)
    assertEquals(probeError, error.cause)
  }

  @Test
  fun `empty output on an installed package throws because the clear cannot be confirmed`() {
    assertFailsWith<IllegalStateException> {
      verifyPmClearSucceeded(appId = "com.example.app", output = "", pmListPackagesOutput = installed)
    }
  }

  @Test
  fun `an unrelated error line on an installed package throws`() {
    assertFailsWith<IllegalStateException> {
      verifyPmClearSucceeded(
        appId = "com.example.app",
        output = "Error: java.lang.SecurityException: Neither user 2000 nor current process",
        pmListPackagesOutput = installed,
      )
    }
  }

  @Test
  fun `a line merely containing Success does not count as success`() {
    // Guards the check against loosening to `contains("Success")`: `pm clear` prints the bare
    // token, so a substring match would let genuine failure text that happens to mention the word
    // through as a pass.
    val error = assertFailsWith<IllegalStateException> {
      verifyPmClearSucceeded(
        appId = "com.example.app",
        output = "Success was not reported",
        pmListPackagesOutput = installed,
      )
    }
    assertEquals(true, error.message!!.contains("Success was not reported"))
  }

  @Test
  fun `the install probe is not consulted on the success path`() {
    // The probe costs a device round-trip, so it must stay off the common path. A probe that
    // throws if called proves the success branch never reaches it.
    verifyPmClearSucceeded(
      appId = "com.example.app",
      output = "Success",
      pmListPackagesOutput = { error("the install probe must not run when the clear succeeded") },
    )
  }

  // --- The probe itself: absence must be POSITIVELY established, never merely unproven. ---

  @Test
  fun `a package present in the listing reads as installed`() {
    assertEquals(
      true,
      installedAccordingToPmList("com.example.app", "package:com.other\npackage:com.example.app\n"),
    )
  }

  @Test
  fun `a package absent from a credible listing reads as not installed`() {
    assertEquals(false, installedAccordingToPmList("com.example.app", "package:com.other\n"))
  }

  @Test
  fun `a listing with no package lines throws rather than reading as absent`() {
    // The load-bearing case, and the one a filtered `pm list packages <id>` could not distinguish:
    // a wedged shell or a failed `pm` prints nothing, which must NOT be tolerated as absence — that
    // would silently accept the failed clear this whole check exists to report.
    val error = assertFailsWith<IllegalStateException> {
      installedAccordingToPmList("com.example.app", "")
    }
    assertTrue(error.message!!.contains("com.example.app"), error.message)
  }

  @Test
  fun `a pm error message throws rather than reading as absent`() {
    assertFailsWith<IllegalStateException> {
      installedAccordingToPmList(
        "com.example.app",
        "Error: Could not access the Package Manager. Is the system running?",
      )
    }
  }

  @Test
  fun `the shared parser strips the package prefix and ignores non-package lines`() {
    assertEquals(
      listOf("com.a", "com.b"),
      parsePmListPackages("Warning: something\npackage:com.a\r\n\npackage:com.b\npackage:\n"),
    )
  }

  @Test
  fun `an id that is only a substring of a listed package does not read as installed`() {
    // Whole-line comparison, not `contains`: `pm list packages` output for a longer id must not
    // satisfy a shorter one.
    assertEquals(
      false,
      installedAccordingToPmList("com.example.app", "package:com.example.app.debug\n"),
    )
  }

  @Test
  fun `a failed clear whose probe cannot answer throws instead of being tolerated`() {
    // End-to-end shape of the hole this closes: clear says Failed, probe returns garbage. The
    // outcome must be an exception, never a silent pass.
    assertFailsWith<IllegalStateException> {
      verifyPmClearSucceeded(
        appId = "com.example.app",
        output = "Failed",
        pmListPackagesOutput = { "Error: Could not access the Package Manager." },
      )
    }
  }
}
