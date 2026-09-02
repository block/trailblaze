package xyz.block.trailblaze.inprocess

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What a post-processed test APK is, written beside it as YAML, so a handed-back artifact is
 * auditable without unzipping it.
 *
 * Every field is derived from the bytes that were actually written, never from the flag that asked
 * for them. A flag and the emitted bytes are two sources for one fact, and that is how a record
 * starts saying something the APK does not do.
 */
@Serializable
data class InProcessBuildRecord(
  /** File name of the APK this record describes; the record sits next to it. */
  val apk: String,
  @SerialName("shell_version") val shellVersion: String?,
  @SerialName("shell_sha256") val shellSha256: String,
  /** The package the instrumentation was stamped to attach to. */
  @SerialName("target_package") val targetPackage: String,
  /** Which of the two target-evidence inputs the guards ran against. */
  @SerialName("target_evidence") val targetEvidence: String,
  @SerialName("signing_certificate_sha256") val signingCertificateSha256: String,
  /** True when `--release` waived the debuggable-target guard. */
  @SerialName("release_mode") val releaseMode: Boolean,
  @SerialName("target_config_id") val targetConfigId: String? = null,
  /**
   * Content digest over every injected tool-bundle entry, path and bytes. Not a git revision: the
   * bundles may be built from a working tree, and what matters for reproducing a run is the bytes
   * that shipped.
   */
  @SerialName("trailmap_revision") val trailmapRevision: String? = null,
  @SerialName("injected_trails") val injectedTrails: List<String> = emptyList(),
  @SerialName("injected_tool_bundles") val injectedToolBundles: List<String> = emptyList(),
  /**
   * Whether this APK may load scripted-tool bundles pushed to the device at run time. Decoded back
   * out of the target-config bytes that were injected — see the class docs — so auditing the record
   * answers a question about the signed artifact, not about the command line.
   *
   * Always encoded, even at the default: this is the record's security-relevant line, and an
   * auditor reading `allow_runtime_tool_source: false` is answered where an absent key only raises
   * the question of whether the record predates the field.
   */
  @OptIn(ExperimentalSerializationApi::class)
  @EncodeDefault
  @SerialName("allow_runtime_tool_source")
  val allowRuntimeToolSource: Boolean = false,
)
