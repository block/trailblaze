import java.io.File
import kotlin.io.path.createTempDirectory
import org.gradle.testkit.runner.GradleRunner

/**
 * Shared Gradle TestKit fixture helpers for this module's functional tests.
 *
 * The plugin under test requires `com.android.library` / `com.android.application` (it fails fast
 * otherwise) and reaches AGP's `sourceSets` / `signingConfigs` by reflection, not a typed
 * reference — so a fixture only needs an `android` extension shaped like AGP's (`getSourceSets()`
 * → a container whose elements expose `getJava()` / `getAssets()`, each with `srcDir(Any)`;
 * `getSigningConfigs()` → a container whose elements expose the four signing getters).
 * [ANDROID_FIXTURE_PRELUDE] registers exactly that stand-in, so fixtures run fast and offline
 * instead of resolving real AGP.
 */
private val ANDROID_FIXTURE_PRELUDE =
  """
  class FakeAndroidSourceDirectorySet {
    val srcDirs = mutableListOf<Any>()
    fun srcDir(dir: Any) { srcDirs.add(dir) }
  }
  open class FakeAndroidSourceSet @javax.inject.Inject constructor(private val n: String) : org.gradle.api.Named {
    override fun getName() = n
    val java = FakeAndroidSourceDirectorySet()
    val assets = FakeAndroidSourceDirectorySet()
  }
  open class FakeSigningConfig @javax.inject.Inject constructor(private val n: String) : org.gradle.api.Named {
    override fun getName() = n
    var storeFile: java.io.File? = null
    var storePassword: String? = null
    var keyAlias: String? = null
    var keyPassword: String? = null
  }
  class FakeAndroidExtension(
    val sourceSets: org.gradle.api.NamedDomainObjectContainer<FakeAndroidSourceSet>,
    val signingConfigs: org.gradle.api.NamedDomainObjectContainer<FakeSigningConfig>,
  )

  val fakeAndroidSourceSets = container(FakeAndroidSourceSet::class.java) { name ->
    objects.newInstance(FakeAndroidSourceSet::class.java, name)
  }
  fakeAndroidSourceSets.create("androidTest")
  val fakeSigningConfigs = container(FakeSigningConfig::class.java) { name ->
    objects.newInstance(FakeSigningConfig::class.java, name)
  }
  extensions.add("android", FakeAndroidExtension(fakeAndroidSourceSets, fakeSigningConfigs))
  """
    .trimIndent()

/**
 * `xyz.block.trailblaze.android-gradle` + the [ANDROID_FIXTURE_PRELUDE] stand-in, with
 * [extraBuildScript] appended.
 */
internal fun androidFixtureBuildScript(extraBuildScript: String): String =
  """
  plugins {
    id("xyz.block.trailblaze.android-gradle")
  }

  $ANDROID_FIXTURE_PRELUDE

  ${extraBuildScript.trimIndent()}
  """
    .trimIndent()

internal fun newFixtureProject(buildScript: String, tempDirs: MutableList<File>): File {
  val dir = createTempDirectory("trailblaze-android-gradle-functional").toFile().also(tempDirs::add)
  File(dir, "settings.gradle.kts").writeText("""rootProject.name = "fixture"""")
  File(dir, "build.gradle.kts").writeText(buildScript)
  return dir
}

internal fun gradleRunner(projectDir: File, vararg args: String): GradleRunner =
  GradleRunner.create()
    .withProjectDir(projectDir)
    // `--no-watch-fs` on every fixture build. Several tests here write a file BETWEEN two
    // invocations and assert the second one re-runs; with file-system watching on, that second
    // build can answer from the reused TestKit daemon's cached VFS state, miss the new file and
    // report UP-TO-DATE, turning a `buildAndFail()` into `UnexpectedBuildSuccess`. Observed once
    // as a 1-in-5 failure of the misplaced-bare-trail test on a loaded machine. Disabling the
    // watcher makes each build re-stat the tree, which is what these assertions assume.
    //
    // This does NOT weaken the one test that asserts UP-TO-DATE: nothing changes between its two
    // builds, so a fresh stat reaches the same answer. The flag only removes a cache that can be
    // stale, never a real up-to-date signal.
    .withArguments(*args, "--no-watch-fs")
    .withPluginClasspath()
    .forwardOutput()

internal fun fixtureTree(dir: File): String =
  dir.walkTopDown().joinToString("\n") { it.relativeTo(dir).path }
