import org.gradle.api.artifacts.VersionCatalogsExtension

// Single source of truth for dependency-resolution security overrides.
//
// Applied from BOTH root builds so the internal and open-source project
// configurations resolve identical versions:
//   - internal root:    apply(from = "opensource/gradle/dependency-security.gradle.kts")
//   - open-source root: apply(from = "gradle/dependency-security.gradle.kts")
//
// Keeping the force here (in the mirrored opensource/ tree) rather than inline in a
// single root means the open-source mirror gets the same resolution the internal
// build does. Otherwise the dependency-guard baselines — generated against the
// internal resolution and then synced — fail in open-source CI because the mirror
// resolves the un-forced transitive version.

val micrometerVersion =
  extensions.getByType<VersionCatalogsExtension>()
    .named("libs")
    .findVersion("micrometer")
    .get()
    .requiredVersion

// micrometer is a transitive dependency of dev.mobile:maestro-utils, which pins the
// vulnerable 1.13.4; Trailblaze does not depend on it directly. Force the
// catalog-pinned version (CVE-2026-40984, CWE-770: Allocation of Resources Without
// Limits or Throttling) across every configuration in every subproject.
subprojects {
  configurations.all {
    resolutionStrategy.eachDependency {
      if (requested.group == "io.micrometer") {
        useVersion(micrometerVersion)
        because("CVE-2026-40984: micrometer-core < 1.15.12 is vulnerable to resource exhaustion")
      }
    }
  }
}
