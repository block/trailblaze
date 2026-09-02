package xyz.block.trailblaze.inprocess.apk

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Builds the smallest dex [DexFile] can read: string ids, type ids, method ids and class defs, with
 * the header offsets pointing at them.
 *
 * Synthetic rather than a committed real dex because the property under test is one a real dex
 * cannot isolate — that a type merely *referenced* is not a type *defined*. In a real APK every
 * defined class is also referenced, so a reader that confused the two would still pass. Here a test
 * can say "reference `Foo`, define `Bar`" and assert the reader reports exactly `Bar`, which is the
 * distinction the whole dex-intersection check rests on: text-searching a dex for
 * `androidx/test/espresso/Espresso` finds hits in an APK that defines none.
 *
 * Not a general dex writer. Verification data, maps, protos and code items are all absent, because
 * nothing here reads them.
 */
internal class TestDexBuilder {

  private val types = LinkedHashSet<String>()
  private val definedTypes = LinkedHashSet<String>()

  /** Adds a type the dex mentions but does not define — an inherited class, a parameter type. */
  fun reference(fqn: String) = apply { types += descriptorOf(fqn) }

  /**
   * Adds a class the dex defines, declaring [methods] as direct methods.
   *
   * [virtualMethods] and the field counts exist because a `class_data_item` puts the field lists
   * *before* both method lists and the virtual list *after* the direct one, so a reader that
   * mis-skips a field or stops at the direct list reads later methods off the wrong offset. Both are
   * shapes every real class has and nothing else here produces.
   */
  fun define(
    fqn: String,
    methods: List<String> = emptyList(),
    virtualMethods: List<String> = emptyList(),
    staticFields: Int = 0,
    instanceFields: Int = 0,
  ) = apply {
    val descriptor = descriptorOf(fqn)
    types += descriptor
    definedTypes += descriptor
    methods.forEach { declaredMethods += descriptor to it }
    virtualMethods.forEach { declaredVirtualMethods += descriptor to it }
    fieldCounts[descriptor] = staticFields to instanceFields
  }

  /**
   * Adds a method the dex *calls* on [ownerFqn] without [ownerFqn] declaring it.
   *
   * The shape that makes reading `method_ids` wrong: an app compiled against a newer library and
   * packaging an older copy of it emits exactly this — a `method_id` for the new method, owned by a
   * class the same dex defines, with no declaration behind it.
   */
  fun referenceMethod(ownerFqn: String, method: String) = apply {
    types += descriptorOf(ownerFqn)
    referencedMethods += descriptorOf(ownerFqn) to method
  }

  private val declaredMethods = mutableListOf<Pair<String, String>>()
  private val declaredVirtualMethods = mutableListOf<Pair<String, String>>()
  private val referencedMethods = mutableListOf<Pair<String, String>>()
  private val fieldCounts = mutableMapOf<String, Pair<Int, Int>>()

