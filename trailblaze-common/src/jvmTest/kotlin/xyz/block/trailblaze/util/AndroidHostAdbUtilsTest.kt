package xyz.block.trailblaze.util

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import org.junit.Test
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform

/**
 * Pins down the flag-selection + shell-escape logic of
 * [AndroidHostAdbUtils.intentToAdbBroadcastCommandArgs] and the pure-logic seams used by the
 * dadb-backed paths: the streaming line decoder (which broke production CI as a P1 when its
 * buffer was rebuilt per packet), the `ADB_SERVER_SOCKET` / `ANDROID_ADB_SERVER_PORT` env-var
 * resolver, the `host:devices` payload parser, and the `tcp:host:port` endpoint parser.
 *
 * All of these can regress silently if unrelated refactors touch them, so they're exercised
 * directly here against curated inputs without spinning up a real Dadb.
 */
class AndroidHostAdbUtilsTest {

  // ── intentToAdbBroadcastCommandArgs ──────────────────────────────────────

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

  // ── StreamingLineDecoder ─────────────────────────────────────────────────
  //
  // This is the same decoder that broke production CI as a P1 (the previous implementation
  // re-allocated the buffer per packet, dropping any line that spanned a packet boundary).
  // Each test exercises one boundary condition that has historically gone wrong.

  @Test
  fun lineSplitAcrossTwoPacketsIsEmittedAsOne() {
    val emitted = mutableListOf<String>()
    val decoder = AndroidHostAdbUtils.StreamingLineDecoder(emitted::add)

    decoder.feed("INSTRUMENTATION_STATUS_CO".toByteArray())
    decoder.feed("DE: 1\n".toByteArray())

    assertThat(emitted).containsExactly("INSTRUMENTATION_STATUS_CODE: 1")
  }

  @Test
  fun multipleLinesInOnePacketAreAllEmitted() {
    val emitted = mutableListOf<String>()
    val decoder = AndroidHostAdbUtils.StreamingLineDecoder(emitted::add)

    decoder.feed("first\nsecond\nthird\n".toByteArray())

    assertThat(emitted).containsExactly("first", "second", "third")
  }

  @Test
  fun crlfLineEndingsAreStripped() {
    val emitted = mutableListOf<String>()
    val decoder = AndroidHostAdbUtils.StreamingLineDecoder(emitted::add)

    decoder.feed("hello\r\nworld\r\n".toByteArray())

    assertThat(emitted).containsExactly("hello", "world")
  }

  @Test
  fun crlfStraddlingPacketBoundaryIsStripped() {
    val emitted = mutableListOf<String>()
    val decoder = AndroidHostAdbUtils.StreamingLineDecoder(emitted::add)

    // Packet 1 ends with a bare \r; packet 2 starts with the matching \n. The bare
    // \r must stay in the buffer until the \n arrives, otherwise we'd emit "hello\r" as
    // the line content.
    decoder.feed("hello\r".toByteArray())
    decoder.feed("\nworld\n".toByteArray())

    assertThat(emitted).containsExactly("hello", "world")
  }

  @Test
  fun multiByteUtf8CodepointSplitAcrossPacketsReassembles() {
    val emitted = mutableListOf<String>()
    val decoder = AndroidHostAdbUtils.StreamingLineDecoder(emitted::add)

    // 🚀 (U+1F680) is 4 bytes in UTF-8: F0 9F 9A 80. Split it across two packets so the
    // continuation bytes arrive separately. The decoder buffers raw bytes (not strings),
    // so the codepoint is reassembled before decoding.
    val rocket = "🚀".toByteArray(Charsets.UTF_8)
    decoder.feed("Launch ".toByteArray() + rocket.copyOfRange(0, 2))
    decoder.feed(rocket.copyOfRange(2, 4) + "!\n".toByteArray())

    assertThat(emitted).containsExactly("Launch 🚀!")
  }

  @Test
  fun trailingPartialLineIsFlushedOnExit() {
    val emitted = mutableListOf<String>()
    val decoder = AndroidHostAdbUtils.StreamingLineDecoder(emitted::add)

    decoder.feed("done\nlast partial".toByteArray())
    decoder.flushTrailingLine()

    assertThat(emitted).containsExactly("done", "last partial")
  }

