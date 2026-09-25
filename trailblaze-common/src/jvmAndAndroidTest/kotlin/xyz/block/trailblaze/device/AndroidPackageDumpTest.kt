package xyz.block.trailblaze.device

import org.junit.Test
import kotlin.test.assertEquals

/**
 * Reading debuggability off a `dumpsys package` dump. Every case here is a real shape the command
 * produces, because the decision it feeds — whether to spend up to a minute AOT-compiling the app
 * under test — is invisible in a passing run either way.
 */
class AndroidPackageDumpTest {

  @Test
  fun `a debuggable package is recognised from either flags line`() {
    val dump = """
      Packages:
        Package [xyz.block.trailblaze.examples.sampleapp] (a1b2c3):
          userId=10190
          flags=[ DEBUGGABLE HAS_CODE ALLOW_CLEAR_USER_DATA ALLOW_BACKUP ]
          pkgFlags=[ DEBUGGABLE HAS_CODE ALLOW_CLEAR_USER_DATA ALLOW_BACKUP ]
    """.trimIndent()
    assertEquals(
      AndroidPackageDump.Debuggable.YES,
      AndroidPackageDump.debuggable(dump, "xyz.block.trailblaze.examples.sampleapp"),
    )
    // Either line alone is enough: which one a dump carries varies by platform version.
    assertEquals(
      AndroidPackageDump.Debuggable.YES,
      AndroidPackageDump.debuggable(
        dump.lines().filterNot { "pkgFlags" in it }.joinToString("\n"),
        "xyz.block.trailblaze.examples.sampleapp",
      ),
    )
  }

  @Test
  fun `a release package is not debuggable`() {
    val dump = """
      Packages:
        Package [com.example.release] (d4e5f6):
          flags=[ HAS_CODE ALLOW_CLEAR_USER_DATA ALLOW_BACKUP ]
          pkgFlags=[ HAS_CODE ALLOW_CLEAR_USER_DATA ALLOW_BACKUP ]
    """.trimIndent()
    assertEquals(
      AndroidPackageDump.Debuggable.NO,
      AndroidPackageDump.debuggable(dump, "com.example.release"),
    )
  }

  @Test
  fun `the word appearing outside a flags line does not count`() {
    val dump = """
      Package [com.example.release]:
        flags=[ HAS_CODE ]
        install reason: DEBUGGABLE mentioned in a comment line
    """.trimIndent()
    assertEquals(
      AndroidPackageDump.Debuggable.NO,
      AndroidPackageDump.debuggable(dump, "com.example.release"),
    )
  }

  @Test
  fun `another package's flags never answer for the target`() {
    // The shape that matters: `dumpsys package <arg>` dumps the WHOLE package database when it
    // does not recognise the argument as a package name. Read without scoping to the target, the
    // first debuggable package in that dump answers for it — and on a userdebug image nearly every
    // package is debuggable, so a release target would read as debug and never get compiled.
    val wholeDatabase = """
      Packages:
        Package [com.android.shell] (aaa111):
          flags=[ DEBUGGABLE HAS_CODE SYSTEM ]
        Package [com.example.release] (bbb222):
          flags=[ HAS_CODE ALLOW_CLEAR_USER_DATA ]
        Package [com.example.other.debugbuild] (ccc333):
          flags=[ DEBUGGABLE HAS_CODE ]
    """.trimIndent()
    assertEquals(
      AndroidPackageDump.Debuggable.NO,
      AndroidPackageDump.debuggable(wholeDatabase, "com.example.release"),
    )
    assertEquals(
      AndroidPackageDump.Debuggable.YES,
      AndroidPackageDump.debuggable(wholeDatabase, "com.example.other.debugbuild"),
    )
  }

