package xyz.block.trailblaze.host.yaml

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import xyz.block.trailblaze.devices.TrailblazeDriverType
import kotlin.test.Test

/**
 * Characterization of [DesktopDispatchDecision] over the whole
 * (driver × preferHostAgent) space.
 *
 * The dispatch decision used to live as four compound predicates inside a `when {}` that could
 * only be exercised with a device attached, and the multi-device gate re-derived one of those
 * predicates by hand. This table is the behavioral contract both now read: every combination
 * states the path it resolves to, so a change to any driver capability shows up here as a named
 * row rather than as a routing surprise on a device.
 */
class DesktopDispatchDecisionTest {

  private fun decide(
    driver: TrailblazeDriverType,
    preferHostAgent: Boolean = false,
  ) = DesktopDispatchDecision.decide(driver, preferHostAgent)

  /**
   * A retired driver has no runtime to dispatch to, so the table below makes no claim about one.
   * The enum value survives only so old recordings and session logs still deserialize.
   */
  private val runnableDrivers = TrailblazeDriverType.entries
    .filterNot { it in TrailblazeDriverType.RETIRED_DRIVERS }

  /**
   * The full table. Every (driver, preferHostAgent) pair maps to exactly one path, and
   * the map is asserted whole — a new driver has no row until someone writes one, so it cannot
   * quietly inherit a fallthrough.
   */
  @Test
  fun `dispatch path for every driver and host-agent preference`() {
    val actual: Map<String, DispatchPath> = buildMap {
      runnableDrivers.forEach { driver ->
        listOf(false, true).forEach { preferHostAgent ->
          put("$driver/preferHostAgent=$preferHostAgent", decide(driver, preferHostAgent))
        }
      }
    }

    val onDevice = DispatchPath.ON_DEVICE_AGENT
    val hostRpc = DispatchPath.HOST_AGENT_OVER_ONDEVICE_RPC
    val koog = DispatchPath.HOST_IN_PROCESS_KOOG

    assertThat(actual).isEqualTo(
      mapOf(
        // Accessibility: on-device by default, host agent when opted in.
        "ANDROID_ONDEVICE_ACCESSIBILITY/preferHostAgent=false" to onDevice,
        "ANDROID_ONDEVICE_ACCESSIBILITY/preferHostAgent=true" to hostRpc,

        // ANDROID_TEST: always on-device. `preferHostAgent` cannot pull the merge gate onto a path
        // that would hand an unrecorded step to an LLM.
        "ANDROID_TEST/preferHostAgent=false" to onDevice,
        "ANDROID_TEST/preferHostAgent=true" to onDevice,

        // Host-resident drivers: the in-process host agent. `preferHostAgent` is inert — the agent
        // already runs on the host.
        "IOS_HOST/preferHostAgent=false" to koog,
        "IOS_HOST/preferHostAgent=true" to koog,

        "IOS_AXE/preferHostAgent=false" to koog,
        "IOS_AXE/preferHostAgent=true" to koog,

        "PLAYWRIGHT_NATIVE/preferHostAgent=false" to koog,
        "PLAYWRIGHT_NATIVE/preferHostAgent=true" to koog,

        "PLAYWRIGHT_ELECTRON/preferHostAgent=false" to koog,
        "PLAYWRIGHT_ELECTRON/preferHostAgent=true" to koog,

        "REVYL_ANDROID/preferHostAgent=false" to koog,
        "REVYL_ANDROID/preferHostAgent=true" to koog,

        "REVYL_IOS/preferHostAgent=false" to koog,
        "REVYL_IOS/preferHostAgent=true" to koog,

        "COMPOSE/preferHostAgent=false" to koog,
        "COMPOSE/preferHostAgent=true" to koog,
      ),
    )
  }

  /**
   * The invariant the old hand-maintained gate could not state. Multi-device only works on the
   * one path with companion connect and per-device routing, so the set of drivers the gate admits
   * must be exactly the set that can reach that path — no more (a trail that runs the launch
   * device alone and reports success) and no fewer (a trail rejected on a path that would have
   * worked). Derived from [decide] rather than restated, so a capability change moves both.
   */
  @Test
  fun `only the Android on-device driver can run multi-device trails`() {
    val multiDeviceCapable = runnableDrivers.filter { driver ->
      listOf(false, true).any { preferHostAgent ->
        DesktopDispatchDecision.supportsMultiDevice(decide(driver, preferHostAgent))
      }
    }.toSet()

    assertThat(multiDeviceCapable).isEqualTo(
      setOf(TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY),
    )
  }

  /** Every rejection names the change that would actually unblock the run. */
  @Test
  fun `each rejection names the change that would unblock it`() {
    val accessibility = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY

    assertThat(
      DesktopDispatchDecision.multiDeviceRemedy(
        decide(accessibility, preferHostAgent = false),
        accessibility,
      ),
    ).contains("Enable `preferHostAgent`")

    assertThat(
      DesktopDispatchDecision.multiDeviceRemedy(
        decide(TrailblazeDriverType.ANDROID_TEST, preferHostAgent = true),
        TrailblazeDriverType.ANDROID_TEST,
      ),
    ).contains("never dispatches multi-device")

    assertThat(
      DesktopDispatchDecision.multiDeviceRemedy(
        decide(TrailblazeDriverType.IOS_HOST),
        TrailblazeDriverType.IOS_HOST,
      ),
    ).contains("runs entirely on the host")
  }

  /**
   * [DesktopDispatchDecision.decide] splits across two capabilities: the host arm asks
   * `executesToolsOnDevice`, the on-device arms ask `hostRpcReachable`. A driver that ran on the
   * device but was unreachable over RPC would satisfy neither and fall through to
   * [DispatchPath.HOST_IN_PROCESS_KOOG] — a host run against a driver whose tools live on the
   * device. It cannot happen while the two coincide, so this pins that they do. If you add such a
   * driver, `decide` needs a new arm before this expectation is relaxed.
   */
  @Test
  fun `no driver runs on the device without the host being able to reach it`() {
    assertThat(TrailblazeDriverType.entries.filter { it.executesToolsOnDevice }.toSet())
      .isEqualTo(TrailblazeDriverType.entries.filter { it.hostRpcReachable }.toSet())
  }

  /**
   * A trail declaring single-device entries beside its configuration runs single-device when no
   * companions are bound, so a host-only path (iOS, web) must accept it — and refuse it once the
   * run binds companions and so selects the configuration.
   */
  @Test
  fun `a mixed trail is refused on a host-only path only when the run selects its configuration`() {
    val mixedTrail = """
      |config:
      |  devices:
      |    ios-iphone: {}
      |    pos-pair:
      |      devices:
      |        seller:
      |          classifier: android-tablet
      |        buyer:
      |          classifier: android-phone
      |trail:
      |  - prompt: "tap checkout"
      |
    """.trimMargin()
    fun selection(rawDeviceBindings: String?) = MultiDeviceConfigurationResolver.selectConfigurationName(
      yaml = mixedTrail,
      requestConfigurationName = null,
      environmentConfigurationName = null,
      requestDeviceBindingNames = emptySet(),
      rawDeviceBindings = rawDeviceBindings,
    )
    val hostOnly = decide(TrailblazeDriverType.IOS_HOST)

    assertThat(DesktopDispatchDecision.refusesMultiDeviceRun(hostOnly, selection(null))).isFalse()
    assertThat(
      DesktopDispatchDecision.refusesMultiDeviceRun(hostOnly, selection("buyer=emulator-5562")),
    ).isTrue()
  }
}
