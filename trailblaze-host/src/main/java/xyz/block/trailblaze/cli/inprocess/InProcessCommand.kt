package xyz.block.trailblaze.cli.inprocess

import picocli.CommandLine
import picocli.CommandLine.Command
import xyz.block.trailblaze.cli.TrailblazeExitCode
import java.util.concurrent.Callable

/**
 * Get an app running under the in-process driver: check whether it can, then build the test APK.
 *
 * The in-process driver runs Trailblaze in the app's process, which means no accessibility service
 * and no separate driver app — but it also means the test APK has to name the app it attaches to and
 * be signed with the app's key. Ordinarily Gradle does both, which puts a team's first in-process
 * run behind adopting a Gradle module. These commands do it to an ALREADY-BUILT shell APK instead,
 * so the first run needs the app's APK (or a fingerprint of it), a key, and nothing else.
 *
 * Examples:
 *   trailblaze inprocess probe-apk --app-apk app.apk
 *   trailblaze inprocess make-test-apk --shell shell.apk --app-apk app.apk \
 *     --target-package com.example.app --keystore debug.keystore --alias androiddebugkey \
 *     --trail login.trail.yaml
 */
@Command(
  name = "inprocess",
  mixinStandardHelpOptions = true,
  description = [
    "Check an app APK for in-process driver compatibility, and build a test APK that drives it.",
  ],
  subcommands = [
    InProcessProbeApkCommand::class,
    MakeTestApkCommand::class,
  ],
)
class InProcessCommand : Callable<Int> {
  override fun call(): Int {
    CommandLine(this).usage(System.out)
    return TrailblazeExitCode.SUCCESS.code
  }
}
