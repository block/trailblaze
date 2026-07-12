package xyz.block.trailblaze.quickjs.tools

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Regression guard for the ProGuard-shrink crash (block/trailblaze#194).
 *
 * The release/Homebrew uber JAR is ProGuard-shrunk (`-Ptrailblaze.proguard=true`). quickjs-kt's
 * native `libquickjs.{dylib,so,dll}` reaches back into its Kotlin classes (`com.dokar.quickjs.**`)
 * by name via JNI `FindClass`/`GetMethodID`/`GetFieldID` — the binding callbacks and `QuickJs`'s
 * own `native` method declarations are invoked ONLY from the native side, so the shrinker sees
 * them as unreachable and deletes them (empirically 68 of 90 classes). The native lib then
 * dereferences a class graph missing what it expects and the func_data callback path segfaults the
 * JVM on a `quickjs-tool-engine-N` thread. This reproduces ONLY from the shrunk JAR — source/dev
 * builds don't run ProGuard — which is why it took the crash artifacts, not a unit test, to find.
 *
 * The fix is a `-keep class com.dokar.quickjs.** { *; }` in the ProGuard config, mirroring the
 * existing JNI keeps for Skiko/Netty/JNA. A runtime "exercise the binding under GC pressure" test
 * can't guard this: unit tests run against the intact (non-shrunk) classes, so they pass with or
 * without the keep. The only thing that actually regresses is the ProGuard ruleset, so that's what
 * this guards — a source-level check, same posture as [QuickJsToolHostAsyncBindingRegressionTest].
 *
 * A downstream release build `-include`s this ProGuard file, so this single keep covers every
 * release JAR built from it; this test verifies this file, the shared source of truth.
 */
class QuickJsProguardKeepRegressionTest {

  @Test
  fun `ProGuard rules keep com dokar quickjs classes and their members`() {
    assertTrue(
      hasActiveQuickJsKeep(locateProguardRules().readText()),
      "trailblaze-desktop/proguard-rules.pro is missing a keep for com.dokar.quickjs. " +
        "Without `-keep class com.dokar.quickjs.** { *; }`, ProGuard shrinks away quickjs-kt's " +
        "JNI binding classes and native-method declarations while leaving the native libquickjs " +
        "untouched, and the daemon JVM crashes (SIGABRT/EXC_BAD_ACCESS) the first time a scripted " +
        "tool composes another tool. See block/trailblaze#194 before removing this keep.",
    )
  }

  @Test
  fun `keep detector matches an active rule and rejects a commented or absent one`() {
    val activeKeep = "-keep class com.dokar.quickjs.** { *; }"
    assertTrue(hasActiveQuickJsKeep(activeKeep), "a live keep line should match")
    assertTrue(
      hasActiveQuickJsKeep("  -keep  class   com.dokar.quickjs.**  {  *;  }  "),
      "whitespace/formatting variation should still match",
    )
    assertTrue(
      hasActiveQuickJsKeep("$activeKeep # keep for JNI"),
      "a trailing inline comment must not hide an otherwise-active rule",
    )
    // The regression the comment-stripping defends against: a commented-out keep is inert to
    // ProGuard, so the detector must NOT treat it as present.
    assertFalse(hasActiveQuickJsKeep("# $activeKeep"), "a fully commented-out keep must not match")
    assertFalse(
      hasActiveQuickJsKeep("   #   $activeKeep"),
      "an indented commented-out keep must not match",
    )
    assertFalse(hasActiveQuickJsKeep("-keep class org.other.** { *; }"), "an absent keep must not match")
  }

  private companion object {
    // The keep must retain the class AND its members (`*;`) — keeping only the class shells would
    // still let the shrinker strip the `native` method declarations the .dylib looks up, which is
    // half of what crashed. Tolerant of whitespace/formatting so this isn't brittle to a reformat.
    private val KEEP_REGEX = Regex("""-keep\s+class\s+com\.dokar\.quickjs\.\*\*\s*\{\s*\*;\s*}""")

    /**
     * True iff [rulesText] has an active (non-commented) `-keep class com.dokar.quickjs.** { *; }`.
     * Comment lines are dropped first so a commented-out keep (`# -keep …`) — inert to ProGuard —
     * doesn't falsely satisfy the guard.
     */
    fun hasActiveQuickJsKeep(rulesText: String): Boolean {
      val activeRules =
        rulesText.lineSequence().filterNot { it.trimStart().startsWith("#") }.joinToString("\n")
      return KEEP_REGEX.containsMatchIn(activeRules)
    }
  }

  private fun locateProguardRules(): File {
    // Walk up from cwd to the repo root, same pattern as
    // QuickJsToolHostAsyncBindingRegressionTest.locateSource() (and BundlerYamlSchemaDriftTest).
    val repoRelativePath = "trailblaze-desktop/proguard-rules.pro"
    var dir: File? = File(System.getProperty("user.dir")).absoluteFile
    while (dir != null) {
      val candidate = File(dir, repoRelativePath)
      if (candidate.isFile) return candidate
      dir = dir.parentFile
    }
    fail("Could not locate $repoRelativePath by walking up from ${System.getProperty("user.dir")}.")
  }
}
