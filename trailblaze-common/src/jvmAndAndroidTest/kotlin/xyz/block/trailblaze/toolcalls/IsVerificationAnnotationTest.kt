package xyz.block.trailblaze.toolcalls

import org.junit.Test
import xyz.block.trailblaze.toolcalls.commands.AssertVisibleTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.WaitForIdleSyncTrailblazeTool
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the `isVerification` capability flag at all three lookup surfaces:
 *
 *  1. `KClass<out TrailblazeTool>.isVerification()` — the source-of-truth read at the
 *     class level (used by the verify gate when only a tool name is in hand).
 *  2. `TrailblazeTool.isVerificationToolInstance()` — the per-instance read that prefers
 *     [TrailblazeToolMetadata.isVerification] when present, falling back to the class
 *     annotation (used by `YamlDefinedTrailblazeTool` to surface YAML config flags).
 *  3. The default value when no annotation field is set — verification must be opt-in,
 *     not opt-out, so existing tools don't accidentally become verify-safe.
 *
 * Without these tests a refactor that drops the metadata fall-through, or flips the
 * default to `true`, would silently change the verify gate's blast radius.
 */
class IsVerificationAnnotationTest {

  // ─── Fixtures ────────────────────────────────────────────────────────────────

  // Plain (non-@Serializable) test fixtures — verify-mode lookup only reads annotations
  // and the toolMetadata override; it never serializes the instance, so the
  // @Serializable contract that real production tools follow isn't required here.

  @TrailblazeToolClass("test_unannotated_tool")
  private class UnannotatedTool : TrailblazeTool

  @TrailblazeToolClass("test_verification_tool", isVerification = true)
  private class VerificationTool : TrailblazeTool

  @TrailblazeToolClass("test_metadata_override_tool")
  private class MetadataOverrideTool(
    override val toolMetadata: TrailblazeToolMetadata?,
  ) : TrailblazeTool

  // ─── KClass extension ────────────────────────────────────────────────────────

  @Test
  fun `KClass isVerification reads true when annotation sets the flag`() {
    assertTrue(VerificationTool::class.isVerification())
    assertTrue(AssertVisibleTrailblazeTool::class.isVerification())
  }

  @Test
  fun `KClass isVerification defaults to false when annotation omits the flag`() {
    assertFalse(UnannotatedTool::class.isVerification())
    // A real non-verification tool from the catalog also defaults to false — the
    // default is opt-in, not opt-out.
    assertFalse(WaitForIdleSyncTrailblazeTool::class.isVerification())
  }

  // ─── Instance helper ─────────────────────────────────────────────────────────

  @Test
  fun `instance helper falls through to class annotation when toolMetadata is null`() {
    assertTrue(VerificationTool().isVerificationToolInstance())
    assertFalse(UnannotatedTool().isVerificationToolInstance())
  }

  @Test
  fun `instance helper prefers toolMetadata isVerification when set to true`() {
    val instance = MetadataOverrideTool(toolMetadata = TrailblazeToolMetadata(isVerification = true))
    assertTrue(
      instance.isVerificationToolInstance(),
      "Per-instance metadata must override the class annotation default — this is the path " +
        "YamlDefinedTrailblazeTool uses to surface `is_verification: true` from YAML.",
    )
  }

  @Test
  fun `instance helper prefers toolMetadata isVerification when set to false`() {
    // Even a class annotated isVerification=true can be instance-overridden to false.
    @Suppress("UNUSED_VARIABLE")
    val placeholder = VerificationTool() // sanity: class is verification
    val instance = MetadataOverrideTool(toolMetadata = TrailblazeToolMetadata(isVerification = false))
    assertFalse(
      instance.isVerificationToolInstance(),
      "Explicit `false` in metadata must win over the class annotation — this is the same " +
        "null-aware override semantics used by isForLlm/isRecordable/requiresHost.",
    )
  }

  @Test
  fun `instance helper falls through when toolMetadata isVerification is null`() {
    // A metadata object with all-null fields is observationally equivalent to no metadata.
    val instance = MetadataOverrideTool(toolMetadata = TrailblazeToolMetadata())
    assertFalse(instance.isVerificationToolInstance())
  }
}