  @Test
  fun emptyFlushOnExitIsNoOp() {
    val emitted = mutableListOf<String>()
    val decoder = AndroidHostAdbUtils.StreamingLineDecoder(emitted::add)

    decoder.feed("complete\n".toByteArray())
    decoder.flushTrailingLine()

    assertThat(emitted).containsExactly("complete")
  }

  @Test
  fun leadingNewlineEmitsEmptyLine() {
    val emitted = mutableListOf<String>()
    val decoder = AndroidHostAdbUtils.StreamingLineDecoder(emitted::add)

    decoder.feed("\nthen content\n".toByteArray())

    assertThat(emitted).containsExactly("", "then content")
  }

  @Test
  fun consecutiveNewlinesEmitEmptyLines() {
    val emitted = mutableListOf<String>()
    val decoder = AndroidHostAdbUtils.StreamingLineDecoder(emitted::add)

    decoder.feed("a\n\nb\n".toByteArray())

    assertThat(emitted).containsExactly("a", "", "b")
  }

  @Test
  fun manyTinyOneCharPacketsReassembleCorrectly() {
    val emitted = mutableListOf<String>()
    val decoder = AndroidHostAdbUtils.StreamingLineDecoder(emitted::add)

    "ab\ncd\n".forEach { decoder.feed(byteArrayOf(it.code.toByte())) }

    assertThat(emitted).containsExactly("ab", "cd")
  }

  // ── resolveAdbServerEndpoint ─────────────────────────────────────────────

  @Test
  fun defaultsToLocalhost5037WhenNoEnvSet() {
    val (host, port) = AndroidHostAdbUtils.resolveAdbServerEndpoint(env())
    assertThat(host).isEqualTo("localhost")
    assertThat(port).isEqualTo(5037)
  }

  @Test
  fun adbServerSocketTakesPrecedenceOverPort() {
    val (host, port) = AndroidHostAdbUtils.resolveAdbServerEndpoint(
      env(
        "ADB_SERVER_SOCKET" to "tcp:remote.example:9999",
        "ANDROID_ADB_SERVER_PORT" to "1234",
      ),
    )
    assertThat(host).isEqualTo("remote.example")
    assertThat(port).isEqualTo(9999)
  }

  @Test
  fun androidAdbServerPortKeepsHostAsLocalhost() {
    val (host, port) = AndroidHostAdbUtils.resolveAdbServerEndpoint(
      env("ANDROID_ADB_SERVER_PORT" to "8888"),
    )
    assertThat(host).isEqualTo("localhost")
    assertThat(port).isEqualTo(8888)
  }

  @Test
  fun malformedAdbServerSocketFallsBackToDefault() {
    val (host, port) = AndroidHostAdbUtils.resolveAdbServerEndpoint(
      env("ADB_SERVER_SOCKET" to "tcp:no-port"),
    )
    assertThat(host).isEqualTo("localhost")
    assertThat(port).isEqualTo(5037)
  }

  @Test
  fun adbServerSocketWithEmptyHostFallsBack() {
    val (host, port) = AndroidHostAdbUtils.resolveAdbServerEndpoint(
      env("ADB_SERVER_SOCKET" to "tcp::5037"),
    )
    assertThat(host).isEqualTo("localhost")
    assertThat(port).isEqualTo(5037)
  }

  @Test
  fun adbServerSocketWithNonNumericPortFallsBack() {
    val (host, port) = AndroidHostAdbUtils.resolveAdbServerEndpoint(
      env("ADB_SERVER_SOCKET" to "tcp:host:abc"),
    )
    assertThat(host).isEqualTo("localhost")
    assertThat(port).isEqualTo(5037)
  }

  @Test
  fun nonNumericAndroidAdbServerPortFallsBack() {
    val (host, port) = AndroidHostAdbUtils.resolveAdbServerEndpoint(
      env("ANDROID_ADB_SERVER_PORT" to "not-a-port"),
    )
    assertThat(host).isEqualTo("localhost")
    assertThat(port).isEqualTo(5037)
  }

