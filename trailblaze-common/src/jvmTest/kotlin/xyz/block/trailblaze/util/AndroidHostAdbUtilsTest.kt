package xyz.block.trailblaze.util

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
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

  // ── redactSecretsForLog ──────────────────────────────────────────────────
  // LLM provider tokens passed as `trailblaze.llm.auth.token.<provider>` args must never reach
  // the logged command — CI archives shell-command logs as downloadable build artifacts.

  @Test
  fun authTokenArgValuesAreRedactedInLoggedCommand() {
    val command =
      "am instrument -e 'trailblaze.reverseProxy' 'true' " +
        "-e 'trailblaze.llm.auth.token.acme' 'SECRET_A' " +
        "-e 'trailblaze.llm.auth.token.openai' 'SECRET_B' app/Runner"
    assertThat(AndroidHostAdbUtils.redactSecretsForLog(command)).isEqualTo(
      "am instrument -e 'trailblaze.reverseProxy' 'true' " +
        "-e 'trailblaze.llm.auth.token.acme' <redacted> " +
        "-e 'trailblaze.llm.auth.token.openai' <redacted> app/Runner",
    )
  }

  @Test
  fun authTokenArgValueIsRedactedWhenUnquoted() {
    val command = "am instrument -e trailblaze.llm.auth.token.openai SECRET_B app/Runner"
    assertThat(AndroidHostAdbUtils.redactSecretsForLog(command)).isEqualTo(
      "am instrument -e trailblaze.llm.auth.token.openai <redacted> app/Runner",
    )
  }

  @Test
  fun commandsWithoutAuthTokenArgsArePassedThroughUnchanged() {
    val command = "getprop ro.build.version.sdk"
    assertThat(AndroidHostAdbUtils.redactSecretsForLog(command)).isEqualTo(command)
  }

  @Test
  fun authTokenArgWithEmbeddedSingleQuoteIsFullyRedacted() {
    // shellEscape of a token containing a single quote: abc'def -> 'abc'\''def'.
    val command = "am instrument -e 'trailblaze.llm.auth.token.openai' 'abc'\\''def' app/Runner"
    assertThat(AndroidHostAdbUtils.redactSecretsForLog(command)).isEqualTo(
      "am instrument -e 'trailblaze.llm.auth.token.openai' <redacted> app/Runner",
    )
  }

  // ── execWithReconnectOnTimeout (reconnect-on-timeout retry policy) ───────
  //
  // The dadb hardening: a *hung* shell call (stale/wedged transport that doesn't throw) now evicts
  // the client and retries once against a fresh connection, where previously it returned null after
  // one attempt. Only a timeout retries — a success or a thrown command error is terminal, because a
  // retry must not double-execute a possibly-non-idempotent command.

  @Test
  fun reconnectRetrySucceedsOnFirstAttemptWithoutRetrying() {
    var calls = 0
    val value = AndroidHostAdbUtils.execWithReconnectOnTimeout {
      calls++
      AndroidHostAdbUtils.ShellAttemptResult("ok", AndroidHostAdbUtils.ShellAttemptOutcome.SUCCESS)
    }
    assertThat(value).isEqualTo("ok")
    assertThat(calls).isEqualTo(1)
  }

  @Test
  fun reconnectRetryDoesNotRetryOnCommandFailure() {
    // A thrown command error is terminal — retrying could double-execute a non-idempotent command.
    var calls = 0
    val value = AndroidHostAdbUtils.execWithReconnectOnTimeout {
      calls++
      AndroidHostAdbUtils.ShellAttemptResult(null, AndroidHostAdbUtils.ShellAttemptOutcome.FAILED)
    }
    assertThat(value).isNull()
    assertThat(calls).isEqualTo(1)
  }

  @Test
  fun reconnectRetryRecoversAfterASingleTimeout() {
    // The headline fix: the first attempt hangs (stale transport), the second reconnects and wins.
    var calls = 0
    val value = AndroidHostAdbUtils.execWithReconnectOnTimeout {
      calls++
      if (calls == 1) {
        AndroidHostAdbUtils.ShellAttemptResult(null, AndroidHostAdbUtils.ShellAttemptOutcome.TIMED_OUT)
      } else {
        AndroidHostAdbUtils.ShellAttemptResult("recovered", AndroidHostAdbUtils.ShellAttemptOutcome.SUCCESS)
      }
    }
    assertThat(value).isEqualTo("recovered")
    assertThat(calls).isEqualTo(2)
  }

  @Test
  fun reconnectRetryGivesUpAfterMaxAttemptsOfTimeout() {
    // A genuinely wedged device times out on both attempts: capped at 2, returns null (no loop).
    var calls = 0
    val value = AndroidHostAdbUtils.execWithReconnectOnTimeout {
      calls++
      AndroidHostAdbUtils.ShellAttemptResult(null, AndroidHostAdbUtils.ShellAttemptOutcome.TIMED_OUT)
    }
    assertThat(value).isNull()
    assertThat(calls).isEqualTo(2)
  }

  // ── mismatchedPackageFromInstallError (signing-key-change recovery) ──────
  //
  // A same-package upgrade across signing identities fails with
  // INSTALL_FAILED_UPDATE_INCOMPATIBLE; installApkFile recovers by uninstalling the named package
  // and reinstalling clean. This parser is the pure seam that decides whether to recover and which
  // package to uninstall — so a genuine, non-recoverable failure still surfaces unchanged.

  @Test
  fun mismatchedPackageParsedFromSignatureMismatchFailure() {
    val message =
      "Install failed: Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: " +
        "Existing package com.example.app signatures do not match newer version; ignoring!]"
    assertThat(AndroidHostAdbUtils.mismatchedPackageFromInstallError(message))
      .isEqualTo("com.example.app")
  }

  @Test
  fun mismatchedPackageParsedWithoutExplicitFailureCode() {
    // Some Android builds surface the phrasing without the INSTALL_FAILED_UPDATE_INCOMPATIBLE token.
    val message = "Existing package com.example.app signatures do not match newer version; ignoring!"
    assertThat(AndroidHostAdbUtils.mismatchedPackageFromInstallError(message))
      .isEqualTo("com.example.app")
  }

  @Test
  fun mismatchedPackageParsedFromAlternatePackagePhrasing() {
    // Some Android builds (observed on API 28/29) use "Package" instead of "Existing package".
    val message =
      "Install failed: Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: " +
        "Package com.example.app signatures do not match previously installed version; ignoring!]"
    assertThat(AndroidHostAdbUtils.mismatchedPackageFromInstallError(message))
      .isEqualTo("com.example.app")
  }

  @Test
  fun mismatchedPackageParsedFromAlternatePhrasingWithoutFailureCode() {
    // The "Package" variant without the INSTALL_FAILED_UPDATE_INCOMPATIBLE wrapper.
    val message = "Package com.example.app signatures do not match previously installed version; ignoring!"
    assertThat(AndroidHostAdbUtils.mismatchedPackageFromInstallError(message))
      .isEqualTo("com.example.app")
  }

  @Test
  fun nonSignatureInstallFailureReturnsNull() {
    // A non-recoverable install failure must NOT trigger an uninstall-then-reinstall.
    assertThat(
      AndroidHostAdbUtils.mismatchedPackageFromInstallError("Failure [INSTALL_FAILED_INSUFFICIENT_STORAGE]"),
    ).isNull()
  }

  @Test
  fun nullInstallErrorMessageReturnsNull() {
    assertThat(AndroidHostAdbUtils.mismatchedPackageFromInstallError(null)).isNull()
  }

  @Test
  fun signatureMismatchWithoutANamedPackageReturnsNull() {
    // The mismatch marker is present but no package is named to uninstall — decline rather than
    // guess, so the original failure surfaces instead.
    assertThat(
      AndroidHostAdbUtils.mismatchedPackageFromInstallError("Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]"),
    ).isNull()
  }

  // ── evictExactClient ──────────────────────────────────────────────────────
  //
  // Timeout/interrupt cleanup runs on the wall clock, not on a caught IOException, so it can't
  // rely on the transport to say "your client is stale" — it has to compare identities itself. A
  // concurrent caller sharing the same key can have already evicted-and-reconnected by the time
  // cleanup runs, and a blind `remove(key)` would tear down that healthy replacement instead of
  // the one call actually used.

  private class FakeClient : AutoCloseable {
    var closed = false
    override fun close() {
      closed = true
    }
  }

  @Test
  fun evictExactClientRemovesAndClosesTheMatchingInstance() {
    val client = FakeClient()
    val clients = ConcurrentHashMap<String, FakeClient>().apply { put("serial", client) }
    evictExactClient(clients, "serial", client)
    assertThat(clients.containsKey("serial")).isFalse()
    assertThat(client.closed).isTrue()
  }

  @Test
  fun evictExactClientLeavesAReplacementInPlace() {
    // A concurrent caller already evicted the stale client and cached a fresh one under the same
    // key. Cleanup for the stale client must not touch it.
    val stale = FakeClient()
    val replacement = FakeClient()
    val clients = ConcurrentHashMap<String, FakeClient>().apply { put("serial", replacement) }
    evictExactClient(clients, "serial", stale)
    assertThat(clients["serial"]).isEqualTo(replacement)
    assertThat(replacement.closed).isFalse()
    assertThat(stale.closed).isFalse()
  }

  @Test
  fun evictExactClientIsANoOpWhenNoClientWasResolved() {
    // The interrupted/timeout paths call this even when the worker never got far enough to report
    // a client (e.g. a test's fake `shellCall` that never invokes `onClientResolved`).
    val clients = ConcurrentHashMap<String, FakeClient>()
    evictExactClient(clients, "serial", null)
    assertThat(clients.isEmpty()).isTrue()
  }

  @Test
  fun evictExactClientIsANoOpWhenTheKeyWasAlreadyRemoved() {
    val client = FakeClient()
    val clients = ConcurrentHashMap<String, FakeClient>()
    evictExactClient(clients, "serial", client)
    assertThat(client.closed).isFalse()
  }

  // ── runOnResolvedClient ───────────────────────────────────────────────────
  //
  // Creating a dadb client opens a socket, so a cold connect fails with the same transient
  // IOException as the call itself. Resolution has to sit inside the recovery boundary, or a
  // cold-connect failure skips the retry every other transport error gets.

  @Test
  fun runOnResolvedClientRecoversFromAFailureWhileResolvingTheClient() {
    val result = runOnResolvedClient<FakeClient, String>(
      resolve = { throw IOException("connection refused") },
      onClientResolved = {},
      onTransportFailure = { e, usedClient ->
        assertThat(usedClient).isNull()
        "recovered from ${e.message}"
      },
      block = { "unreachable" },
    )
    assertThat(result).isEqualTo("recovered from connection refused")
  }

  @Test
  fun runOnResolvedClientHandsTheExactClientTheFailedCallUsed() {
    val client = FakeClient()
    val reported = mutableListOf<FakeClient>()
    var failedWith: FakeClient? = null
    runOnResolvedClient(
      resolve = { client },
      onClientResolved = { reported += it },
      onTransportFailure = { _, usedClient -> failedWith = usedClient },
      block = { throw IOException("stream closed") },
    )
    assertThat(reported).containsExactly(client)
    assertThat(failedWith).isEqualTo(client)
  }

  @Test
  fun runOnResolvedClientPropagatesASyncFailureWithoutRecovery() {
    var recovered = false
    val thrown = runCatching {
      runOnResolvedClient<FakeClient, Unit>(
        resolve = { FakeClient() },
        onClientResolved = {},
        onTransportFailure = { _, _ -> recovered = true },
        block = { throw IOException("Sync failed: permission denied") },
      )
    }.exceptionOrNull()
    assertThat(thrown?.message).isEqualTo("Sync failed: permission denied")
    assertThat(recovered).isFalse()
  }

  // ── runOnResolvedClientOnce ───────────────────────────────────────────────
  //
  // The transport every bounded worker uses. A timed-out worker is abandoned, not stopped, so a
  // retry inside it would re-run `pm clear` or a force-stop whenever the adb server finally drops
  // the socket — after the caller threw and the device moved on to the next trail.

  @Test
  fun runOnResolvedClientOnceRunsTheBlockOnceAndPropagatesTheTransportFailure() {
    val client = FakeClient()
    var resolves = 0
    var blockRuns = 0
    var evicted: FakeClient? = null
    val thrown = runCatching {
      runOnResolvedClientOnce<FakeClient, String>(
        resolve = { resolves++; client },
        onClientResolved = {},
        onTransportFailure = { _, usedClient -> evicted = usedClient },
        block = { blockRuns++; throw IOException("connection reset by peer") },
      )
    }.exceptionOrNull()
    assertThat(blockRuns).isEqualTo(1)
    assertThat(resolves).isEqualTo(1)
    assertThat(evicted).isEqualTo(client)
    assertThat(thrown?.message).isEqualTo("connection reset by peer")
  }

  // ── execWithOneTransportRetry ─────────────────────────────────────────────
  //
  // The retry execAdbShellCommand always had, moved out of the worker onto the caller's thread and
  // inside what is left of the bound.

  private fun attemptResult(
    outcome: AndroidHostAdbUtils.ShellAttemptOutcome,
    value: String? = null,
    error: Throwable? = null,
  ) = AndroidHostAdbUtils.ShellAttemptResult(value, outcome, error)

  @Test
  fun execWithOneTransportRetryRetriesATransportFailureWithTheBudgetThatIsLeft() {
    var now = 0L
    val budgets = mutableListOf<Long>()
    val result = AndroidHostAdbUtils.execWithOneTransportRetry(timeoutMs = 1_000L, nowMs = { now }) { budget ->
      budgets += budget
      if (budgets.size == 1) {
        now += 400L
        attemptResult(AndroidHostAdbUtils.ShellAttemptOutcome.FAILED, error = IOException("reset"))
      } else {
        attemptResult(AndroidHostAdbUtils.ShellAttemptOutcome.SUCCESS, value = "ok")
      }
    }
    assertThat(budgets).containsExactly(1_000L, 600L)
    assertThat(result.value).isEqualTo("ok")
  }

  @Test
  fun execWithOneTransportRetryDoesNotRetryATimeout() {
    var calls = 0
    val result = AndroidHostAdbUtils.execWithOneTransportRetry(timeoutMs = 1_000L, nowMs = { 0L }) {
      calls++
      attemptResult(AndroidHostAdbUtils.ShellAttemptOutcome.TIMED_OUT)
    }
    assertThat(calls).isEqualTo(1)
    assertThat(result.outcome).isEqualTo(AndroidHostAdbUtils.ShellAttemptOutcome.TIMED_OUT)
  }

  @Test
  fun execWithOneTransportRetryDoesNotRetryAFailureTheDeviceReported() {
    var calls = 0
    AndroidHostAdbUtils.execWithOneTransportRetry(timeoutMs = 1_000L, nowMs = { 0L }) {
      calls++
      attemptResult(AndroidHostAdbUtils.ShellAttemptOutcome.FAILED, error = IllegalStateException("no"))
    }
    assertThat(calls).isEqualTo(1)
  }

  @Test
  fun execWithOneTransportRetryDoesNotRetryOnceTheBudgetIsSpent() {
    var now = 0L
    var calls = 0
    AndroidHostAdbUtils.execWithOneTransportRetry(timeoutMs = 1_000L, nowMs = { now }) {
      calls++
      now += 1_000L
      attemptResult(AndroidHostAdbUtils.ShellAttemptOutcome.FAILED, error = IOException("reset"))
    }
    assertThat(calls).isEqualTo(1)
  }

  // ── helpers ──────────────────────────────────────────────────────────────

  /** Tiny env-var fixture so callers can write `env("KEY" to "value", ...)`. */
  private fun env(vararg pairs: Pair<String, String>): (String) -> String? {
    val map = pairs.toMap()
    return { name -> map[name] }
  }
}
