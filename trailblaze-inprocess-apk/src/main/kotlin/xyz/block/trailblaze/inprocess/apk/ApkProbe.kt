package xyz.block.trailblaze.inprocess.apk

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlException
import kotlinx.serialization.Serializable
import java.io.File

/** A team's own statement of what their app ships, for an APK whose bytes cannot say. */
@Serializable
data class DeclaredDependencies(
  val libraries: List<DeclaredLibraryVersion> = emptyList(),
) {
  companion object {
    private val yaml = Yaml(configuration = YamlConfiguration(strictMode = true))

    fun parse(text: String, describeSource: String): DeclaredDependencies = try {
      yaml.decodeFromString(serializer(), text)
    } catch (e: YamlException) {
      throw ApkReadException(
        "$describeSource is not a readable declared-dependency list: ${e.message}. Expected " +
          "`libraries:` with `library:` / `version:` entries, e.g.\n" +
          "  libraries:\n" +
          "    - library: \"org.jetbrains.kotlinx:kotlinx-coroutines-core\"\n" +
          "      version: \"1.10.2\"",
        e,
      )
    }
  }
}

@Serializable
data class DeclaredLibraryVersion(val library: String, val version: String)

/**
 * Turns an app APK into its [AppFingerprint].
 *
 * Pure JVM on purpose — no `aapt2`, no `apksigner`, no `ANDROID_HOME`. A team adopting the
 * in-process driver runs this where their APK already is (usually their own CI), and hands back a
 * few KB of YAML instead of a few hundred MB of binary.
 */
