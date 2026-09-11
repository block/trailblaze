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
