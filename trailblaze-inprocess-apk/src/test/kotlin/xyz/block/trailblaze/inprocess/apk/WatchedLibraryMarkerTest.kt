package xyz.block.trailblaze.inprocess.apk

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The structural invariants of [DEFAULT_WATCHED_LIBRARIES], the ones checkable with no network.
 *
 * Whether `GuidanceKt` really first shipped in coroutines 1.10.2 can only be settled by listing the
 * published jars, and [WatchedLibrary] documents that procedure. What is checkable here is the class
 * of mistake that procedure cannot catch, because it is made when a qualified marker is *typed into*
 * the list: a marker pasted under the wrong library, or a version string the comparator silently
 * reads as zero. Both fail the same way in production — the marker is never found, the library reads
 * as older than it is, and every app gets a floor disqualifier it does not deserve.
 */
class WatchedLibraryMarkerTest {

  @Test
  fun `every marker belongs to the library it bounds`() {
    for (library in DEFAULT_WATCHED_LIBRARIES) {
      val owner = ownedPackage(library)
      for (marker in library.boundaries) {
        val fqn = markerClass(marker)
        assertTrue(
          fqn.startsWith("$owner."),
          "${library.library} bounds itself with $fqn, which is not under $owner — a marker the " +
            "library cannot define is never found, so the library reads as older than it is",
        )
      }
    }
  }

  @Test
  fun `every introduced-in version is numeric`() {
    // `LibraryVersions.compare` reads a non-numeric segment as 0 rather than refusing it, so a typo
    // like "1.10.2-beta" or "v1.11.0" does not throw — it quietly sorts below every floor.
    val numeric = Regex("""\d+(\.\d+)*""")
    for (library in DEFAULT_WATCHED_LIBRARIES) {
      for (marker in library.boundaries) {
        assertTrue(
          numeric.matches(marker.introducedIn),
          "${library.library}'s ${marker.describe} claims version '${marker.introducedIn}', which " +
            "the version comparator reads as zeros",
        )
      }
    }
  }

  @Test
  fun `no two markers for one library claim the same version`() {
    // Two markers at one version cannot narrow anything the other does not, and a duplicate is the
    // shape of a copy-paste that meant to state a different release.
    for (library in DEFAULT_WATCHED_LIBRARIES) {
      val versions = library.boundaries.map { it.introducedIn }
      assertEquals(
        versions.distinct(),
        versions,
        "${library.library} states more than one marker at the same version",
      )
    }
  }

  @Test
  fun `every androidx version file named follows the packaging convention`() {
    // AGP writes `META-INF/<group>_<artifact>.version` for AndroidX artifacts. A typo here is
    // invisible in production: the entry is simply absent from the APK, and the exact era silently
    // degrades to a marker bound or to undeterminable. Naming none is a real choice — a library
    // watched only for presence needs no version — so this checks the entries that exist.
    for (library in DEFAULT_WATCHED_LIBRARIES) {
      val (group, artifact) = library.library.split(":")
      if (!group.startsWith("androidx.") || library.versionFiles.isEmpty()) continue
      assertEquals(
        listOf("META-INF/${group}_$artifact.version"),
        library.versionFiles,
        "${library.library} does not name the entry AGP packages for it",
      )
    }
  }

  @Test
  fun `every shell floor version file belongs to the library's own group`() {
    // A shell floor may come from a sibling artifact — Compose's floor comes from `ui-test-junit4`,
    // not from `ui` — but never from another group, which would let one library's shell version
    // impose a floor on an unrelated library's app copy.
    for (library in DEFAULT_WATCHED_LIBRARIES) {
      val group = library.library.substringBefore(":")
      for (entry in library.shellFloorVersionFiles) {
        assertTrue(
          entry.startsWith("META-INF/${group}_"),
          "${library.library} takes a shell floor from $entry, which is not one of its artifacts",
        )
      }
    }
  }

  @Test
  fun `every declared floor sits at a version some marker can reach`() {
    // The trap this pins, and the reason the Compose Runtime floor says 1.11.0 while the shell
    // packages 1.11.4: a floor is only useful at a precision the dex can answer. Compose publishes
    // identical class sets across a minor line, so no marker can ever bound an app past 1.11.0, and
    // a 1.11.4 floor would report the ENFORCED ERA_UNDETERMINABLE for every app that packages no
    // `.version` file — including ones on a perfectly fine 1.11.x. Requiring a marker AT the floor
    // means an unversioned APK can always be cleared or refused on evidence.
    for (entry in DEFAULT_DECLARED_SHELL_FLOOR.libraries) {
      val watched = DEFAULT_WATCHED_LIBRARIES.firstOrNull { it.library == entry.library }
      assertTrue(
        watched != null,
        "${entry.library} has a declared floor but is not watched, so no era is ever computed for it",
      )
      assertTrue(
        watched.boundaries.any { LibraryVersions.compare(it.introducedIn, entry.minVersion) == 0 },
        "${entry.library}'s floor of ${entry.minVersion} has no marker at that version, so an APK " +
          "packaging no version file can only ever report ERA_UNDETERMINABLE against it",
      )
    }
  }

  @Test
  fun `no library is watched twice`() {
    val coordinates = DEFAULT_WATCHED_LIBRARIES.map { it.library }
    assertEquals(coordinates.distinct(), coordinates)
  }

  private fun markerClass(marker: DexMarker): String = when (marker) {
    is DexMarker.ClassDefined -> marker.fqn
    is DexMarker.MethodDeclared -> marker.classFqn
  }

  /**
   * The package every class of [library] must sit under.
   *
   * The Maven group alone is not it: `org.jetbrains.kotlinx:kotlinx-coroutines-core` ships
   * `kotlinx.coroutines`, so a group-prefix rule would reject every correct coroutines marker. The
   * presence class's own package alone is not it either — Compose UI's presence class lives in
   * `androidx.compose.ui.platform` while a legitimate marker lives in `androidx.compose.ui.autofill`.
   * What holds for both shapes is the part the group and the presence class agree on, falling back to
   * the presence class's package when the two share no prefix at all.
   */
  private fun ownedPackage(library: WatchedLibrary): String {
    val group = library.library.substringBefore(":").split(".")
    val presence = packageOf(library.presenceClass).split(".")
    val shared = group.zip(presence).takeWhile { (a, b) -> a == b }.map { it.first }
    return if (shared.isEmpty()) presence.joinToString(".") else shared.joinToString(".")
  }

  /** The package of a fully-qualified class name: the leading segments that are not type names. */
  private fun packageOf(fqn: String): String =
    fqn.split(".").takeWhile { it.firstOrNull()?.isLowerCase() == true }.joinToString(".")
}
