package xyz.block.trailblaze.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Pins the contract of [TrailCommand.Companion.parseMemorySeeds] — the helper that
 * converts `--memory KEY=VAL` entries into a flat string map at command parse time.
 * Behavior tested:
 *
 *  1. Split on the FIRST `=` so values may contain further `=` (URLs, tokens, JSON).
 *  2. Repeated keys: later-wins (mirrors the YAML-then-CLI precedence at session start).
 *  3. Empty values are allowed (deliberate "unset" sentinel for some flows).
 *  4. Malformed entries (no `=` at all, empty key) throw with a clear message — the CLI
 *     surfaces this as a USAGE exit BEFORE any device or LLM resolution happens, so a typo
 *     never silently drops a seed.
 */
class TrailCommandParseMemorySeedsTest {

  @Test
  fun `parses a single KEY=VAL entry`() {
    val seeds = TrailCommand.parseMemorySeeds(listOf("user=sam"))
    assertEquals(mapOf("user" to "sam"), seeds)
  }

  @Test
  fun `parses multiple repeated entries`() {
    val seeds = TrailCommand.parseMemorySeeds(
      listOf("user=sam", "password=hunter2", "accountTier=PRO"),
    )
    assertEquals(
      mapOf("user" to "sam", "password" to "hunter2", "accountTier" to "PRO"),
      seeds,
    )
  }

  @Test
  fun `splits on the first equals so the value may contain more equals`() {
    val seeds = TrailCommand.parseMemorySeeds(listOf("token=abc=def=ghi"))
    assertEquals(mapOf("token" to "abc=def=ghi"), seeds)
  }

  @Test
  fun `later occurrence overrides earlier for the same key`() {
    val seeds = TrailCommand.parseMemorySeeds(
      listOf("user=first", "user=second", "user=third"),
    )
    assertEquals(mapOf("user" to "third"), seeds)
  }

  @Test
  fun `empty value is preserved`() {
    val seeds = TrailCommand.parseMemorySeeds(listOf("user="))
    assertEquals(mapOf("user" to ""), seeds)
  }

  @Test
  fun `empty list yields empty map`() {
    assertEquals(emptyMap(), TrailCommand.parseMemorySeeds(emptyList()))
  }

  @Test
  fun `missing equals throws with a clear message`() {
    val e = assertFailsWith<IllegalArgumentException> {
      TrailCommand.parseMemorySeeds(listOf("noequals"))
    }
    assertEquals(
      "Invalid --memory entry \"noequals\" — expected KEY=VAL with a non-empty KEY.",
      e.message,
    )
  }

  @Test
  fun `empty key throws with a clear message`() {
    val e = assertFailsWith<IllegalArgumentException> {
      TrailCommand.parseMemorySeeds(listOf("=value"))
    }
    assertEquals(
      "Invalid --memory entry \"=value\" — expected KEY=VAL with a non-empty KEY.",
      e.message,
    )
  }

  @Test
  fun `first malformed entry stops parsing even with later valid entries`() {
    assertFailsWith<IllegalArgumentException> {
      TrailCommand.parseMemorySeeds(listOf("user=sam", "broken", "password=hunter2"))
    }
  }

  @Test
  fun `wholly empty string entry throws with a clear message`() {
    // Defensive: an empty string entry has indexOf('=') == -1, which fails the eq > 0
    // guard the same way "noequals" does. Pin the behavior so a future refactor that
    // switches to substringBefore('=') / split('=', 2) (both of which silently accept
    // empty strings) catches the regression here instead of in production logs.
    val e = assertFailsWith<IllegalArgumentException> {
      TrailCommand.parseMemorySeeds(listOf(""))
    }
    assertEquals(
      "Invalid --memory entry \"\" — expected KEY=VAL with a non-empty KEY.",
      e.message,
    )
  }
}