  fun build(): ByteArray {
    // Every method_ids entry the dex carries: the declared ones and the merely-called ones, exactly
    // as d8 would emit them. Only the declared ones get a class_data_item entry below.
    val methodIds = (declaredMethods + declaredVirtualMethods + referencedMethods).distinct()

    // String pool: every type descriptor, then every method name. Order is this list's order, and
    // the id tables below index into it.
    val strings = buildList {
      addAll(types)
      addAll(methodIds.map { it.second }.distinct())
    }
    val stringIndex = strings.withIndex().associate { (i, s) -> s to i }
    val typeList = types.toList()
    val typeIndex = typeList.withIndex().associate { (i, t) -> t to i }

    val stringData = ByteArrayOutputStream()
    val stringDataOffsets = strings.map { s ->
      val offset = STRING_DATA_START + stringData.size()
      val utf8 = s.toByteArray(Charsets.UTF_8)
      writeUleb128(stringData, utf8.size)
      stringData.write(utf8)
      stringData.write(0)
      offset
    }

    val stringIdsOff = STRING_DATA_START + stringData.size()
    val typeIdsOff = stringIdsOff + strings.size * 4
    val methodIdsOff = typeIdsOff + typeList.size * 4
    val classDefsOff = methodIdsOff + methodIds.size * METHOD_ID_SIZE
    val classDataOff = classDefsOff + definedTypes.size * CLASS_DEF_SIZE

    // One class_data_item per defined class, laid out end to end after the class defs. A class with
    // no declared methods still gets one, which keeps this writer's layout uniform.
    val methodIdIndex = methodIds.withIndex().associate { (i, m) -> m to i }
    val classData = ByteArrayOutputStream()
    val classDataOffsets = definedTypes.associateWith { descriptor ->
      val offset = classDataOff + classData.size()
      fun indicesOf(source: List<Pair<String, String>>) = source.filter { it.first == descriptor }
        .map { methodIdIndex.getValue(it) }
        .sorted()
      val direct = indicesOf(declaredMethods)
      val virtual = indicesOf(declaredVirtualMethods)
      val (staticFields, instanceFields) = fieldCounts[descriptor] ?: (0 to 0)
      writeUleb128(classData, staticFields)
      writeUleb128(classData, instanceFields)
      writeUleb128(classData, direct.size)
      writeUleb128(classData, virtual.size)
      repeat(staticFields + instanceFields) {
        // Deliberately wide enough to need two ULEB128 bytes: a reader that skipped fields by a
        // fixed byte count instead of decoding them would land mid-value and read the method lists
        // as garbage, which is the failure this makes visible.
        writeUleb128(classData, FIELD_IDX_DIFF)
        writeUleb128(classData, 0) // access_flags
      }
      // Each list's method_idx_diff is cumulative within that list, and the virtual list restarts
      // from zero rather than continuing the direct one.
      listOf(direct, virtual).forEach { list ->
        var previous = 0
        list.forEach { methodIdx ->
          writeUleb128(classData, methodIdx - previous)
          previous = methodIdx
          writeUleb128(classData, 0) // access_flags
          writeUleb128(classData, 0) // code_off — abstract, so no code item to point at
        }
      }
      offset
    }
    val total = classDataOff + classData.size()

    val buf = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
    // Header. Only the offsets DexFile reads are meaningful; the rest stays zero.
    buf.put(DEX_MAGIC)
    buf.position(HEADER_STRING_IDS_SIZE)
    buf.putInt(strings.size)
    buf.putInt(stringIdsOff)
    buf.putInt(typeList.size)
    buf.putInt(typeIdsOff)
    buf.position(HEADER_METHOD_IDS_SIZE)
    buf.putInt(methodIds.size)
    buf.putInt(methodIdsOff)
    buf.putInt(definedTypes.size)
    buf.putInt(classDefsOff)

    buf.position(STRING_DATA_START)
    buf.put(stringData.toByteArray())

    buf.position(stringIdsOff)
    stringDataOffsets.forEach { buf.putInt(it) }

    buf.position(typeIdsOff)
    typeList.forEach { buf.putInt(stringIndex.getValue(it)) }

    buf.position(methodIdsOff)
    methodIds.forEach { (owner, method) ->
      buf.putShort(typeIndex.getValue(owner).toShort())
      buf.putShort(0) // proto_idx — unread
      buf.putInt(stringIndex.getValue(method))
    }

    buf.position(classDefsOff)
    definedTypes.forEach { descriptor ->
      buf.putInt(typeIndex.getValue(descriptor))
      // Everything between class_idx and class_data_off is unread here.
      repeat(CLASS_DEF_CLASS_DATA_OFF - 4) { buf.put(0) }
      buf.putInt(classDataOffsets.getValue(descriptor))
      buf.putInt(0) // static_values_off
    }

    buf.position(classDataOff)
    buf.put(classData.toByteArray())
    return buf.array()
  }

  private fun descriptorOf(fqn: String) = "L${fqn.replace('.', '/')};"

  private fun writeUleb128(out: ByteArrayOutputStream, value: Int) {
    var remaining = value
    do {
      var byte = remaining and 0x7f
      remaining = remaining ushr 7
      if (remaining != 0) byte = byte or 0x80
      out.write(byte)
    } while (remaining != 0)
  }

  private companion object {
    val DEX_MAGIC = byteArrayOf(0x64, 0x65, 0x78, 0x0a, 0x30, 0x33, 0x35, 0x00)
    const val HEADER_STRING_IDS_SIZE = 0x38
    const val HEADER_METHOD_IDS_SIZE = 0x58
    const val STRING_DATA_START = 0x70
    const val METHOD_ID_SIZE = 8
    const val CLASS_DEF_SIZE = 32
    const val CLASS_DEF_CLASS_DATA_OFF = 24

    /** Two ULEB128 bytes' worth, so a field is not skippable by a fixed-width read. */
    const val FIELD_IDX_DIFF = 0x81
  }
}
