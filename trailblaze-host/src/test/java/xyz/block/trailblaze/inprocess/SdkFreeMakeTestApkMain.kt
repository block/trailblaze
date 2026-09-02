package xyz.block.trailblaze.inprocess

import java.io.File

/**
 * Entry point [SdkFreeHostBuildTest] runs in a forked JVM whose environment has been cleared, to
 * prove `make-test-apk` needs no Android SDK and no Gradle.
 *
 * A separate `main` rather than an in-process call, because the claim is about the ENVIRONMENT: a
 * JVM cannot unset its own `ANDROID_HOME`, and a test running inside Gradle's test worker inherits
 * everything Gradle sets. Only a fork can be genuinely without them.
 *
 * Args: `<shell.apk> <app.apk> <keystore> <targetPackage> <out.apk>`
 */
object SdkFreeMakeTestApkMain {

  @JvmStatic
  fun main(args: Array<String>) {
    check(System.getenv("ANDROID_HOME") == null) { "ANDROID_HOME leaked into the forked JVM" }
    check(System.getenv("ANDROID_SDK_ROOT") == null) { "ANDROID_SDK_ROOT leaked into the forked JVM" }
    check(System.getenv().keys.none { it.startsWith("GRADLE") }) {
      "Gradle environment leaked into the forked JVM: ${System.getenv().keys.filter { it.startsWith("GRADLE") }}"
    }

    val result = InProcessShellPackager.make(
      InProcessShellPackager.Request(
        shellApk = File(args[0]),
        outputApk = File(args[4]),
        targetPackage = args[3],
        keystore = File(args[2]),
        keyAlias = "test",
        storePassword = InProcessTestApks.PASSWORD.toCharArray(),
        keyPassword = InProcessTestApks.PASSWORD.toCharArray(),
        appApk = File(args[1]),
      ),
    )
    val manifest = ApkZipRewriter.readEntry(result.outputApk, "AndroidManifest.xml")!!
    println("STAMPED=${AndroidBinaryXml.readInstrumentationTargetPackage(manifest)}")
    println("SIGNED=${ApkSigning.certificateSha256Digests(result.outputApk).single()}")
    println("RECORD=${result.buildRecordFile.name}")
  }
}
