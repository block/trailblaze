/**
 * Hard gate: refuse to publish a bare CalVer coordinate (`2026.08.11`) to Maven Central.
 *
 * Publishing one is a one-way door. `2026.08.11` sorts ABOVE any future `1.0.0` in both Gradle's
 * conflict resolution and Maven's `ComparableVersion` — 2026 beats 1 on the first numeric part —
 * so once a CalVer release exists, a later semver line is silently out-ranked by every older
 * build: a transitive dependency on the CalVer artifact wins over a direct `1.0.0`. Maven
 * coordinates have no epoch, so the only exits are renaming the artifacts or staying on CalVer
 * forever.
 *
 * This is easy to do by accident here, which is why it is a build failure rather than a
 * convention. Releases are tagged `vYYYY.MM.DD`, so the tag name is the obvious thing to feed to
 * `-Pversion`, and `gradle/git-version.gradle.kts` already sets `version = 2026.08.11` on ANY
 * build whose HEAD carries a release tag. Publishing from a tagged checkout without an explicit
 * `-Pversion` would ship CalVer with nothing else to stop it.
 *
 * The rule: the leading component of a published version must not be a calendar year. A published
 * version is `<major>.<minor>.<patch>[-<qualifier>]`, and the build date — when present — rides in
 * the qualifier (`0.1.0-2026.08.11`), never in front. `.github/workflows/release.yml` derives
 * exactly that shape.
 *
 * Deliberately scoped to the Maven Central publish path, not applied at configuration time: the
 * git-version override sets a CalVer version on every ordinary build made from a tagged commit,
 * and those must keep working. `publishToMavenLocal` is likewise left alone.
 */

// No realistic major version reaches four digits; every calendar year does.
val calVerLeadingComponent = 1000

fun assertNotCalendarVersion(target: String, version: String) {
  // Gradle and Maven both split version parts on `.`, `-`, AND `_`, so a dash/underscore CalVer
  // (`2026-08-11`) outranks `1.0.0` exactly like the dotted form and must be caught too. A plain
  // `substringBefore('.')` would let it through: "2026-08-11".toIntOrNull() is null.
  val leading = version.split('.', '-', '_').first().toIntOrNull() ?: return
  if (leading < calVerLeadingComponent) return
  throw GradleException(
    """
    Refusing to publish $target at version '$version'.

    Its leading component ($leading) is a calendar year, making this a bare CalVer coordinate.
    A CalVer version sorts ABOVE any future semver release — '$version' beats '1.0.0' in both
    Gradle and Maven — which permanently forecloses a stable semver line for these artifacts.
    There is no epoch escape hatch in Maven coordinates.

    Publish '<major>.<minor>.<patch>' with the build date in the qualifier instead, e.g.
    '0.1.0-$version'. That is what .github/workflows/release.yml derives from the release tag;
    a publish run that bypasses it (or a local `-Pversion=`) has to supply the same shape.
    """.trimIndent(),
  )
}

allprojects {
  // `prepareMavenCentralPublishing` is the earliest task on the Central path — it runs before the
  // signing and publish tasks and is what registers the project with the upload build service, so
  // failing here means nothing is staged, signed, or uploaded. The PublishToMavenRepository gate
  // below is the backstop for any path that reaches a remote repository without it.
  tasks.matching { it.name == "prepareMavenCentralPublishing" }.configureEach {
    val path = project.displayName
    val versionAtExecution = project.provider { project.version.toString() }
    doFirst { assertNotCalendarVersion(path, versionAtExecution.get()) }
  }

  tasks.withType(org.gradle.api.publish.maven.tasks.PublishToMavenRepository::class.java)
    .configureEach {
      val path = project.displayName
      val versionAtExecution = project.provider { project.version.toString() }
      doFirst { assertNotCalendarVersion(path, versionAtExecution.get()) }
    }
}
