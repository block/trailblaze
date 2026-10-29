import GenerateAndroidTrailJUnitShellsTask.Companion.groupByTrailhead
import GenerateAndroidTrailJUnitShellsTask.Companion.groupableTrailYaml
import GenerateAndroidTrailJUnitShellsTask.Companion.renderShell
import GenerateAndroidTrailJUnitShellsTask.Companion.trailheadGroupKey
import GenerateAndroidTrailJUnitShellsTask.TestHostMode
import GenerateAndroidTrailJUnitShellsTask.TrailGroup
import GenerateAndroidTrailJUnitShellsTask.TrailMethod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Which trails `groupTrailsByTrailhead` runs in one `@Test`, and the test it renders for them. */
class TrailheadGroupingTest {

  private fun trail(trailheadEmail: String?, step: String = "Sign in", config: String = "") = buildString {
    if (config.isNotEmpty()) append("config:\n$config\n")
    if (trailheadEmail != null) {
      append(
        """
        |trailhead:
        |  step: "$step"
        |  recording:
        |    android:
        |      myapp_launchSignedIn:
        |        email: $trailheadEmail
        |        useFakeMode: true
        |
        """
          .trimMargin()
      )
    }
    append("trail:\n  - step: Open the settings screen\n")
  }

  private fun method(name: String) = TrailMethod(methodName = name, isRecordingDir = false, source = "C/$name.trail.yaml")

  @Test
  fun `trails with the same recorded trailhead share a key whatever their step text says`() {
    assertEquals(
      trailheadGroupKey(trail("bart@example.com", step = "Log in as Bart")),
      trailheadGroupKey(trail("bart@example.com", step = "Sign in to the POS")),
    )
  }

  @Test
  fun `trails that differ only in what names or describes them share a key`() {
    val first =
      "  id: suite/first\n  title: First\n  description: |\n    Skip nothing; any locale.\n" +
        "  metadata:\n    caseId: 1\n  tags: [smoke]\n  target: myapp"
    val second =
      "  id: suite/second\n  title: Second\n  priority: P0\n  metadata:\n    caseId: 2\n  tags:\n  - search\n  target: myapp"

    assertNotNull(trailheadGroupKey(trail("bart@example.com", config = first)))
    assertEquals(
      trailheadGroupKey(trail("bart@example.com", config = first)),
      trailheadGroupKey(trail("bart@example.com", config = second)),
    )
  }

  @Test
  fun `a config key that changes the run still keeps trails apart`() {
    assertNotEquals(
      trailheadGroupKey(trail("bart@example.com", config = "  id: a\n  memory:\n    amount: 1")),
      trailheadGroupKey(trail("bart@example.com", config = "  id: b\n  memory:\n    amount: 2")),
    )
  }

  @Test
  fun `a different trailhead argument is a different key`() {
    assertNotEquals(trailheadGroupKey(trail("bart@example.com")), trailheadGroupKey(trail("krusty@example.com")))
  }

  @Test
  fun `a trail with no trailhead, or with a locale, runs alone`() {
    assertNull(trailheadGroupKey(trail(trailheadEmail = null)))
    assertNull(trailheadGroupKey(trail("bart@example.com", config = "  locale: es-US")))
  }

  @Test
  fun `a trail its config can skip runs alone`() {
    assertNull(trailheadGroupKey(trail("bart@example.com", config = "  skip:\n    android: Not on phones yet")))
  }

  @Test
  fun `trails that pin different drivers do not group`() {
    assertNotEquals(
      trailheadGroupKey(trail("bart@example.com", config = "  driver: ANDROID_ONDEVICE_ACCESSIBILITY")),
      trailheadGroupKey(trail("bart@example.com", config = "  driver: ANDROID_ONDEVICE_INSTRUMENTATION")),
    )
  }

  @Test
  fun `trails that bind the same drivers to different devices do not group`() {
    val phoneFirst = "  devices:\n    android-phone:\n      driver: A\n    android-tablet:\n      driver: B"
    val tabletFirst = "  devices:\n    android-tablet:\n      driver: A\n    android-phone:\n      driver: B"

    assertNotEquals(
      trailheadGroupKey(trail("bart@example.com", config = phoneFirst)),
      trailheadGroupKey(trail("bart@example.com", config = tabletFirst)),
    )
  }