  @Test
  fun `a package the dump never mentions is unknown, not release`() {
    // `Unable to find package` is what an uninstalled target prints, and a wedged or truncated
    // dump is empty. Neither says the package is a release build, so neither may read as one:
    // UNKNOWN is what lets the caller apply its own fallback deliberately.
    assertEquals(
      AndroidPackageDump.Debuggable.UNKNOWN,
      AndroidPackageDump.debuggable("Unable to find package: com.example.missing", "com.example.missing"),
    )
    assertEquals(
      AndroidPackageDump.Debuggable.UNKNOWN,
      AndroidPackageDump.debuggable("", "com.example.missing"),
    )
  }

  // -------------------------------------------------------------------------------------------
  // Dexopt state — whether ART has compiled artifacts, read for `android_ensureAppCompiled`.
  // Shapes are verbatim `dumpsys package <pkg>` output from an API 36 x86_64 emulator.
  // -------------------------------------------------------------------------------------------

  @Test
  fun `an install whose dex2oat produced nothing reads as not compiled`() {
    // The state that added ~8s to every cold start of a large app: the artifacts were never
    // produced (`location is error`), so ART verifies the dex from the APK on each process start.
    val dump = """
      Dexopt state:
        [com.example.pos]
          path: /data/app/~~Ab12==/com.example.pos-Cd34==/base.apk
            x86_64: [status=run-from-apk] [reason=unknown] [primary-abi]
              [location is error]
    """.trimIndent()
    val state = checkNotNull(AndroidPackageDump.dexoptState(dump, "com.example.pos"))
    assertEquals(false, state.compiled)
    assertEquals("x86_64: status=run-from-apk reason=unknown", state.summary())
  }

  @Test
  fun `an install that dexopted at any real filter reads as compiled`() {
    val filters = listOf("verify", "speed-profile", "speed", "quicken", "space", "everything", "assume-verified")
    for (filter in filters) {
      val dump = """
        Dexopt state:
          [com.example.pos]
            path: /data/app/~~Ab12==/com.example.pos-Cd34==/base.apk
              x86_64: [status=$filter] [reason=install] [primary-abi]
                [location is /data/app/~~Ab12==/com.example.pos-Cd34==/oat/x86_64/base.odex]
      """.trimIndent()
      val state = checkNotNull(AndroidPackageDump.dexoptState(dump, "com.example.pos"))
      assertEquals(true, state.compiled, "status=$filter should read as compiled")
    }
  }

  @Test
  fun `one uncompiled code path makes the package uncompiled`() {
    // Splits are compiled alongside the base APK; a split left at run-from-apk still costs the
    // launch, and `pm compile` handles the whole package, so any decisive path decides.
    val dump = """
      Dexopt state:
        [com.example.pos]
          path: /data/app/~~Ab12==/com.example.pos-Cd34==/base.apk
            x86_64: [status=verify] [reason=install] [primary-abi]
              [location is /data/app/~~Ab12==/com.example.pos-Cd34==/oat/x86_64/base.odex]
          path: /data/app/~~Ab12==/com.example.pos-Cd34==/split_config.xxhdpi.apk
            x86_64: [status=run-from-apk] [reason=unknown] [primary-abi]
              [location is error]
    """.trimIndent()
    val state = checkNotNull(AndroidPackageDump.dexoptState(dump, "com.example.pos"))
    assertEquals(false, state.compiled)
    assertEquals(2, state.entries.size)
  }

  @Test
  fun `a secondary abi status does not decide when a primary one is present`() {
    val dump = """
      Dexopt state:
        [com.example.pos]
          path: /data/app/~~Ab12==/com.example.pos-Cd34==/base.apk
            arm64: [status=verify] [reason=install] [primary-abi]
              [location is /data/app/~~Ab12==/com.example.pos-Cd34==/oat/arm64/base.odex]
            arm: [status=run-from-apk] [reason=unknown]
              [location is error]
    """.trimIndent()
    val state = checkNotNull(AndroidPackageDump.dexoptState(dump, "com.example.pos"))
    assertEquals(true, state.compiled)
    assertEquals("arm64: status=verify reason=install", state.summary())
  }