  @Test
  fun blankAdbServerSocketIsIgnored() {
    val (host, port) = AndroidHostAdbUtils.resolveAdbServerEndpoint(
      env(
        "ADB_SERVER_SOCKET" to "   ",
        "ANDROID_ADB_SERVER_PORT" to "4321",
      ),
    )
    assertThat(host).isEqualTo("localhost")
    assertThat(port).isEqualTo(4321)
  }

  // ── parseTcpEndpoint ─────────────────────────────────────────────────────

  @Test
  fun parsesValidTcpEndpoint() {
    val parsed = AndroidHostAdbUtils.parseTcpEndpoint("tcp:host.example:5037")
    assertThat(parsed).isEqualTo("host.example" to 5037)
  }

  @Test
  fun parseTcpEndpointSupportsIpv4Hosts() {
    val parsed = AndroidHostAdbUtils.parseTcpEndpoint("tcp:127.0.0.1:5037")
    assertThat(parsed).isEqualTo("127.0.0.1" to 5037)
  }

  @Test
  fun parseTcpEndpointRejectsMissingTcpPrefix() {
    assertThat(AndroidHostAdbUtils.parseTcpEndpoint("host:5037")).isNull()
  }

  @Test
  fun parseTcpEndpointRejectsMissingPort() {
    assertThat(AndroidHostAdbUtils.parseTcpEndpoint("tcp:host")).isNull()
  }

  @Test
  fun parseTcpEndpointRejectsTrailingColon() {
    assertThat(AndroidHostAdbUtils.parseTcpEndpoint("tcp:host:")).isNull()
  }

  @Test
  fun parseTcpEndpointRejectsEmptyHost() {
    assertThat(AndroidHostAdbUtils.parseTcpEndpoint("tcp::5037")).isNull()
  }

  @Test
  fun parseTcpEndpointRejectsNonNumericPort() {
    assertThat(AndroidHostAdbUtils.parseTcpEndpoint("tcp:host:abc")).isNull()
  }

  // ── parseHostDevicesPayload ──────────────────────────────────────────────

  @Test
  fun parsesSingleDevicePayload() {
    val devices = AndroidHostAdbUtils.parseHostDevicesPayload("emulator-5554\tdevice\n")
    assertThat(devices).containsExactly(
      TrailblazeDeviceId("emulator-5554", TrailblazeDevicePlatform.ANDROID),
    )
  }

  @Test
  fun parsesMultipleDevicesAndFiltersNonDeviceStates() {
    val payload = """
      emulator-5554${'\t'}device
      emulator-5556${'\t'}offline
      ABC123${'\t'}device
      DEF456${'\t'}unauthorized
      GHI789${'\t'}recovery
    """.trimIndent() + "\n"

    val devices = AndroidHostAdbUtils.parseHostDevicesPayload(payload)

    assertThat(devices).containsExactly(
      TrailblazeDeviceId("emulator-5554", TrailblazeDevicePlatform.ANDROID),
      TrailblazeDeviceId("ABC123", TrailblazeDevicePlatform.ANDROID),
    )
  }

  @Test
  fun parsesEmptyPayload() {
    assertThat(AndroidHostAdbUtils.parseHostDevicesPayload("")).isEmpty()
  }

  @Test
  fun ignoresBlankLines() {
    val devices = AndroidHostAdbUtils.parseHostDevicesPayload(
      "\n\nemulator-5554\tdevice\n\n",
    )
    assertThat(devices).containsExactly(
      TrailblazeDeviceId("emulator-5554", TrailblazeDevicePlatform.ANDROID),
    )
  }

  @Test
  fun ignoresMalformedLines() {
    // Missing tab, three columns, etc. — anything that isn't <serial>\t<state> is dropped.
    val devices = AndroidHostAdbUtils.parseHostDevicesPayload(
      "no-tab-here\nemulator-5554\tdevice\nthree\tcolumns\there\n",
    )
    assertThat(devices).containsExactly(
      TrailblazeDeviceId("emulator-5554", TrailblazeDevicePlatform.ANDROID),
    )
  }

  // ── helpers ──────────────────────────────────────────────────────────────

  /** Tiny env-var fixture so callers can write `env("KEY" to "value", ...)`. */
  private fun env(vararg pairs: Pair<String, String>): (String) -> String? {
    val map = pairs.toMap()
    return { name -> map[name] }
  }
}
