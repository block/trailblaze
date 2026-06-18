import org.gradle.api.artifacts.VersionCatalogsExtension

// Centralized dependency-resolution forces and version pins, applied across every subproject
// from the root build via `apply(from = ".../gradle/dependency-resolution.gradle.kts")`.
// This is the one place to pin a transitive version that must hold for every configuration —
// whether the reason is a security advisory or a runtime-compatibility mismatch. Keeping the
// forces in one applied script means every build that applies it resolves identical versions,
// so dependency-guard baselines stay consistent. Add new force blocks here rather than inline
// in a root build script.

val libsCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
val skikoVersion = libsCatalog.findVersion("skiko").get().requiredVersion
val serializationVersion = libsCatalog.findVersion("kotlinx-serialization").get().requiredVersion
val micrometerVersion = libsCatalog.findVersion("micrometer").get().requiredVersion
val ktorVersion = libsCatalog.findVersion("ktor").get().requiredVersion
val kotlinVersion = libsCatalog.findVersion("kotlin").get().requiredVersion

subprojects {
  // Pin every Kotlin runtime artifact — kotlin-stdlib* (including the empty, post-1.8
  // stdlib-jdk7/jdk8 pass-through shims), kotlin-reflect, and the kotlin-bom — to the catalog
  // Kotlin version across every configuration. Transitive dependencies otherwise pull these in
  // at mismatched versions: the MCP Kotlin SDK and others drag in a newer kotlin-stdlib than the
  // catalog, and koog pulls a transitive kotlin-bom whose constraint pins the shims to an older
  // version. A mismatch triggers "runtime JAR is newer than the compiler" warnings and
  // stdlib/reflect skew, and — because dependency-guard baselines are generated against this
  // build's forced resolution and synced to open source — drifts the baselines apart. Previously
  // the stdlib/reflect force lived inline in a downstream module's build script.
  configurations.all {
    resolutionStrategy.eachDependency {
      if (requested.group == "org.jetbrains.kotlin" &&
        (requested.name.startsWith("kotlin-stdlib") ||
          requested.name == "kotlin-reflect" ||
          requested.name == "kotlin-bom")
      ) {
        useVersion(kotlinVersion)
        because("pin Kotlin stdlib/reflect/bom to the catalog Kotlin version ($kotlinVersion)")
      }
    }
  }

  // Pin Skiko to the catalog (Compose Multiplatform) version. Coil transitively pulls in an
  // older skiko built against a different Compose; force it back so the two agree.
  plugins.withId("org.jetbrains.compose") {
    configurations.all {
      resolutionStrategy {
        force("org.jetbrains.skiko:skiko:$skikoVersion")
      }
    }
  }

  // Pin kotlinx-serialization to the declared catalog version across all configurations.
  // Transitive BOM constraints (e.g. from compose-bom) can downgrade serialization-core to
  // an older version while serialization-json stays newer, causing AbstractMethodError at
  // runtime due to binary incompatibility.
  configurations.all {
    resolutionStrategy.eachDependency {
      if (requested.group == "org.jetbrains.kotlinx" &&
        requested.name.startsWith("kotlinx-serialization")
      ) {
        useVersion(serializationVersion)
        because("prevent transitive BOM constraints from downgrading serialization below $serializationVersion")
      }
    }
  }

  // Force micrometer to a version that fixes CVE-2026-40984 (CWE-770: Allocation of Resources
  // Without Limits or Throttling). micrometer is a transitive dependency of
  // dev.mobile:maestro-utils, which pins the vulnerable 1.13.4; this project does not use it
  // directly.
  configurations.all {
    resolutionStrategy.eachDependency {
      if (requested.group == "io.micrometer") {
        useVersion(micrometerVersion)
        because("CVE-2026-40984: micrometer-core < 1.15.12 is vulnerable to resource exhaustion")
      }
    }
  }

  // Align every io.ktor:* artifact to the declared Ktor version. Koog (ai.koog:prompt-executor-*)
  // transitively pulls a subset of Ktor modules (ktor-client-cio, ktor-server-cio/-sse/-websockets,
  // ktor-network-tls, ktor-serialization-kotlinx-json, ktor-client-apache5) at its own Ktor version.
  // Without this, bumping the catalog `ktor` ref upgrades only the directly-aliased modules and
  // leaves those transitive ones behind, putting two Ktor minor versions on one classpath. Ktor
  // modules ship in lockstep and share internal APIs, so a mismatch risks NoSuchMethodError/LinkageError.
  configurations.all {
    resolutionStrategy.eachDependency {
      if (requested.group == "io.ktor") {
        useVersion(ktorVersion)
        because("Ktor modules must be version-aligned; prevent transitive deps (e.g. Koog) from pinning a different Ktor version")
      }
    }
  }
}
