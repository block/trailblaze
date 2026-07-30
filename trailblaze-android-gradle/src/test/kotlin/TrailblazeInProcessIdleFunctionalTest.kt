import java.io.File
import java.util.zip.ZipFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assume.assumeTrue

/**
 * TestKit coverage for `trailblazeAndroid { inProcessIdle { } }`: task registration + assets wiring,
 * the reflected `signingConfigName` resolution against the fake AGP stand-in, the directed
 * duplicate-suffix error, and (when the machine has an Android SDK) a real end-to-end APK build
 * signed with a test keystore.
 */
class TrailblazeInProcessIdleFunctionalTest {

  private val tempDirs = mutableListOf<File>()

  @AfterTest
  fun cleanup() {
    tempDirs.forEach { it.deleteRecursively() }
  }

  @Test
  fun `inProcessIdle registers the conventional task and wires the staging root into assets`() {
    val projectDir =
      newFixtureProject(
        androidFixtureBuildScript(
          """
          trailblazeAndroid {
            inProcessIdle { targetApplicationId = "com.example.fixtureapp" }
          }

          tasks.register("probeInProcessIdleWiring") {
            val inProcessIdleTaskNames = provider {
              tasks.matching { it.name.startsWith("buildTrailblazeInProcessIdle") }.map { it.name }
            }
            val assetsDirs = provider {
              (project.extensions.getByName("android") as FakeAndroidExtension)
                .sourceSets.getByName("androidTest").assets.srcDirs
                .map {
                  if (it is org.gradle.api.provider.Provider<*>) it.get().toString()
                  else it.toString()
                }
            }
            doLast {
              println("IN_PROCESS_IDLE_TASKS=" + inProcessIdleTaskNames.get())
              println("ASSETS_DIRS=" + assetsDirs.get())
            }
          }
          """
        ),
        tempDirs,
      )

    val result = gradleRunner(projectDir, "probeInProcessIdleWiring").build()

    assertTrue(
      result.output.contains("IN_PROCESS_IDLE_TASKS=[buildTrailblazeInProcessIdleFixtureappApk]"),
      "expected the conventional idle detector task name: ${result.output}",
    )
    assertTrue(
      result.output.contains("inprocess-idle-apk-assets"),
      "expected the idle detector staging root wired into androidTest assets: ${result.output}",
    )
  }

  @Test
  fun `signingConfigName resolves the quad from the module's own signing config by reflection`() {
    val projectDir =
      newFixtureProject(
        androidFixtureBuildScript(
          """
          (extensions.getByName("android") as FakeAndroidExtension).signingConfigs.create("internalRelease").apply {
            storeFile = file("app-signing.keystore")
            storePassword = "storepw"
            keyAlias = "appkey"
          }

          trailblazeAndroid {
            inProcessIdle {
              targetApplicationId = "com.example.fixtureapp"
              signingConfigName = "internalRelease"
            }
          }

          tasks.register("probeInProcessIdleSigning") {
            val inProcessIdleTask = tasks.named(
              "buildTrailblazeInProcessIdleFixtureappApk",
              BuildTrailblazeInProcessIdleApkTask::class.java,
            )
            doLast {
              val t = inProcessIdleTask.get()
              println("SIGNING_STORE=" + t.keystoreFile.get().name)
              println("SIGNING_ALIAS=" + t.keyAlias.get())
              println("SIGNING_KEYPW=" + t.keyPassword.get())
            }
          }
          """
        ),
        tempDirs,
      )

    val result = gradleRunner(projectDir, "probeInProcessIdleSigning").build()

    assertTrue(result.output.contains("SIGNING_STORE=app-signing.keystore"), result.output)
    assertTrue(result.output.contains("SIGNING_ALIAS=appkey"), result.output)
    // keyPassword falls back to the store password when the config doesn't set one.
    assertTrue(result.output.contains("SIGNING_KEYPW=storepw"), result.output)
  }

  @Test
  fun `a missing signing config name is a directed error`() {
    val projectDir =
      newFixtureProject(
        androidFixtureBuildScript(
          """
          trailblazeAndroid {
            inProcessIdle {
              targetApplicationId = "com.example.fixtureapp"
              signingConfigName = "doesNotExist"
            }
          }
          """
        ),
        tempDirs,
      )

    val result = gradleRunner(projectDir, "tasks").buildAndFail()

    assertTrue(result.output.contains("no signing config named `doesNotExist`"), result.output)
  }

  @Test
  fun `two targets sharing a package suffix is a directed error`() {
    val projectDir =
      newFixtureProject(
        androidFixtureBuildScript(
          """
          trailblazeAndroid {
            inProcessIdle { targetApplicationId = "com.first.app" }
            inProcessIdle { targetApplicationId = "com.second.app" }
          }
          """
        ),
        tempDirs,
      )

    val result = gradleRunner(projectDir, "tasks").buildAndFail()

    assertTrue(result.output.contains("share the package suffix `app`"), result.output)
  }

  @Test
  fun `builds a signed idle detector apk end to end with the machine's sdk`() {
    // Same resolution the plugin will apply inside the fixture (which has no local.properties):
    // env vars or the default install locations. No SDK → skip, not fail — external contributors
    // without an Android SDK still get a green `check`.
    assumeTrue(
      "no Android SDK found via ANDROID_HOME / default locations — skipping the APK build test",
      resolveAndroidSdkDir(File(System.getProperty("java.io.tmpdir"))) != null,
    )

    val projectDir =
      newFixtureProject(
        androidFixtureBuildScript(
          """
          trailblazeAndroid {
            inProcessIdle {
              targetApplicationId = "com.example.fixtureapp"
              keystoreFile = file("fixture.keystore")
              keystorePassword = "fixturepw"
              keyAlias = "fixturekey"
            }
          }
          """
        ),
        tempDirs,
      )
    generateTestKeystore(File(projectDir, "fixture.keystore"))

    val result = gradleRunner(projectDir, "buildTrailblazeInProcessIdleFixtureappApk").build()

    assertEquals(
      TaskOutcome.SUCCESS,
      result.task(":buildTrailblazeInProcessIdleFixtureappApk")?.outcome,
    )
    val apk =
      File(
        projectDir,
        "build/intermediates/trailblaze/inprocess-idle-apk-assets/" +
          "inprocess-idle-apks/trailblaze-inprocess-idle-fixtureapp.apk",
      )
    assertTrue(apk.isFile, "staged idle detector APK missing at $apk")
    ZipFile(apk).use { zip ->
      assertTrue(zip.getEntry("classes.dex") != null, "APK has no classes.dex")
      assertTrue(zip.getEntry("AndroidManifest.xml") != null, "APK has no manifest")
    }
  }

  private fun generateTestKeystore(keystore: File) {
    val keytool = File(System.getProperty("java.home"), "bin/keytool")
    val process =
      ProcessBuilder(
          keytool.absolutePath,
          "-genkeypair",
          "-keystore",
          keystore.absolutePath,
          "-storepass",
          "fixturepw",
          "-keypass",
          "fixturepw",
          "-alias",
          "fixturekey",
          "-dname",
          "CN=Fixture,O=Test,C=US",
          "-keyalg",
          "RSA",
          "-keysize",
          "2048",
          "-validity",
          "30",
        )
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
    assertEquals(0, process.waitFor(), "keytool failed:\n$output")
  }
}
