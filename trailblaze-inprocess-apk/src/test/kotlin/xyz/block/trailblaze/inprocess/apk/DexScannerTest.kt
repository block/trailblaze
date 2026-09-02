package xyz.block.trailblaze.inprocess.apk

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The dex reader, on the one distinction the whole probe rests on.
 *
 * A class a dex *references* is not a class it *defines*. Text-searching one large first-party app's
 * dex for `androidx/test/espresso/Espresso` returns hits in seven files; that app defines none of
 * them. If the reader conflated the two, the era map would report libraries the app does not ship and
 * the dex intersection would report an overlap that cannot happen.
 */
class DexScannerTest {

  @Test
  fun `a referenced type is not a defined class`() {
    val dex = TestDexBuilder()
      .define("com.example.Defined")
      .reference("androidx.test.espresso.Espresso")
      .build()

    val defined = definedClassesIn(dex)
    assertTrue("com.example.Defined" in defined)
    assertFalse(
      "androidx.test.espresso.Espresso" in defined,
      "a merely referenced type must not count as shipped — that conflation is what makes a text " +
        "search of a dex wrong",
    )
  }

  @Test
  fun `a scan reports only the classes of interest it was asked about`() {
    val dex = TestDexBuilder()
      .define("com.example.Wanted")
      .define("com.example.Unwanted")
      .build()

    val result = DexScanner.scan(
      dexes = dexSourceOf(dex),
      request = DexScanRequest(
        classesOfInterest = setOf("com.example.Wanted", "com.example.Absent"),
        methodMarkers = emptyList(),
        shellClasses = emptySet(),
      ),
    )
    assertEquals(setOf("com.example.Wanted"), result.definedClassesOfInterest)
    // The total is every defined class, not just the interesting ones — it is what tells a reader
    // whether a zero overlap means "nothing shared" or "nothing read".
    assertEquals(2, result.totalDefinedClasses)
  }

  @Test
  fun `a method marker fires only when its owning class declares it`() {
    val dex = TestDexBuilder()
      .define("kotlinx.coroutines.BuildersKt", methods = listOf("runBlocking"))
      .build()

    val result = DexScanner.scan(
      dexes = dexSourceOf(dex),
      request = DexScanRequest(
        classesOfInterest = setOf("kotlinx.coroutines.BuildersKt"),
        methodMarkers = listOf(
          DexMarker.MethodDeclared("kotlinx.coroutines.BuildersKt", "runBlocking", introducedIn = "1.0.0"),
          DexMarker.MethodDeclared("kotlinx.coroutines.BuildersKt", "runBlockingK", introducedIn = "1.11.0"),
        ),
        shellClasses = emptySet(),
      ),
    )
    assertEquals(setOf("kotlinx.coroutines.BuildersKt#runBlocking"), result.definedMethodMarkers)
  }

  @Test
  fun `a marker on a virtual method of a class with fields still fires`() {
    // The shape of a real class, and the one the era markers actually land on: an interface member
    // is a virtual method, and the list holding it comes after both field lists and after the direct
    // methods. Every field skipped by the wrong width, or a walk that stopped at the direct list,
    // silently reports the method as undeclared — which reads as "this app is older than it is" and
    // fails a build over an era it satisfies.
    val dex = TestDexBuilder()
      .define(
        "androidx.compose.runtime.Composer",
        methods = listOf("<init>"),
        virtualMethods = listOf("startReplaceGroup"),
        staticFields = 1,
        instanceFields = 2,
      )
      .build()

    val result = DexScanner.scan(
      dexes = dexSourceOf(dex),
      request = DexScanRequest(
        classesOfInterest = setOf("androidx.compose.runtime.Composer"),
        methodMarkers = listOf(
          DexMarker.MethodDeclared("androidx.compose.runtime.Composer", "startReplaceGroup", introducedIn = "1.7.0"),
          DexMarker.MethodDeclared("androidx.compose.runtime.Composer", "neverDeclared", introducedIn = "9.9.9"),
        ),
        shellClasses = emptySet(),
      ),
    )
    assertEquals(setOf("androidx.compose.runtime.Composer#startReplaceGroup"), result.definedMethodMarkers)
  }