  @Test
  fun `an uncompiled split for the same ABI decides even when only the base line is marked primary`() {
    // Whether `[primary-abi]` repeats on every path's line for a shared ABI, or only the first, is
    // not something to depend on: match by ABI name, not by requiring the marker on every line.
    val dump = """
      Dexopt state:
        [com.example.pos]
          path: /data/app/~~Ab12==/com.example.pos-Cd34==/base.apk
            x86_64: [status=verify] [reason=install] [primary-abi]
          path: /data/app/~~Ab12==/com.example.pos-Cd34==/split_config.xxhdpi.apk
            x86_64: [status=run-from-apk] [reason=unknown]
              [location is error]
    """.trimIndent()
    val state = checkNotNull(AndroidPackageDump.dexoptState(dump, "com.example.pos"))
    assertEquals(false, state.compiled)
    assertEquals(2, state.decisive.size)
  }

  @Test
  fun `an uncompiled secondary dex does not make the package uncompiled`() {
    // `pm compile` without `--secondary-dex` never touches these; treating their status as
    // decisive would report a broken package forever, with no compile that could ever fix it.
    val dump = """
      Dexopt state:
        [com.example.pos]
          path: /data/app/~~Ab12==/com.example.pos-Cd34==/base.apk
            x86_64: [status=verify] [reason=install] [primary-abi]
          known secondary dex files:
            /data/user/0/com.example.pos/code_cache/secondary-1.dex
              x86_64: [status=run-from-apk] [reason=unknown]
                [location is error]
    """.trimIndent()
    val state = checkNotNull(AndroidPackageDump.dexoptState(dump, "com.example.pos"))
    assertEquals(true, state.compiled)
    assertEquals(1, state.entries.size)
  }

  @Test
  fun `a split after a secondary dex list is still read`() {
    // Android 8.1 through 13 print `known secondary dex files:` inside EVERY `path:` block — it
    // is the package-level map, repeated per path. A flag that only cleared at the next package
    // header swallowed every split after the base, so an app whose split was uncompiled read as
    // compiled and the repair never ran.
    val dump = """
      Dexopt state:
        [com.example.pos]
          path: /data/app/~~Ab12==/com.example.pos-Cd34==/base.apk
            x86_64: [status=verify] [reason=install] [primary-abi]
            known secondary dex files:
              /data/user/0/com.example.pos/code_cache/secondary-1.dex
                x86_64: [status=verify] [reason=unknown]
          path: /data/app/~~Ab12==/com.example.pos-Cd34==/split_config.x86_64.apk
            x86_64: [status=run-from-apk] [reason=unknown] [primary-abi]
            known secondary dex files:
              /data/user/0/com.example.pos/code_cache/secondary-1.dex
                x86_64: [status=verify] [reason=unknown]
    """.trimIndent()
    val state = checkNotNull(AndroidPackageDump.dexoptState(dump, "com.example.pos"))
    assertEquals(2, state.entries.size)
    assertEquals(false, state.compiled)
  }

  @Test
  fun `a secondary dex section under one package does not leak into the next package's entries`() {
    val dump = """
      Dexopt state:
        [com.example.pos]
          path: /data/app/~~Ab12==/com.example.pos-Cd34==/base.apk
            x86_64: [status=verify] [reason=install] [primary-abi]
          known secondary dex files:
            /data/user/0/com.example.pos/code_cache/secondary-1.dex
              x86_64: [status=run-from-apk] [reason=unknown]
        [com.example.other]
          path: /data/app/~~Ef56==/com.example.other-Gh78==/base.apk
            x86_64: [status=verify] [reason=install] [primary-abi]
    """.trimIndent()
    val other = checkNotNull(AndroidPackageDump.dexoptState(dump, "com.example.other"))
    assertEquals(true, other.compiled)
    assertEquals(1, other.entries.size)
  }

