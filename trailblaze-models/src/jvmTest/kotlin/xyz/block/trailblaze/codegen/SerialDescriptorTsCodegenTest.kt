package xyz.block.trailblaze.codegen

import kotlinx.serialization.Polymorphic
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Unit coverage for [SerialDescriptorTsCodegen] against the full shape vocabulary the first DTO
 * surface uses: required / nullable / defaulted fields, `List`, `Map`, nested type references,
 * enums, `@SerialName` field remapping, and the numeric primitives. Uses local sample types so the
 * test is independent of any real DTO file's churn.
 */
class SerialDescriptorTsCodegenTest {

  enum class Platform { ANDROID, IOS, WEB }

  @Serializable
  data class DeviceId(
    val instanceId: String,
    val platform: Platform,
  )

  @Serializable
  data class Param(
    val name: String,
    val required: Boolean = true,
    val description: String? = null,
  )

  @Serializable
  data class Sample(
    val deviceId: DeviceId,
    val yaml: String,
    val maxLlmCalls: Int? = null,
    val durationMs: Long = 0,
    val quality: Float? = null,
    val tags: List<String> = emptyList(),
    val params: List<Param> = emptyList(),
    val memory: Map<String, String> = emptyMap(),
    val counts: Map<String, Int> = emptyMap(),
    @SerialName("class") val className: String? = null,
  )

  @Test
  fun `generates expected typescript for the full shape vocabulary`() {
    val ts = SerialDescriptorTsCodegen.generate(
      listOf(Sample.serializer().descriptor),
      header = "// HEADER\n",
    )

    val expected = """
// HEADER

export interface DeviceId {
  instanceId: string;
  platform: Platform;
}

export interface Param {
  name: string;
  required?: boolean;
  description?: string | null;
}

export type Platform = "ANDROID" | "IOS" | "WEB";

export interface Sample {
  deviceId: DeviceId;
  yaml: string;
  maxLlmCalls?: number | null;
  durationMs?: number;
  quality?: number | null;
  tags?: string[];
  params?: Param[];
  memory?: Record<string, string>;
  counts?: Record<string, number>;
  class?: string | null;
}
""".trimStart('\n')

    assertEquals(expected, ts)
  }

  @Serializable
  data class NullableListHolder(val items: List<String?> = emptyList())

  @Test
  fun `nullable list element renders as a union array`() {
    val ts = SerialDescriptorTsCodegen.generate(
      listOf(NullableListHolder.serializer().descriptor),
      header = "",
    )
    assertContains(ts, "items?: (string | null)[];")
  }

  @Serializable
  data class HolderA(val device: DeviceId)

  @Serializable
  data class HolderB(val device: DeviceId)

  @Test
  fun `a type reached from two roots is emitted once and types stay alphabetical`() {
    val ts = SerialDescriptorTsCodegen.generate(
      listOf(HolderA.serializer().descriptor, HolderB.serializer().descriptor),
      header = "",
    )
    assertEquals(1, Regex("export interface DeviceId ").findAll(ts).count(), "DeviceId should be emitted once")
    val iDeviceId = ts.indexOf("export interface DeviceId")
    val iHolderA = ts.indexOf("export interface HolderA")
    val iHolderB = ts.indexOf("export interface HolderB")
    assertTrue(iDeviceId in 0 until iHolderA && iHolderA < iHolderB, "types should be alphabetical")
  }

  @Serializable
  data class KebabHolder(@SerialName("kebab-case") val kebabCase: String)

  @Test
  fun `a serial name that is not a valid identifier is quoted`() {
    val ts = SerialDescriptorTsCodegen.generate(
      listOf(KebabHolder.serializer().descriptor),
      header = "",
    )
    assertContains(ts, "\"kebab-case\": string;")
  }

  // Single-token @SerialName values so each subtype's discriminator LITERAL (the full serial name)
  // and its TS type NAME (the serial name's last segment) coincide — the same alignment real
  // FQN-named sealed subtypes get (e.g. TrailblazeLog.AccessibilityActionLog → type name
  // "AccessibilityActionLog", literal the full FQN).
  @Serializable
  sealed interface Shape {
    @Serializable
    @SerialName("Circle")
    data class Circle(val radius: Int) : Shape

    @Serializable
    @SerialName("Square")
    data class Square(val side: Int) : Shape
  }

  @Serializable
  data class ShapeHolder(val shape: Shape)

  @Test
  fun `a sealed hierarchy renders as a discriminated union`() {
    val ts = SerialDescriptorTsCodegen.generate(listOf(ShapeHolder.serializer().descriptor), header = "")
    assertContains(ts, "export type Shape = Circle | Square;")
    // Subtypes reached only via the sealed base carry the discriminator as a REQUIRED literal.
    assertContains(ts, "class: \"Circle\";")
    assertContains(ts, "class: \"Square\";")
    assertContains(ts, "shape: Shape;")
  }

  @Serializable
  data class ConcreteAndBaseHolder(val circle: Shape.Circle, val shape: Shape)

  @Test
  fun `a subtype also referenced as a concrete field type gets an optional discriminator`() {
    // kotlinx omits the discriminator when the declared (static) type is the concrete class, so
    // the TS field can't be required.
    val ts = SerialDescriptorTsCodegen.generate(listOf(ConcreteAndBaseHolder.serializer().descriptor), header = "")
    assertContains(ts, "class?: \"Circle\";")
    assertContains(ts, "class: \"Square\";")
  }

  @Test
  fun `the discriminator key follows the configured classDiscriminator`() {
    val ts = SerialDescriptorTsCodegen.generate(
      listOf(ShapeHolder.serializer().descriptor),
      header = "",
      classDiscriminator = "type",
    )
    assertContains(ts, "type: \"Circle\";")
  }

  @JvmInline
  @Serializable
  value class WrappedId(val value: String)

  @Serializable
  data class InlineHolder(val id: WrappedId)

  @Test
  fun `an inline value class unwraps to its underlying wire type`() {
    val ts = SerialDescriptorTsCodegen.generate(listOf(InlineHolder.serializer().descriptor), header = "")
    assertContains(ts, "id: string;")
    assertTrue("WrappedId" !in ts, "inline classes have no named wire type: $ts")
  }

  @Serializable
  data class OpenHolder(@Polymorphic val payload: Any)

  @Test
  fun `open polymorphism degrades to a permissive record with a sanitized name`() {
    // kotlinx names the OPEN descriptor `kotlinx.serialization.Polymorphic<Any>` — the angle
    // brackets must not leak into the emitted TypeScript declaration as a bogus generic.
    val ts = SerialDescriptorTsCodegen.generate(listOf(OpenHolder.serializer().descriptor), header = "")
    assertContains(ts, "export type PolymorphicAny = { class: string } & { [key: string]: unknown };")
    assertContains(ts, "payload: PolymorphicAny;")
  }

  // Two distinct types whose serial names differ but whose last segment ("Dup") collides into the
  // same TypeScript name — exactly the case the generate() guard must reject.
  @Serializable
  @SerialName("com.foo.Dup")
  data class DupFoo(val x: Int = 0)

  @Serializable
  @SerialName("com.bar.Dup")
  data class DupBar(val y: Int = 0)

  @Serializable
  data class DupRoot(val a: DupFoo, val b: DupBar)

  @Test
  fun `two types with the same TypeScript name fail loud`() {
    val ex = assertFailsWith<IllegalStateException> {
      SerialDescriptorTsCodegen.generate(listOf(DupRoot.serializer().descriptor), header = "")
    }
    assertTrue(
      ex.message!!.contains("same TypeScript name"),
      "error should name the collision: ${ex.message}",
    )
  }
}
