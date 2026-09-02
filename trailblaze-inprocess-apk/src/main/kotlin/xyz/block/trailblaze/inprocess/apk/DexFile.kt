package xyz.block.trailblaze.inprocess.apk

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal reader for one `classes*.dex`, exposing the two facts the probe needs: which classes this
 * dex **defines**, and which methods one of those classes declares.
 *
 * "Defines" rather than "mentions" is the whole point. `grep`-ing a dex for a class name also hits
 * every dex that merely *references* it, which is why a text search says one large first-party build
 * we probe ships Espresso (seven dex files mention it) when it defines none. The dex-overlap tripwire
 * and the era markers are both claims about what actually loads, so both read `class_defs`.
 *
 * Only the header, `string_ids`, `type_ids`, `class_defs`, `method_ids` and the `class_data_item`
 * each class def points at are parsed; bytecode, annotations and debug info are skipped. Format
 * reference: AOSP `dex_file.h` / "Dalvik Executable format". No ordering assumption is made about
 * any section — tables are scanned linearly rather than binary-searched, so a dex written by a
 * non-`d8` tool still reads.
 */
internal class DexFile(bytes: ByteArray) {

  private val buf: ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

  private val stringIdsSize: Int
  private val stringIdsOff: Int
  private val typeIdsSize: Int
  private val typeIdsOff: Int
  private val methodIdsSize: Int
  private val methodIdsOff: Int
  private val classDefsSize: Int
  private val classDefsOff: Int

  init {
    if (bytes.size < HEADER_SIZE) {
      throw ApkReadException("A dex entry is ${bytes.size} bytes — too short to carry a dex header.")
    }
    val magic = String(bytes, 0, 4, Charsets.US_ASCII)
    if (magic != "dex\n") {
      throw ApkReadException("A dex entry does not start with the dex magic (found '$magic').")
    }
    stringIdsSize = buf.getInt(0x38)
    stringIdsOff = buf.getInt(0x3c)
    typeIdsSize = buf.getInt(0x40)
    typeIdsOff = buf.getInt(0x44)
    methodIdsSize = buf.getInt(0x58)
    methodIdsOff = buf.getInt(0x5c)
    classDefsSize = buf.getInt(0x60)
    classDefsOff = buf.getInt(0x64)
  }

  /** The `type_ids` table, resolved lazily: index into it is the `class_idx` of a `class_def`. */
  private fun typeDescriptor(typeIdx: Int): String? {
    if (typeIdx < 0 || typeIdx >= typeIdsSize) return null
    val descriptorStringIdx = buf.getInt(typeIdsOff + typeIdx * 4)
    return string(descriptorStringIdx)
  }

  private fun string(stringIdx: Int): String? {
    if (stringIdx < 0 || stringIdx >= stringIdsSize) return null
    val dataOff = buf.getInt(stringIdsOff + stringIdx * 4)
    return readMutf8(dataOff)
  }

  /**
   * Reads a `string_data_item`: a ULEB128 UTF-16 length, then MUTF-8 bytes terminated by NUL.
   *
   * Decoded as plain UTF-8 rather than MUTF-8. The two differ only for the NUL character and
   * supplementary-plane code points, neither of which appears in a JVM type descriptor or method
   * name — and every string this reader resolves is one of those.
   */
  private fun readMutf8(at: Int): String {
    var p = at
    // Skip the ULEB128 length; the NUL terminator is what bounds the read.
    while ((buf.get(p).toInt() and 0x80) != 0) p++
    p++
    val start = p
    while (buf.get(p).toInt() != 0) p++
    val out = ByteArray(p - start)
    for (i in out.indices) out[i] = buf.get(start + i)
    return String(out, Charsets.UTF_8)
  }

  /**
   * Invokes [block] for every class this dex defines, with its dotted FQN and its `type_ids` index
   * (which [declaredMethodNames] takes).
   */
  fun forEachDefinedClass(block: (fqn: String, typeIdx: Int) -> Unit) {
    for (i in 0 until classDefsSize) {
      val typeIdx = buf.getInt(classDefsOff + i * CLASS_DEF_ITEM_SIZE)
      val descriptor = typeDescriptor(typeIdx) ?: continue
      val fqn = descriptorToFqn(descriptor) ?: continue
      block(fqn, typeIdx)
    }
  }