  @Test
  fun `a locale or driver written as an inline mapping still keeps a trail apart`() {
    assertNull(trailheadGroupKey(trail("bart@example.com", config = "  android: { locale: fr }")))
    assertNotEquals(
      trailheadGroupKey(trail("bart@example.com")),
      trailheadGroupKey(trail("bart@example.com", config = "  android: { driver: ANDROID_TEST }")),
    )
    assertNull(trailheadGroupKey("config: { driver: ANDROID_TEST }\n" + trail("bart@example.com")))
  }

  @Test
  fun `a recording directory with a classifier-specific recording runs alone`() {
    val classDir = kotlin.io.path.createTempDirectory("grouping").toFile()
    val plain = classDir.resolve("plain").apply { mkdirs() }
    plain.resolve("trail.yaml").writeText(trail("bart@example.com"))
    val withPhone = classDir.resolve("withPhone").apply { mkdirs() }
    withPhone.resolve("trail.yaml").writeText(trail("bart@example.com"))
    withPhone.resolve("android-phone.trail.yaml").writeText(trail("bart@example.com", config = "  locale: fr"))
    fun dirMethod(name: String) = TrailMethod(methodName = name, isRecordingDir = true, source = "C/$name")

    assertNotNull(groupableTrailYaml(classDir, dirMethod("plain")))
    assertNull(groupableTrailYaml(classDir, dirMethod("withPhone")))
    classDir.deleteRecursively()
  }

  @Test
  fun `groups split at the cap, and a trailhead only one trail uses makes no group`() {
    val trails =
      linkedMapOf(
        "a" to trail("bart@example.com"),
        "b" to trail("bart@example.com"),
        "c" to trail("bart@example.com"),
        "d" to trail("krusty@example.com"),
        "e" to trail(trailheadEmail = null),
        "f" to trail(trailheadEmail = null),
      )

    val groups = groupByTrailhead(trails.keys.map(::method), maxPerGroup = 2) { trails.getValue(it.methodName) }

    assertEquals(listOf(listOf("a", "b")), groups.map { group -> group.members.map { it.methodName } })
    assertEquals("aAnd1More", groups.single().methodName)
  }

  @Test
  fun `a group's name never takes a trail's own name`() {
    val trails = linkedMapOf("a" to trail("bart@example.com"), "aAnd1More" to trail("bart@example.com"))

    val groups = groupByTrailhead(trails.keys.map(::method), maxPerGroup = 2) { trails.getValue(it.methodName) }

    assertEquals("aAnd1More2", groups.single().methodName)
  }

  @Test
  fun `a grouped test runs its trails in order, with their source links, and they get no test of their own`() {
    val members = listOf(method("a"), method("b").copy(trailSourceUrl = "https://example.com/b"))
    val rendered =
      renderShell(
        packageName = "com.example",
        className = "C",
        mode = TestHostMode.BaseClass("com.example.BaseTest"),
        methods = listOf(method("solo")),
        groups = listOf(TrailGroup("aAnd1More", members)),
      )

    assertTrue(rendered.contains("@Test fun solo() = runFromAsset()"), rendered)
    assertTrue(
      rendered.contains(
        """
        |  @Test
        |  fun aAnd1More() =
        |    runFromAssets(
        |      listOf(
        |        "trails/C/a.trail.yaml",
        |        "trails/C/b.trail.yaml",
        |      ),
        |      trailSourceUrls =
        |        mapOf(
        |          "trails/C/b.trail.yaml" to "https://example.com/b",
        |        ),
        |    )
        """
          .trimMargin()
      ),
      rendered,
    )
    assertFalse(rendered.contains("fun a()"), rendered)
  }

  @Test
  fun `an inline-rule shell's grouped test calls the rule`() {
    val rendered =
      renderShell(
        packageName = "com.example",
        className = "C",
        mode = TestHostMode.InlineRule("com.example.Rule"),
        methods = emptyList(),
        groups = listOf(TrailGroup("aAnd1More", listOf(method("a"), method("b")))),
      )

    assertTrue(rendered.contains("    rule.runFromAssets(\n      listOf(\n        \"trails/C/a.trail.yaml\","), rendered)
  }
}