class ApkProbe(
  private val watched: List<WatchedLibrary> = DEFAULT_WATCHED_LIBRARIES,
  private val declaredFloor: ShellFloor = DEFAULT_DECLARED_SHELL_FLOOR,
  /**
   * Reads an APK's signing certificate. The default is the real one; a caller can substitute a
   * reader so an APK built to exercise era detection does not also have to be signed.
   */
  private val certificateDigest: (File) -> String = ApkSigningCertificate::sha256OfSoleSigner,
) {

  /**
   * Probes [appApk].
   *
   * @param shellApk the processed shell this app would actually be paired with. Without it the dex
   *   intersection cannot run and the verdict is capped at [ProbeStatus.INCOMPLETE] — the shell also
   *   contributes half the era floor, so a shell-less probe is a strictly weaker answer.
   * @param declared a team's own dependency versions, for an APK that packages no `.version` files.
   */
  fun probe(
    appApk: File,
    shellApk: File? = null,
    declared: DeclaredDependencies = DeclaredDependencies(),
  ): AppFingerprint = ApkArchive.open(appApk).use { app ->
    val manifest = ManifestFacts.read(app)
    val certSha256 = certificateDigest(appApk)

    val shellFacts = shellApk?.let { file ->
      ApkArchive.open(file).use { shell ->
        ShellFacts(
          name = file.name,
          classes = DexScanner.definedClasses(shell),
          floor = readShellFloor(shell),
        )
      }
    }
    val shellClasses = shellFacts?.classes.orEmpty()
    val shellFloor = shellFacts?.floor ?: ShellFloor()
    val shellName = shellFacts?.name

    val scan = DexScanner.scan(app, buildScanRequest(shellClasses))
    val eras = watched.map { library -> detectEra(app, library, scan, declared) }
    val floor = declaredFloor.mergedWith(shellFloor)

    val overlap = shellName?.let { name ->
      val classes = scan.overlapWithShell.toList()
      DexOverlap(
        shell = name,
        classCount = classes.size,
        classes = classes.take(DEX_OVERLAP_SAMPLE_LIMIT),
        truncated = classes.size > DEX_OVERLAP_SAMPLE_LIMIT,
      )
    }

    AppFingerprint(
      targetPackage = manifest.packageName,
      launcherActivity = manifest.launcherActivity,
      debuggable = manifest.debuggable,
      certSha256 = certSha256,
      providers = manifest.providers,
      eras = eras,
      dexOverlap = overlap,
      verdict = ProbeVerdictRules.evaluate(manifest, eras, floor, overlap, shellGiven = shellApk != null),
    )
  }

  private class ShellFacts(val name: String, val classes: Set<String>, val floor: ShellFloor)

  private fun buildScanRequest(shellClasses: Set<String>): DexScanRequest {
    val classes = mutableSetOf<String>()
    val methods = mutableListOf<DexMarker.MethodDeclared>()
    watched.forEach { library ->
      classes += library.presenceClass
      library.boundaries.forEach { marker ->
        when (marker) {
          is DexMarker.ClassDefined -> classes += marker.fqn
          is DexMarker.MethodDeclared -> {
            methods += marker
            // A method marker is only readable when its declaring class is defined here, and the
            // scan learns the class's type index from the same pass.
            classes += marker.classFqn
          }
        }
      }
    }
    return DexScanRequest(classes, methods, shellClasses)
  }

  private fun detectEra(
    app: ApkArchive,
    library: WatchedLibrary,
    scan: DexScanResult,
    declared: DeclaredDependencies,
  ): LibraryEra {
    val present = library.presenceClass in scan.definedClassesOfInterest

    // Ground truth first: a `.version` file AGP packaged says the version outright.
    library.versionFiles.forEach { entry ->
      val text = app.text(entry)
      if (!text.isNullOrBlank() && text.firstOrNull()?.isDigit() == true) {
        return LibraryEra(
          library = library.library,
          // The entry is itself presence evidence, and stronger than the presence class: R8 can
          // rename or strip that class, and `present = false` makes the floor check skip this
          // library entirely — clearing an app whose exact version is right there in the APK.
          present = true,
          version = text,
          source = EraSource.PACKAGED_VERSION_FILE,
          evidence = entry,
        )
      }
    }

    val declaredVersion = declared.libraries.firstOrNull { it.library == library.library }?.version
    val bounds = boundsFrom(library, scan, present)

    if (declaredVersion != null) {
      val contradiction = contradiction(declaredVersion, bounds)
      return LibraryEra(
        library = library.library,
        present = present,
        version = declaredVersion,
        source = EraSource.DECLARED,
        evidence = "declared by the caller" + (contradiction?.let { " — but $it" } ?: ""),
      )
    }

    if (!present) {
      return LibraryEra(
        library = library.library,
        present = false,
        source = EraSource.DEX_MARKER,
        evidence = "no dex defines ${library.presenceClass}",
      )
    }

    return LibraryEra(
      library = library.library,
      present = true,
      atLeastVersion = bounds.atLeast,
      belowVersion = bounds.below,
      source = EraSource.DEX_MARKER,
      evidence = bounds.evidence.ifBlank {
        "${library.presenceClass} is defined, but no version marker is known for this library"
      },
    )
  }

  private class Bounds(val atLeast: String?, val below: String?, val evidence: String)

  private fun boundsFrom(library: WatchedLibrary, scan: DexScanResult, present: Boolean): Bounds {
    if (!present || library.boundaries.isEmpty()) return Bounds(null, null, "")
    var atLeast: String? = null
    var below: String? = null
    val notes = mutableListOf<String>()
    library.boundaries.forEach { marker ->
      val observed = when (marker) {
        is DexMarker.ClassDefined -> marker.fqn in scan.definedClassesOfInterest
        is DexMarker.MethodDeclared -> "${marker.classFqn}#${marker.method}" in scan.definedMethodMarkers
      }
      if (observed) {
        if (atLeast == null || LibraryVersions.compare(marker.introducedIn, atLeast!!) > 0) {
          atLeast = marker.introducedIn
        }
        notes += "${marker.describe} is defined (added in ${marker.introducedIn})"
      } else {
        if (below == null || LibraryVersions.compare(marker.introducedIn, below!!) < 0) {
          below = marker.introducedIn
        }
        notes += "${marker.describe} is absent (added in ${marker.introducedIn})"
      }
    }
    return Bounds(atLeast, below, notes.joinToString("; "))
  }

  private fun contradiction(declaredVersion: String, bounds: Bounds): String? {
    bounds.atLeast?.let {
      if (LibraryVersions.compare(declaredVersion, it) < 0) {
        return "the dex says otherwise: ${bounds.evidence}"
      }
    }
    bounds.below?.let {
      if (LibraryVersions.compare(declaredVersion, it) >= 0) {
        return "the dex says otherwise: ${bounds.evidence}"
      }
    }
    return null
  }

  /**
   * The floor the shell's own bytes state: every watched library the shell packages a `.version`
   * file for, since the shell's packaged code runs against the app's classes.
   */
  private fun readShellFloor(shell: ApkArchive): ShellFloor {
    val entries = watched.mapNotNull { library ->
      val (entry, version) = library.shellFloorVersionFiles
        .firstNotNullOfOrNull { name ->
          shell.text(name)?.takeIf { it.firstOrNull()?.isDigit() == true }?.let { name to it }
        } ?: return@mapNotNull null
      LibraryFloor(
        library = library.library,
        minVersion = version,
        why = "the shell packages $entry ($version), and its code runs against the app's copy of " +
          "${library.library} — an older app copy is missing what it links against",
      )
    }
    return ShellFloor(entries)
  }

}

/**
 * The verdict rules, as a pure function of facts already gathered.
 *
 * Separate from [ApkProbe] because a verdict branch is decidable without an APK: every input
 * here is a plain value, so each disqualifier — and the GO that requires all of them absent — is
 * stated as a test over plain inputs rather than over a fixture APK built to trip it.
 */
