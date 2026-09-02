package xyz.block.trailblaze.inprocess.apk

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Everything the in-process adoption path knows about one app APK — the **fingerprint**.
 *
 * This declaration is the schema. It exists in exactly one place because two commands read it from
 * opposite ends of a trust boundary (docs/internal/inprocess-dogfooding-plan.md, items 3–4):
 *
 * - `trailblaze inprocess probe-apk` writes it, where the APK is — often a key-custody team's own
 *   CI, since the fingerprint is a few KB and the APK is hundreds of MB.
 * - `trailblaze inprocess make-test-apk` reads it as **target evidence** on the path where the app
 *   APK's bytes never travel, and runs its signing guards off [certSha256] and [debuggable].
 *
 * That second reader is why those two fields are non-null and not optional. A fingerprint that
 * omits either is not a weaker fingerprint; it is one that silently disarms the guard standing
 * between a key ceremony and a blind signature.
 */
@Serializable
data class AppFingerprint(
  /**
   * Bumped when a field changes meaning or disappears. A reader that does not recognise the version
   * refuses rather than guessing — see [AppFingerprintCodec.decode].
   */
  val schemaVersion: Int = FINGERPRINT_SCHEMA_VERSION,

  /** The app's `package` — what a test APK's `android:targetPackage` has to be stamped to. */
  val targetPackage: String,

  /**
   * The activity the launcher starts, or null when the APK declares none.
   *
   * Resolved the way `PackageManager.getLaunchIntentForPackage` does: MAIN/`CATEGORY_INFO` across
   * the whole package first, then MAIN/`CATEGORY_LAUNCHER`.
   *
   * Null is a disqualifier: with no launcher the harness has nothing to start, and the adopter
   * falls to subclassing the test base class, which means Kotlin, which means Gradle.
   */
  val launcherActivity: String? = null,

  /**
   * The manifest's `android:debuggable`. Target evidence for `make-test-apk`, which refuses a
   * non-debuggable target unless `--release` is passed explicitly.
   */
  val debuggable: Boolean,

  /**
   * Lowercase hex SHA-256 of the DER encoding of the first signer's certificate — the same digest
   * `apksigner verify --print-certs` prints, so a cross-check against either side agrees.
   */
  val certSha256: String,

  /**
   * Every `<provider>` the merged manifest declares.
   *
   * Recorded, not judged. A provider that runs app code in its own `onCreate` was expected to be a
   * disqualifier until it was measured: an instrumented process **does** install the app's
   * ContentProviders, before the first line of test code
   * (`docs/internal/devlog/2026-08-31-inprocess-startup-init-providers-verify.md`). The list stays
   * because it is what a reader compares against when an app initializes in a way the driver later
   * trips over.
   */
  val providers: List<DeclaredProvider> = emptyList(),

  /** What library era the app ships, per library the in-process attach cares about. */
  val eras: List<LibraryEra> = emptyList(),

  /**
   * Classes the shell contributes that this app also defines, or null when the probe was not given
   * a shell.
   *
   * Null is not "no overlap" — it is "unchecked", and it caps the verdict at
   * [ProbeStatus.INCOMPLETE]. An era map cannot see a duplicated class, which is why this check
   * exists separately at all.
   */
  val dexOverlap: DexOverlap? = null,

  /** GO, or the named reasons it is not. */
  val verdict: ProbeVerdict,
)

/**
 * A `<provider>` from the merged manifest.
 *
 * `androidx.startup`'s `InitializationProvider` is called out because it is the one provider whose
 * work is enumerable — `AppInitializer` records what it ran, so a reader can tell app initialization
 * from framework initialization. Neither kind disqualifies an app: both are installed and run under
 * instrumentation, before the first line of test code.
 */
@Serializable
data class DeclaredProvider(
  val className: String,
  val authorities: String? = null,
  @SerialName("androidxStartup") val androidxStartup: Boolean,
)

