package xyz.block.trailblaze.cli.inprocess

import org.junit.Test
import picocli.CommandLine
import xyz.block.trailblaze.inprocess.apk.ProbeApkMain
import xyz.block.trailblaze.inprocess.apk.ProbeReasonCode
import java.io.File
import kotlin.test.assertEquals

/**
 * The two ways to run the probe must be the same probe.
 *
 * `trailblaze inprocess probe-apk` is what a person runs; `ProbeApkMain` is what the farm's
 * install-time pre-flight runs, because building the whole CLI to read two files would be the
 * expensive half of a cheap gate. They share one implementation, so the only way they can diverge is
 * at the command line — a flag one surface honours and the other spells differently, or defaults
 * that disagree. Either would mean a fingerprint a person checked locally is not the one CI checked.
 */
class ProbeApkSurfaceParityTest {

  @Test
  fun `the same arguments produce the same options on both surfaces`() {
    val argv = arrayOf(
      "app.apk",
      "--shell", "shell.apk",
      "--declared-deps", "deps.yaml",
      "--shell-floor", "floor.yaml",
      "--out", "fingerprint.yaml",
      "--fail-on", "NO_LAUNCHER_ACTIVITY,DEX_OVERLAP_UNCHECKED",
    )
    val lean = ProbeApkMain.parse(argv)
    val cli = parseCli(*argv)

    assertEquals(lean.appApk, cli.appApk)
    assertEquals(lean.shellApk, cli.shellApk)
    assertEquals(lean.declaredDepsFile, cli.declaredDepsFile)
    assertEquals(lean.shellFloorFile, cli.shellFloorFile)
    assertEquals(lean.outFile, cli.outFile)
    assertEquals(lean.failOn, cli.failOn?.toSet())
  }

  @Test
  fun `the optional arguments default the same way on both surfaces`() {
    val lean = ProbeApkMain.parse(arrayOf("app.apk"))
    val cli = parseCli("app.apk")

    assertEquals(File("app.apk"), cli.appApk)
    assertEquals(lean.shellApk, cli.shellApk)
    assertEquals(lean.declaredDepsFile, cli.declaredDepsFile)
    assertEquals(lean.shellFloorFile, cli.shellFloorFile)
    assertEquals(lean.outFile, cli.outFile)
    // Null on both, not "empty set" on one: an empty enforcing set means the gate fails on nothing.
    assertEquals(lean.failOn, cli.failOn?.toSet())
  }

  @Test
  fun `both surfaces spell every reason code the same way`() {
    // `--fail-on` names codes as strings on one surface and as an enum on the other. A code either
    // side could not express would silently drop out of a gate's enforcing set.
    val codes = ProbeReasonCode.entries.joinToString(",") { it.name }
    val everyCode: Set<ProbeReasonCode> = ProbeReasonCode.entries.toSet()
    assertEquals(everyCode, ProbeApkMain.parse(arrayOf("app.apk", "--fail-on", codes)).failOn)
    assertEquals(everyCode, parseCli("app.apk", "--fail-on", codes).failOn?.toSet())
  }

  private fun parseCli(vararg argv: String): InProcessProbeApkCommand {
    val command = InProcessProbeApkCommand()
    CommandLine(command).parseArgs(*argv)
    return command
  }
}