internal object ProbeVerdictRules {
  fun evaluate(
    manifest: ManifestFacts,
    eras: List<LibraryEra>,
    floor: ShellFloor,
    overlap: DexOverlap?,
    shellGiven: Boolean,
  ): ProbeVerdict {
    val reasons = mutableListOf<ProbeReason>()

    if (manifest.launcherActivity == null) {
      reasons += ProbeReason(
        ProbeReasonCode.NO_LAUNCHER_ACTIVITY,
        "${manifest.packageName} declares no MAIN/LAUNCHER or MAIN/INFO activity, so the harness " +
          "has nothing to start. Adopting means subclassing the test base class, which means " +
          "Kotlin, which means the Gradle path.",
      )
    }

    // Declared providers are recorded in the fingerprint and are NOT a disqualifier. The premise
    // that would have made them one — that an instrumented process does not install the app's own
    // ContentProviders — was measured false on API 34 and 36
    // (docs/internal/devlog/2026-08-31-inprocess-startup-init-providers-verify.md).

    manifest.launcherProcess?.let { process ->
      reasons += ProbeReason(
        ProbeReasonCode.LAUNCHER_IN_OTHER_PROCESS,
        "${manifest.launcherActivity} declares android:process=\"$process\", so it starts in a " +
          "different process from the instrumentation. The harness waits on " +
          "ActivityLifecycleMonitorRegistry, which only ever sees Activities in its own process, so " +
          "the launch would succeed and the wait would time out with nothing naming the cause. The " +
          "app needs a launcher in its default process, or a harness that starts the right Activity " +
          "itself.",
      )
    }

    eras.forEach { era ->
      val libraryFloor = floor.minVersionFor(era.library) ?: return@forEach
      if (!era.present) return@forEach
      val known = era.version
      when {
        known != null && !LibraryVersions.atLeast(known, libraryFloor.minVersion) ->
          reasons += ProbeReason(
            ProbeReasonCode.ERA_BELOW_SHELL_FLOOR,
            "${era.library} is $known in this app, below the shell's floor of " +
              "${libraryFloor.minVersion} — ${libraryFloor.why}.",
          )
        known != null -> Unit
        era.atLeastVersion != null && LibraryVersions.atLeast(era.atLeastVersion, libraryFloor.minVersion) -> Unit
        // `belowVersion` is STRICT — the app is below it, not at it — so a bound equal to the floor
        // already puts the app under the floor. Reading this as `!atLeast(below, floor)` would treat
        // "below 1.11.4, floor 1.11.4" as unresolved and report the wrong disqualifier.
        era.belowVersion != null && LibraryVersions.compare(era.belowVersion, libraryFloor.minVersion) <= 0 ->
          reasons += ProbeReason(
            ProbeReasonCode.ERA_BELOW_SHELL_FLOOR,
            "${era.library} is below ${era.belowVersion} in this app (${era.evidence}), which is " +
              "below the shell's floor of ${libraryFloor.minVersion} — ${libraryFloor.why}. This " +
              "bound comes from an absent dex marker, which R8 can also strip; confirm it with a " +
              "packaged version file or --declared-deps before acting on it.",
          )
        else ->
          reasons += ProbeReason(
            ProbeReasonCode.ERA_UNDETERMINABLE,
            "${era.library} is present but its version could not be determined from the APK " +
              "(${era.evidence}), so the shell's floor of ${libraryFloor.minVersion} could not be " +
              "checked. Pass --declared-deps with this app's dependency versions.",
          )
      }
    }

    if (overlap != null && overlap.classCount > 0) {
      reasons += ProbeReason(
        ProbeReasonCode.DEX_OVERLAP_WITH_SHELL,
        "${overlap.shell} and this app both define ${overlap.classCount} class(es) — e.g. " +
          "${overlap.classes.take(3).joinToString(", ")}. Under instrumentation both dex sets share " +
          "one classloader and only one copy loads, which is how a duplicated Compose crashed text " +
          "editing with Resources\$NotFoundException. Strip them from the shell.",
      )
    }

    if (!shellGiven) {
      reasons += ProbeReason(
        ProbeReasonCode.DEX_OVERLAP_UNCHECKED,
        "No --shell was given, so the dex intersection never ran and an era map cannot see a " +
          "duplicated class. Re-run with --shell pointing at the exact processed shell APK this app " +
          "will be paired with.",
      )
    }

    val disqualified = reasons.any { it.code != ProbeReasonCode.DEX_OVERLAP_UNCHECKED }
    val status = when {
      disqualified -> ProbeStatus.NO_GO
      !shellGiven -> ProbeStatus.INCOMPLETE
      else -> ProbeStatus.GO
    }
    return ProbeVerdict(status, reasons)
  }
}
