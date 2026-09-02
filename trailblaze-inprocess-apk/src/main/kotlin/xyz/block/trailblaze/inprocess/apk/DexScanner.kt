package xyz.block.trailblaze.inprocess.apk

/** What one pass over an APK's dex files should look for. */
internal class DexScanRequest(
  /** Classes whose *definition* answers a presence or marker question. */
  val classesOfInterest: Set<String>,
  /** Method markers, keyed by the class that would declare them. */
  val methodMarkers: List<DexMarker.MethodDeclared>,
  /** The shell's defined classes, to intersect with the app's. Empty when no shell was given. */
  val shellClasses: Set<String>,
)

internal class DexScanResult(
  val definedClassesOfInterest: Set<String>,
  /** `com.foo.Bar#method` for every method marker actually declared. */
  val definedMethodMarkers: Set<String>,
  val overlapWithShell: Set<String>,
  val totalDefinedClasses: Int,
)

/**
 * Reads every `classes*.dex` in an APK once and answers every dex question in that one pass.
 *
 * One pass is not an optimization detail. A large first-party debug build we probe is 59 dex files
 * and half a gigabyte; asking "is this class defined" per marker would re-read all of it per
 * question.
 *
 * Takes dex bytes rather than an [ApkArchive] so a test states its own dex contents directly. The
 * distinction the scanner exists to draw — defined versus merely referenced — cannot be exercised
 * through a real APK, where every class it defines is also referenced.
 */
internal object DexScanner {

  fun scan(apk: ApkArchive, request: DexScanRequest): DexScanResult =
    scan(apk.asDexSource(), request)

  fun scan(dexes: DexSource, request: DexScanRequest): DexScanResult {
    val foundClasses = mutableSetOf<String>()
    val foundMethods = mutableSetOf<String>()
    val overlap = sortedSetOf<String>()
    var total = 0

    val methodMarkerClasses = request.methodMarkers.map { it.classFqn }.toSet()
    val wantedMethodsByClass = request.methodMarkers.groupBy({ it.classFqn }, { it.method })

    dexes.forEach { name, bytes ->
      readingDex(name) {
        val dex = DexFile(bytes)
        val markerClassTypeIdx = mutableMapOf<String, Int>()
        dex.forEachDefinedClass { fqn, typeIdx ->
          total++
          if (fqn in request.classesOfInterest) foundClasses += fqn
          if (fqn in request.shellClasses) overlap += fqn
          if (fqn in methodMarkerClasses) markerClassTypeIdx[fqn] = typeIdx
        }
        if (markerClassTypeIdx.isNotEmpty()) {
          val declared = dex.declaredMethodNames(markerClassTypeIdx.values.toSet())
          markerClassTypeIdx.forEach { (fqn, typeIdx) ->
            val names = declared[typeIdx] ?: return@forEach
            wantedMethodsByClass[fqn].orEmpty().forEach { method ->
              if (method in names) foundMethods += "$fqn#$method"
            }
          }
        }
      }
    }

    return DexScanResult(
      definedClassesOfInterest = foundClasses,
      definedMethodMarkers = foundMethods,
      overlapWithShell = overlap,
      totalDefinedClasses = total,
    )
  }

  /** Every class the APK defines. Used for the shell side of the intersection, which is small. */
  fun definedClasses(apk: ApkArchive): Set<String> = definedClasses(apk.asDexSource())

  fun definedClasses(dexes: DexSource): Set<String> {
    val out = mutableSetOf<String>()
    dexes.forEach { name, bytes ->
      readingDex(name) { DexFile(bytes).forEachDefinedClass { fqn, _ -> out += fqn } }
    }
    return out
  }

  /**
   * Runs [block] over one dex, turning any parse failure into an [ApkReadException] naming the entry.
   *
   * Wrapping the whole read, not just the constructor: [DexFile] validates the magic and the header
   * length up front, but every section offset after that is read from the header and trusted, so a
   * dex whose `class_defs_off` points past the end fails deep inside iteration with an
   * `IndexOutOfBoundsException` that names no file. Letting that escape breaks the probe's contract
   * twice over — the farm's pre-flight prints a stack trace instead of a sentence, and the process
   * exits `3` ("bad flags") instead of `2` ("unreadable input").
   */
  private inline fun <T> readingDex(name: String, block: () -> T): T = try {
    block()
  } catch (e: ApkReadException) {
    throw e
  } catch (e: RuntimeException) {
    throw ApkReadException(
      "$name could not be read as a dex file (${e::class.simpleName}: ${e.message}). The APK is " +
        "truncated, or is not an APK.",
      e,
    )
  }
}

/**
 * Something that can hand out dex bytes one file at a time, with the entry name each came from.
 *
 * A function type rather than a collection because the real implementation streams: an APK large
 * enough to matter here is half a gigabyte across dozens of dex files, none of which may be held
 * alongside the others. The name rides along so a parse failure can say which entry failed.
 */
internal fun interface DexSource {
  fun forEach(block: (name: String, bytes: ByteArray) -> Unit)
}

private fun ApkArchive.asDexSource() = DexSource { block -> forEachDex { name, bytes -> block(name, bytes) } }
