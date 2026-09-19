package xyz.block.trailblaze.inprocessidle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The facts the build and the attach share about the split-APK host. A disagreement here is the
 * silent kind: the attach would find no split, install nothing, and the launch would fall back to
 * the instrumentation host while every log line still says turbo.
 */
class InProcessIdleSplitTest {

  @Test
  fun splitAssetPathDerivesFromTheSameFlavorAsTheInstrumentationAsset() {
    assertEquals(
      "inprocess-idle-apks/trailblaze-inprocess-idle-split-app.apk",
      InProcessIdle.splitAssetPathFor("com.example.app"),
    )
    // Distinct from the instrumentation host's asset, so a bundle staging both stages two files.
    assertFalse(InProcessIdle.splitAssetPathFor("com.example.app") == InProcessIdle.assetPathFor("com.example.app"))
  }

  @Test
  fun pmPathListsTheSplitOnlyWhenItsFileIsPresent() {
    val withSplit = """
      package:/data/app/~~abc==/com.example.app-xyz==/base.apk
      package:/data/app/~~abc==/com.example.app-xyz==/split_trailblaze_inprocess_idle.apk
    """.trimIndent()
    val baseOnly = "package:/data/app/~~abc==/com.example.app-xyz==/base.apk\n"
    val otherSplit = "package:/data/app/~~abc==/com.example.app-xyz==/split_config.xxhdpi.apk\n"
    assertTrue(InProcessIdle.pmPathListsSplit(withSplit))
    assertFalse(InProcessIdle.pmPathListsSplit(baseOnly))
    assertFalse(InProcessIdle.pmPathListsSplit(otherSplit))
    assertFalse(InProcessIdle.pmPathListsSplit(""))
  }

  @Test
  fun removingTheSplitNamesThePackageAndTheSplitNotJustThePackage() {
    // `pm uninstall <package>` alone would remove the APP.
    assertEquals(
      listOf("pm", "uninstall", "com.example.app", InProcessIdle.SPLIT_NAME),
      InProcessIdle.removeSplitShellArgs("com.example.app"),
    )
  }

  @Test
  fun theInstallSessionInheritsTheInstalledAppRatherThanReplacingIt() {
    // Without `-p` the session is a fresh install of the app, which the package manager refuses
    // (INSTALL_FAILED_ALREADY_EXISTS) — and would replace the app under test if it didn't.
    assertEquals(
      listOf("pm", "install-create", "-p", "com.example.app"),
      InProcessIdle.splitInstallCreateShellArgs("com.example.app"),
    )
  }

  @Test
  fun theSessionIdIsReadOutOfTheCreateOutputAndIsAbsentWhenTheCreateWasRefused() {
    assertEquals(
      2107725001,
      InProcessIdle.parseInstallSessionId("Success: created install session [2107725001]\n"),
    )
    assertNull(InProcessIdle.parseInstallSessionId("Error: java.lang.SecurityException: ...\n"))
    assertNull(InProcessIdle.parseInstallSessionId(""))
  }

  @Test
  fun theWriteStreamsTheApkIntoTheSessionUnderTheSplitsOwnFileName() {
    val command = InProcessIdle.splitInstallWriteInnerCommand(
      sessionId = 42,
      apkBase64 = "QUJD",
      sizeBytes = 3,
    )
    // The name the package manager stores the split under — what `pm path` then lists, which is
    // how the attach recognizes it ([pmPathListsSplit]).
    assertTrue(command.contains("split_${InProcessIdle.SPLIT_NAME}.apk"), command)
    // `-S <size>` and the trailing `-`: together they are what makes this a stdin write. Without
    // the size `pm` cannot size the session; without the dash it looks for a file named `-`.
    assertTrue(command.contains("pm install-write -S 3 42 split_${InProcessIdle.SPLIT_NAME}.apk -"), command)
    assertTrue(command.startsWith("printf %s QUJD | base64 -d |"), command)
  }

  @Test
  fun commitAndAbandonNameTheSessionTheCreateReturned() {
    assertEquals(listOf("pm", "install-commit", "42"), InProcessIdle.splitInstallCommitShellArgs(42))
    assertEquals(listOf("pm", "install-abandon", "42"), InProcessIdle.splitInstallAbandonShellArgs(42))
  }

  @Test
  fun onlyASuccessTokenCountsAsAnInstallStepThatRan() {
    assertTrue(InProcessIdle.installOutputSucceeded("Success\n"))
    assertTrue(InProcessIdle.installOutputSucceeded("Success: streamed 12685 bytes\n"))
    assertFalse(
      InProcessIdle.installOutputSucceeded(
        "Failure [INSTALL_FAILED_INVALID_APK: Split not found: trailblaze_inprocess_idle]\n",
      ),
    )
    // A command that printed nothing never ran — the one outcome a token-based check must not
    // read as success, since this transport reports no exit status.
    assertFalse(InProcessIdle.installOutputSucceeded(""))
  }
}
