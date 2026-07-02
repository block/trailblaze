import java.io.File
import kotlin.io.path.createTempDirectory
import org.gradle.testkit.runner.GradleRunner

/**
 * Shared Gradle TestKit fixture helpers for this module's functional tests.
 *
 * The plugin under test requires `com.android.library` / `com.android.application` (it fails fast
 * otherwise) and reaches AGP's `sourceSets` by reflection, not a typed reference — so a fixture
 * only needs an `android` extension shaped like AGP's (`getSourceSets()` → a container whose
 * elements expose `getJava()` / `getAssets()`, each with `srcDir(Any)`). [ANDROID_FIXTURE_PRELUDE]
 * registers exactly that stand-in, so fixtures run fast and offline instead of resolving real AGP.
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
  class FakeAndroidExtension(val sourceSets: org.gradle.api.NamedDomainObjectContainer<FakeAndroidSourceSet>)

  val fakeAndroidSourceSets = container(FakeAndroidSourceSet::class.java) { name ->
    objects.newInstance(FakeAndroidSourceSet::class.java, name)
  }
  fakeAndroidSourceSets.create("androidTest")
  extensions.add("android", FakeAndroidExtension(fakeAndroidSourceSets))
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
    .withArguments(*args)
    .withPluginClasspath()
    .forwardOutput()

internal fun fixtureTree(dir: File): String =
  dir.walkTopDown().joinToString("\n") { it.relativeTo(dir).path }
