package xyz.block.trailblaze.util

import assertk.assertThat
import assertk.assertions.containsExactly
import org.junit.Test

/**
 * Pins down the flag-selection + shell-escape logic of
 * [AndroidHostAdbUtils.intentToAdbBroadcastCommandArgs]. Both pieces are
 * user-facing contracts of `android_sendBroadcast` and can regress silently
 * if unrelated refactors touch them.
 */
class AndroidHostAdbUtilsTest {

  @Test
  fun stringExtrasEmitAsEsFlag() {
    val args = AndroidHostAdbUtils.intentToAdbBroadcastCommandArgs(
      action = "a",
      component = "p/c",
      extras = mapOf("k" to "v"),
    )
    assertThat(args).containsExactly(
      "am", "broadcast", "-a", "'a'", "-n", "'p/c'", "--es", "'k'", "'v'",
    )
  }

  @Test
  fun typedExtrasEmitTheCorrectAmFlag() {
    val args = AndroidHostAdbUtils.intentToAdbBroadcastCommandArgs(
      action = "a",
      component = "",
      extras = linkedMapOf(
        "s" to "text",
        "b" to true,
        "i" to 7,
        "l" to 42L,
        "f" to 1.5f,
      ),
    )
    assertThat(args).containsExactly(
      "am", "broadcast", "-a", "'a'",
      "--es", "'s'", "'text'",
      "--ez", "'b'", "'true'",
      "--ei", "'i'", "'7'",
      "--el", "'l'", "'42'",
      "--ef", "'f'", "'1.5'",
    )
  }

  @Test
  fun emptyActionAndComponentAreOmitted() {
    val args = AndroidHostAdbUtils.intentToAdbBroadcastCommandArgs(
      action = "",
      component = "",
      extras = emptyMap(),
    )
    assertThat(args).containsExactly("am", "broadcast")
  }

  @Test
  fun extraValuesWithSpacesAreQuotedSoShellCannotSplit() {
    val args = AndroidHostAdbUtils.intentToAdbBroadcastCommandArgs(
      action = "a",
      component = "",
      extras = mapOf("greeting" to "hello world"),
    )
    assertThat(args).containsExactly(
      "am", "broadcast", "-a", "'a'",
      "--es", "'greeting'", "'hello world'",
    )
  }

  @Test
  fun extraValuesWithShellMetacharactersAreNeutralized() {
    val args = AndroidHostAdbUtils.intentToAdbBroadcastCommandArgs(
      action = "a",
      component = "",
      extras = mapOf("payload" to "\$(rm -rf /); echo pwned"),
    )
    // After shell-escape, the entire value must be inside single quotes so `sh`
    // treats it as a literal string, not a subshell + statement separator.
    assertThat(args).containsExactly(
      "am", "broadcast", "-a", "'a'",
      "--es", "'payload'", "'\$(rm -rf /); echo pwned'",
    )
  }

  @Test
  fun valuesWithSingleQuotesEscapeCorrectly() {
    val args = AndroidHostAdbUtils.intentToAdbBroadcastCommandArgs(
      action = "a",
      component = "",
      extras = mapOf("phrase" to "it's here"),
    )
    // Single quote inside the value becomes '\'' — close quote, escaped literal
    // quote, reopen quote — so the full string is still a single shell token.
    assertThat(args).containsExactly(
      "am", "broadcast", "-a", "'a'",
      "--es", "'phrase'", "'it'\\''s here'",
    )
  }
}