  @Test
  fun `a method the dex only calls is not a method its own class declares`() {
    // The mixed-version shape, and the one that makes a version marker dangerous rather than merely
    // wrong: app code compiled against coroutines 1.11 calls `BuildersKt.runBlockingK`, while the
    // copy of `BuildersKt` the APK packages is 1.10.x and declares no such method. d8 emits a
    // method_id for the call anyway. Reading method_ids would report the app as at-least-1.11,
    // clear it against the shell floor, and let it reach NoSuchMethodError on device.
    val dex = TestDexBuilder()
      .define("kotlinx.coroutines.BuildersKt", methods = listOf("runBlocking"))
      .referenceMethod("kotlinx.coroutines.BuildersKt", "runBlockingK")
      .build()

    val result = DexScanner.scan(
      dexes = dexSourceOf(dex),
      request = DexScanRequest(
        classesOfInterest = setOf("kotlinx.coroutines.BuildersKt"),
        methodMarkers = listOf(
          DexMarker.MethodDeclared("kotlinx.coroutines.BuildersKt", "runBlockingK", introducedIn = "1.11.0"),
        ),
        shellClasses = emptySet(),
      ),
    )
    assertEquals(emptySet(), result.definedMethodMarkers)
  }

  @Test
  fun `the intersection reports classes both sides define and nothing else`() {
    val shellDefines = setOf("com.example.Shared", "com.example.ShellOnly")
    val appDex = TestDexBuilder()
      .define("com.example.Shared")
      .define("com.example.AppOnly")
      // Referenced, not defined: the app calls into a shell class without shipping it, which is the
      // normal case and must NOT read as an overlap.
      .reference("com.example.ShellOnly")
      .build()

    val result = DexScanner.scan(
      dexes = dexSourceOf(appDex),
      request = DexScanRequest(emptySet(), emptyList(), shellClasses = shellDefines),
    )
    assertEquals(setOf("com.example.Shared"), result.overlapWithShell)
  }

  @Test
  fun `classes spread across multiple dex files are all found`() {
    val first = TestDexBuilder().define("com.example.InFirst").build()
    val second = TestDexBuilder().define("com.example.InSecond").build()

    val result = DexScanner.scan(
      dexes = dexSourceOf(first, second),
      request = DexScanRequest(
        classesOfInterest = setOf("com.example.InFirst", "com.example.InSecond"),
        methodMarkers = emptyList(),
        shellClasses = emptySet(),
      ),
    )
    assertEquals(
      setOf("com.example.InFirst", "com.example.InSecond"),
      result.definedClassesOfInterest,
    )
  }

  @Test
  fun `a dex that cannot be parsed is refused by entry name, not thrown as a raw runtime error`() {
    // The farm's pre-flight prints this message and maps the exit code, so a parse failure that
    // escaped as an IndexOutOfBoundsException would read as a stack trace and exit as "bad flags".
    val truncated = TestDexBuilder().define("com.example.Defined").build()
      .copyOf(0x74) // a valid header, then nothing the section offsets point at

    val error = assertFailsWith<ApkReadException> {
      DexScanner.definedClasses(dexSourceOf(truncated))
    }
    assertTrue(error.message!!.contains("classes.dex"), error.message!!)
  }

  private fun definedClassesIn(vararg dexes: ByteArray): Set<String> =
    DexScanner.definedClasses(dexSourceOf(*dexes))

  /** The dex files this test wrote, handed over one at a time exactly as an APK would. */
  private fun dexSourceOf(vararg dexes: ByteArray) = DexSource { block ->
    dexes.forEachIndexed { i, bytes -> block("classes${if (i == 0) "" else "${i + 1}"}.dex", bytes) }
  }
}