  @Test
  fun `a status token ART has not been seen to print reads as not compiled`() {
    // The safe direction: a needless compile costs seconds once, a missed one costs every launch.
    val dump = """
      Dexopt state:
        [com.example.pos]
          path: /data/app/~~Ab12==/com.example.pos-Cd34==/base.apk
            x86_64: [status=io-error-please-retry] [reason=unknown] [primary-abi]
    """.trimIndent()
    assertEquals(false, checkNotNull(AndroidPackageDump.dexoptState(dump, "com.example.pos")).compiled)
  }

  @Test
  fun `another package's dexopt entry never answers for the target`() {
    // Same hazard as the flags: an argument `dumpsys package` does not recognise dumps EVERY
    // package, and the dexopt section then lists them all. The section also ends at the next
    // unindented header, so a status-shaped line further down the dump cannot leak in.
    val wholeDatabase = """
      Packages:
        Package [com.example.pos] (bbb222):
          flags=[ HAS_CODE ]
      Dexopt state:
        [com.android.shell]
          path: /system/priv-app/Shell/Shell.apk
            x86_64: [status=speed] [reason=prebuilt] [primary-abi]
        [com.example.pos]
          path: /data/app/~~Ab12==/com.example.pos-Cd34==/base.apk
            x86_64: [status=run-from-apk] [reason=unknown] [primary-abi]
              [location is error]
        [com.example.pos.debug]
          path: /data/app/~~Ef56==/com.example.pos.debug-Gh78==/base.apk
            x86_64: [status=verify] [reason=install] [primary-abi]
      Compiler stats:
        [com.example.pos]
          base.apk - 4321
          x86_64: [status=speed] [reason=install] [primary-abi]
    """.trimIndent()
    val state = checkNotNull(AndroidPackageDump.dexoptState(wholeDatabase, "com.example.pos"))
    assertEquals(false, state.compiled)
    assertEquals(1, state.entries.size)
    assertEquals(
      true,
      checkNotNull(AndroidPackageDump.dexoptState(wholeDatabase, "com.example.pos.debug")).compiled,
    )
  }

  @Test
  fun `a package with no dexopt entry is null, not uncompiled`() {
    // Null lets the tool say "is it installed?" instead of compiling a package that is not there.
    assertEquals(null, AndroidPackageDump.dexoptState("Unable to find package: com.example.missing", "com.example.missing"))
    assertEquals(null, AndroidPackageDump.dexoptState("", "com.example.missing"))
    val otherOnly = """
      Dexopt state:
        [com.example.other]
          path: /data/app/~~Ab12==/com.example.other-Cd34==/base.apk
            x86_64: [status=verify] [reason=install] [primary-abi]
    """.trimIndent()
    assertEquals(null, AndroidPackageDump.dexoptState(otherOnly, "com.example.pos"))
  }

  @Test
  fun `a dexopt entry with no status lines is not compiled`() {
    val dump = """
      Dexopt state:
        [com.example.pos]
          path: /data/app/~~Ab12==/com.example.pos-Cd34==/base.apk
    """.trimIndent()
    val state = checkNotNull(AndroidPackageDump.dexoptState(dump, "com.example.pos"))
    assertEquals(false, state.compiled)
    assertEquals("no dexopt status lines", state.summary())
  }

  @Test
  fun `a package id that prefixes the target does not answer for it`() {
    // The detector APK's own package is the target's id with a suffix, and vice versa — the two
    // are always both installed while turbo is attached.
    val dump = """
      Packages:
        Package [com.example.app.debug] (aaa111):
          flags=[ DEBUGGABLE HAS_CODE ]
        Package [com.example.app] (bbb222):
          flags=[ HAS_CODE ]
    """.trimIndent()
    assertEquals(
      AndroidPackageDump.Debuggable.NO,
      AndroidPackageDump.debuggable(dump, "com.example.app"),
    )
  }
}
