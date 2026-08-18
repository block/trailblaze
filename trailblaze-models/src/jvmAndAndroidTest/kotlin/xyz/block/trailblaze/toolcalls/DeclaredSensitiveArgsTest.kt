package xyz.block.trailblaze.toolcalls

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Pure coverage for [DeclaredSensitiveArgs] — the `_meta` reading and the arg resolution that
 * decides what a scripted tool's log payload masks. Both runtimes delegate here, so this is the
 * one place the fail-closed contract is pinned; the runtime suites then assert it end-to-end
 * through an actual log payload.
 */
class DeclaredSensitiveArgsTest {

  private val callArgs = setOf("email", "password")

  @Test
  fun `an absent key masks nothing`() {
    assertEquals(DeclaredSensitiveArgs.None, DeclaredSensitiveArgs.fromMeta(null))
    assertEquals(DeclaredSensitiveArgs.None, DeclaredSensitiveArgs.fromMeta(buildJsonObject { }))
    assertEquals(emptySet(), DeclaredSensitiveArgs.None.resolve(callArgs))
  }

  @Test
  fun `a list of strings masks exactly those names`() {
    val declared = DeclaredSensitiveArgs.fromMeta(
      buildJsonObject {
        put(
          DeclaredSensitiveArgs.META_KEY,
          buildJsonArray { add(JsonPrimitive("password")) },
        )
      },
    )
    assertEquals(DeclaredSensitiveArgs.Named(setOf("password")), declared)
    assertEquals(setOf("password"), declared.resolve(callArgs))
  }

  @Test
  fun `a declared name the call did not supply is simply absent from the payload`() {
    // `resolve` reports intent, not intersection — `withSensitiveArgsRedacted` already ignores keys
    // the payload lacks, and narrowing here would mean a tool masks a different set depending on
    // which optional args a given call happened to pass.
    val declared = DeclaredSensitiveArgs.Named(setOf("password", "otpCode"))
    assertEquals(setOf("password", "otpCode"), declared.resolve(callArgs))
  }

  @Test
  fun `an empty list masks nothing`() {
    val declared = DeclaredSensitiveArgs.fromMeta(
      buildJsonObject { put(DeclaredSensitiveArgs.META_KEY, buildJsonArray { }) },
    )
    assertEquals(DeclaredSensitiveArgs.None, declared)
  }

  @Test
  fun `a non-array value masks every arg`() {
    val declared = DeclaredSensitiveArgs.fromMeta(
      buildJsonObject { put(DeclaredSensitiveArgs.META_KEY, "password") },
    )
    assertEquals(DeclaredSensitiveArgs.AllArgsDeclarationUnreadable, declared)
    assertEquals(callArgs, declared.resolve(callArgs))
  }

  @Test
  fun `an array holding a non-string masks every arg`() {
    // Partial credit would be worse than none: masking only the readable entries would look like
    // it worked while leaving whichever name failed to parse in the clear.
    val declared = DeclaredSensitiveArgs.fromMeta(
      buildJsonObject {
        put(
          DeclaredSensitiveArgs.META_KEY,
          buildJsonArray { add(JsonPrimitive("password")); add(JsonPrimitive(42)) },
        )
      },
    )
    assertEquals(DeclaredSensitiveArgs.AllArgsDeclarationUnreadable, declared)
    assertEquals(callArgs, declared.resolve(callArgs))
  }

  @Test
  fun `masking every arg on a no-arg call masks nothing`() {
    assertEquals(emptySet(), DeclaredSensitiveArgs.AllArgsDeclarationUnreadable.resolve(emptySet()))
  }

  @Test
  fun `the unreadable diagnostic reports the shape and never the value`() {
    // The over-masked log shows the effect but not the cause, so the read says why once. It must
    // not reproduce the value: this is the one place holding a `_meta` entry that claimed to describe
    // secrets and turned out to be something else — printing it would be the leak the type exists
    // to stop.
    val secretish = "hunter2"
    val diagnostic = captureStdErr {
      DeclaredSensitiveArgs.fromMeta(
        buildJsonObject { put(DeclaredSensitiveArgs.META_KEY, secretish) },
      )
    }

    assertTrue(
      diagnostic.contains(DeclaredSensitiveArgs.META_KEY),
      "expected the offending key so the author can find it, got: $diagnostic",
    )
    assertFalse(
      diagnostic.contains(secretish),
      "the diagnostic must never reproduce the malformed value, got: $diagnostic",
    )
  }

  @Test
  fun `a well-formed declaration emits no diagnostic`() {
    // Every correctly-authored tool hits this on every registration; a warning here would train
    // authors to ignore the one that matters.
    val quiet = captureStdErr {
      DeclaredSensitiveArgs.fromMeta(
        buildJsonObject {
          put(DeclaredSensitiveArgs.META_KEY, buildJsonArray { add(JsonPrimitive("password")) })
        },
      )
      DeclaredSensitiveArgs.fromMeta(buildJsonObject { })
    }
    assertEquals("", quiet.trim())
  }

  private fun captureStdErr(block: () -> Unit): String {
    val original = System.err
    val captured = java.io.ByteArrayOutputStream()
    System.setErr(java.io.PrintStream(captured, true))
    try {
      block()
    } finally {
      System.setErr(original)
    }
    return captured.toString()
  }
}
