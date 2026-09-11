package xyz.block.trailblaze.logs.client

import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.isAccessible
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.TrailblazeClockDomain
import xyz.block.trailblaze.toolcalls.TrailblazeTool

/**
 * Forcing function for [withClockMetadata]'s per-subclass `when` branches.
 *
 * The sealed `when` guarantees a NEW log type fails to compile until it gets a branch — but it
 * can't catch a branch that copies only SOME of the three fields (`copy(clock = clock)` compiles
 * fine and silently drops every ingestion anchor for that log type, which unanchors the clock
 * offset derivation with no error at any layer; dropping `timestamp` leaves that log type
 * un-normalized on a host timeline). So this walks every sealed subclass by reflection, builds a
 * minimal instance, and asserts all three fields round-trip through the branch — including the
 * single-argument default paths, whose regression is the silent one.
 *
 * Instances are built generically from primary constructors (optional parameters skipped,
 * nullable filled with null), so a new log type is covered here the moment it compiles — there is
 * no fixture list to keep in sync.
 */
class TrailblazeLogClockMetadataCoverageTest {

  @Test
  fun `withClockMetadata round-trips every clock field on every log type`() {
    val subclasses = TrailblazeLog::class.sealedSubclasses
    assertTrue(subclasses.size >= 20, "expected the full sealed family, got ${subclasses.size}")
    val anchor = Instant.fromEpochMilliseconds(1_234_567)
    val normalized = Instant.fromEpochMilliseconds(2_000_000)

    subclasses.forEach { subclass ->
      val original = instantiate(subclass) as TrailblazeLog
      assertEquals(null, original.clock, "${subclass.simpleName}: fixture must start unstamped")
      assertEquals(null, original.hostReceivedAt, "${subclass.simpleName}: fixture must start unanchored")

      val restamped = original.withClockMetadata(
        timestamp = normalized,
        clock = TrailblazeClockDomain.HOST,
      )
      assertEquals(
        normalized,
        restamped.timestamp,
        "${subclass.simpleName}'s withClockMetadata branch dropped the timestamp argument",
      )
      assertEquals(
        original.timestamp,
        original.withClockMetadata(clock = TrailblazeClockDomain.HOST).timestamp,
        "${subclass.simpleName}: updating the clock alone must preserve the existing timestamp",
      )

      val stamped = original.withClockMetadata(
        clock = TrailblazeClockDomain.DEVICE,
        hostReceivedAt = anchor,
      )
      assertEquals(
        TrailblazeClockDomain.DEVICE,
        stamped.clock,
        "${subclass.simpleName}'s withClockMetadata branch dropped the clock argument",
      )
      assertEquals(
        anchor,
        stamped.hostReceivedAt,
        "${subclass.simpleName}'s withClockMetadata branch dropped the hostReceivedAt argument",
      )

      // The single-argument default paths: setting one field must preserve the other, both ways.
      val anchorOnly = stamped.withClockMetadata(clock = null)
      assertEquals(null, anchorOnly.clock, "${subclass.simpleName}: clock-only update failed")
      assertEquals(
        anchor,
        anchorOnly.hostReceivedAt,
        "${subclass.simpleName}: updating clock alone must preserve the existing anchor",
      )
      val clockOnly = stamped.withClockMetadata(hostReceivedAt = null)
      assertEquals(
        TrailblazeClockDomain.DEVICE,
        clockOnly.clock,
        "${subclass.simpleName}: updating the anchor alone must preserve the existing clock",
      )

      // The branch must COPY, not rebuild: un-stamping returns the exact original, proving no
      // other field was touched.
      assertEquals(
        original,
        stamped.withClockMetadata(clock = null, hostReceivedAt = null),
        "${subclass.simpleName}'s branch changed a field other than the clock metadata",
      )
    }
  }

  /** A minimal instance of [kClass]: optional constructor params skipped, nullable → null. */
  private fun instantiate(kClass: KClass<*>): Any = when {
    kClass.objectInstance != null -> kClass.objectInstance!!
    kClass == String::class -> "fixture"
    kClass == Boolean::class -> false
    kClass == Int::class -> 1
    kClass == Long::class -> 1L
    kClass == Double::class -> 1.0
    kClass == Float::class -> 1.0f
    kClass == Instant::class -> Instant.fromEpochMilliseconds(1_000_000)
    kClass == SessionId::class -> SessionId("coverage-session")
    kClass == JsonObject::class -> JsonObject(emptyMap())
    // Non-sealed interface: use the payload form every log stores tools as.
    kClass == TrailblazeTool::class -> OtherTrailblazeTool(toolName = "fixtureTool")
    kClass.java.isEnum -> kClass.java.enumConstants.first()!!
    kClass.isSealed -> instantiate(kClass.sealedSubclasses.first())
    else -> {
      val ctor = kClass.primaryConstructor
        ?: error("${kClass.qualifiedName} has no primary constructor — add a case to instantiate()")
      ctor.isAccessible = true
      ctor.callBy(
        ctor.parameters
          .filterNot { it.isOptional }
          .associateWith { param -> valueFor(param.type) },
      )
    }
  }

  private fun valueFor(type: KType): Any? {
    if (type.isMarkedNullable) return null
    val classifier = type.classifier as? KClass<*>
      ?: error("Can't build a value for $type — add a case to valueFor()")
    return when (classifier) {
      List::class -> emptyList<Any>()
      Map::class -> emptyMap<Any, Any>()
      Set::class -> emptySet<Any>()
      else -> instantiate(classifier)
    }
  }
}