/** Where a [LibraryEra] entry's claim came from. */
@Serializable
enum class EraSource {
  /** A `META-INF/<group>_<artifact>.version` file AGP packaged. Exact. */
  PACKAGED_VERSION_FILE,

  /** A class or method the app's dex defines (or doesn't), bounding the version. */
  DEX_MARKER,

  /** The caller's `--declared-deps` list. Only as good as the team that wrote it. */
  DECLARED,
}

/**
 * What version of one library the app ships.
 *
 * A minified or `.version`-stripped APK often cannot be pinned to an exact number, so the era is
 * expressed as whatever the evidence actually proves: an exact [version], a lower bound
 * ([atLeastVersion]), an upper bound ([belowVersion]), or none of the three — in which case the era
 * is undeterminable and the caller is told to supply `--declared-deps`.
 */
@Serializable
data class LibraryEra(
  /** Maven-ish coordinate, e.g. `androidx.compose.ui:ui` or `org.jetbrains.kotlinx:kotlinx-coroutines-core`. */
  val library: String,
  /** Whether the app ships this library at all. An absent library cannot shadow ours. */
  val present: Boolean,
  val version: String? = null,
  val atLeastVersion: String? = null,
  val belowVersion: String? = null,
  val source: EraSource,
  /** What was read to reach the claim, in the probe's own words. */
  val evidence: String,
)

/** Result of intersecting the shell's defined classes with the app's. */
@Serializable
data class DexOverlap(
  /** File name of the shell the intersection ran against — the exact one about to be installed. */
  val shell: String,
  val classCount: Int,
  /** Up to [DEX_OVERLAP_SAMPLE_LIMIT] overlapping class names, sorted. */
  val classes: List<String> = emptyList(),
  /** True when [classes] was capped, so a reader never mistakes the sample for the whole set. */
  val truncated: Boolean = false,
)

/** The three answers a probe can give. */
@Serializable
enum class ProbeStatus {
  /** Every check ran and every check passed. */
  GO,

  /** A check ran and failed. [ProbeVerdict.reasons] names which. */
  NO_GO,

  /** No check failed, but at least one could not run — so GO cannot be claimed. */
  INCOMPLETE,
}

/** Why a probe answered what it did. */
@Serializable
enum class ProbeReasonCode {
  /** A library the shell links against is older in the app than the shell's floor. */
  ERA_BELOW_SHELL_FLOOR,

  /** The shell defines classes the app also defines; both cannot load. */
  DEX_OVERLAP_WITH_SHELL,

  /** The manifest declares no MAIN/LAUNCHER or MAIN/INFO activity. */
  NO_LAUNCHER_ACTIVITY,

  /** The launcher activity runs in a process the instrumentation cannot observe. */
  LAUNCHER_IN_OTHER_PROCESS,

  /** A library's era could not be determined from the APK, so the floor check could not run. */
  ERA_UNDETERMINABLE,

  /** The probe ran without `--shell`, so the dex intersection never ran. Caps at INCOMPLETE. */
  DEX_OVERLAP_UNCHECKED,
}

@Serializable
data class ProbeVerdict(
  val status: ProbeStatus,
  val reasons: List<ProbeReason> = emptyList(),
)

@Serializable
data class ProbeReason(
  val code: ProbeReasonCode,
  /**
   * One line, addressed to whoever is trying to adopt. The farm's install-time pre-flight fails in
   * exactly this wording rather than restating it, so a CI log and a local run say the same thing.
   */
  val message: String,
)

/** Bump on any incompatible change to [AppFingerprint]. */
const val FINGERPRINT_SCHEMA_VERSION: Int = 1

/**
 * How many overlapping class names a fingerprint spells out. The intersection can be thousands of
 * entries; a fingerprint is meant to stay a few KB, and [DexOverlap.classCount] carries the total.
 */
const val DEX_OVERLAP_SAMPLE_LIMIT: Int = 50