  /**
   * Every method name **declared** on the classes whose `type_ids` indices are in [typeIdxs].
   *
   * Read from each class's `class_data_item`, not by filtering `method_ids` on `class_idx`. The two
   * differ exactly where it matters: `method_ids` also carries every method this dex *calls*,
   * including calls on a class the same dex defines. An app compiled against a newer library but
   * packaging an older copy of it therefore has a `method_id` for the new method and no declaration
   * of it — which is the shape a version marker is supposed to detect, not the shape it should be
   * fooled by. Reading `method_ids` would clear that app against the floor and let it reach
   * `NoSuchMethodError` on device.
   *
   * Walks `class_defs` (one entry per defined class) rather than `method_ids` (hundreds of thousands
   * of entries in a real app), so this is also the cheaper pass.
   */
  fun declaredMethodNames(typeIdxs: Set<Int>): Map<Int, Set<String>> {
    if (typeIdxs.isEmpty()) return emptyMap()
    val out = mutableMapOf<Int, MutableSet<String>>()
    for (i in 0 until classDefsSize) {
      val base = classDefsOff + i * CLASS_DEF_ITEM_SIZE
      val typeIdx = buf.getInt(base)
      if (typeIdx !in typeIdxs) continue
      val classDataOff = buf.getInt(base + CLASS_DEF_CLASS_DATA_OFF)
      // Zero means the class declares no fields and no methods — an interface marker, say.
      if (classDataOff == 0) continue
      val names = out.getOrPut(typeIdx) { mutableSetOf() }
      readClassDataMethodNames(classDataOff) { names += it }
    }
    return out
  }

  /**
   * Reads the method names out of one `class_data_item`.
   *
   * Layout: four ULEB128 counts, then the field lists, then the direct and virtual method lists.
   * Each list's `*_idx_diff` is cumulative **within that list**, and both method lists restart from
   * zero, which is why the running index is reset between them.
   */
  private fun readClassDataMethodNames(at: Int, block: (String) -> Unit) {
    val cursor = Uleb128Cursor(at)
    val staticFields = cursor.next()
    val instanceFields = cursor.next()
    val directMethods = cursor.next()
    val virtualMethods = cursor.next()
    repeat(staticFields + instanceFields) {
      cursor.next() // field_idx_diff
      cursor.next() // access_flags
    }
    for (count in intArrayOf(directMethods, virtualMethods)) {
      var methodIdx = 0
      repeat(count) {
        methodIdx += cursor.next() // method_idx_diff
        cursor.next() // access_flags
        cursor.next() // code_off
        methodName(methodIdx)?.let(block)
      }
    }
  }

  private fun methodName(methodIdx: Int): String? {
    if (methodIdx < 0 || methodIdx >= methodIdsSize) return null
    return string(buf.getInt(methodIdsOff + methodIdx * METHOD_ID_ITEM_SIZE + 4))
  }

  /** A read position over ULEB128 values, which are variable width and so cannot be indexed. */
  private inner class Uleb128Cursor(private var at: Int) {
    fun next(): Int {
      var result = 0
      var shift = 0
      while (true) {
        val byte = buf.get(at++).toInt()
        result = result or ((byte and 0x7f) shl shift)
        if ((byte and 0x80) == 0) return result
        shift += 7
      }
    }
  }

  companion object {
    private const val HEADER_SIZE = 0x70
    private const val CLASS_DEF_ITEM_SIZE = 32

    /** Byte offset of `class_data_off` within a `class_def_item`. */
    private const val CLASS_DEF_CLASS_DATA_OFF = 24
    private const val METHOD_ID_ITEM_SIZE = 8

    /** `Lcom/foo/Bar;` → `com.foo.Bar`. Null for arrays and primitives, which are never defined. */
    fun descriptorToFqn(descriptor: String): String? {
      if (descriptor.length < 3 || descriptor[0] != 'L' || !descriptor.endsWith(";")) return null
      return descriptor.substring(1, descriptor.length - 1).replace('/', '.')
    }
  }
}
