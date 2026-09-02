# Trailblaze Android test driver

This module is the first native instrumentation driver for mixed Android View and Jetpack Compose
applications. It runs Trailblaze tools inside the application's existing AndroidJUnitRunner test,
so View actions use Espresso and composable actions use the app's existing
`AndroidComposeTestRule`.

## Integration shape

The driver deliberately does not create an Activity or Compose rule. Wrap it with the application's
existing test lifecycle (for example, a rule that owns fixtures, idling resources, cleanup, and the
Compose clock), then expose the current Activity and that already-installed Compose rule:

```kotlin
val target =
  RuleBackedAndroidTestTarget(
    activityProvider = { currentActivity() },
    composeTestRule = existingComposeRule,
    screenshotProvider = { captureWholeDisplay() },
  )

val agent =
  AndroidTestTrailblazeAgent(
    target = target,
    trailblazeLogger = logger,
    trailblazeDeviceInfoProvider = deviceInfoProvider,
    sessionProvider = sessionProvider,
    trailblazeToolRepo = toolRepo,
  )
```

`activityProvider` is evaluated for every capture so Activity recreation cannot leave the driver
holding a stale instance. Do not install a second Compose rule: doing so replaces the app harness's
clock/idling bridge and can deadlock mixed Espresso/Compose tests.

## Hybrid hierarchy

`AndroidTestScreenState` emits one non-overlapping tree:

- classic Views are traversed in-process from the live `android.view.View` objects and carry the
  `androidView` selector dialect;
- an `AndroidComposeView` remains as a structural boundary, with its descendants replaced by native
  Compose test semantics, carrying the `compose` dialect;
- dialog or popup Compose roots that do not intersect a host are retained as sibling roots.

The Compose half is read from the **unmerged** semantics tree. The merged tree folds a composable's
children into it — a Button's Text child disappears into the Button — which erases exactly the
structure a selector needs to talk about.

The View traversal runs on the UI thread. It reads live state (`isShown`, `getGlobalVisibleRect`,
`isFocused`) that a layout pass mutates, and reading that from the instrumentation thread yields a
half-updated tree with no error to show for it.

**Classic View windows other than the Activity's are not captured.** The traversal starts at
`activity.window.decorView`, so a platform `AlertDialog` or a `PopupWindow` — each of which owns a
separate window root — is absent from the snapshot, and a selector for its contents finds nothing
even though Espresso can interact with it. Enumerating the other roots means reaching into
`WindowManagerGlobal` by reflection, which is what Espresso's own internal roots oracle does; this
driver does not. Compose dialogs and popups are unaffected: they surface as their own semantics
roots and are retained as siblings.

## Selectors

Two native dialects, matching the two halves of the tree.

`androidView` reads the live view objects, so it can match things no accessibility projection
carries: a `View.setTag` string, the real un-sanitized `className`, `inputType`, and the difference
between an unchecked checkbox and a view that is not `Checkable` at all (`isChecked: false` vs the
field being absent). Matching is **strict**: patterns are case-sensitive and `.` does not cross a
newline. This is the opposite of `androidMaestro`, which is deliberately lenient, so a pattern
copied from a Maestro selector may stop matching here — that is the point.

```yaml
- androidTest_tap:
    nodeSelector:
      androidView:
        textRegex: "Pick Me"
      childOf:
        androidView:
          tagRegex: "row_beta"
```

```yaml
- androidTest_type:
    value: "4242 4242 4242 4242"
    nodeSelector:
      compose:
        testTag: "card_number_field"
```

## Tools

The driver's entire surface is three tools that name an intent, never a backend:

- `androidTest_tap`
- `androidTest_type`
- `androidTest_assertVisible`

Each takes a `nodeSelector`, resolves it against a hierarchy captured at that moment, and acts on
the node that matched. There are no per-backend tools to reach for; Espresso and the Compose rule
are reached through internal `AndroidViewActions` / `AndroidComposeActions`.

**Selectors do all the matching.** The native layer only synchronizes and acts, on an identity: a
`View` instance for Espresso, a `SemanticsNode.id` for Compose. Nothing re-describes a matched node
as native matcher arguments, because that made the framework search a second time under different
semantics and it could land somewhere else — `androidView`'s regexes are anchored and
case-sensitive, Espresso's `withText` is exact equality, and Compose's `hasText` matches many nodes.
It also makes some selectors expressible that no property matcher could carry: two buttons with
identical text and no ids are told apart by the row each sits in.

Because the trail never names a backend, a screen re-laid-out from Views to Compose keeps replaying
the same trail.

**Relocation.** The node a trail names is often not the node that handles the action — a `TextView`
inside a clickable row, or a text field whose test tag sits on the wrapper while the set-text action
lives on an inner node. The action moves to the nearest node that owns it (self, then ancestors,
then descendants) and **says so in the tool result**. A silent jump to a neighbouring node is how a
passing test ends up asserting nothing.

Every name carries the `androidTest_` prefix because the driver, not the backend, is the real
constraint: `AndroidTestExecutableTool.execute()` hard-errors unless `AndroidTestTrailblazeAgent`
dispatches it, so a name like `androidView_click` would advertise portability to other Android
drivers that does not exist.

The bundled `androidTest` trailmap exposes them as the single `android_test` toolset for driver key
`android-test`.

## Custom views

A custom view describes itself through two things this driver already reads, with no test-only
opt-in: a real `className` (un-sanitized, so `com.example.ui.AmountView` is matchable), and
`stateDescription` — the accessibility property where a view publishes its own state ("Expanded",
"3 of 10"). `stateDescription` is read from `View.getStateDescription()` first and from
`onInitializeAccessibilityNodeInfo` only when that is empty; both are API 30+.

Not yet covered: a canvas-drawn view that exposes its contents as accessibility **virtual children**
through an `AccessibilityNodeProvider`. Those children have no `View` to act on, and there is no
public API to enumerate them across API levels. Such a view is currently visible only as one opaque
node with its class name and state description.

## On-device tests

`src/androidTest` carries the driver's behavioral contract, run in CI by the
`uitest-trailblaze-android-test-driver` step against a self-contained mixed View + Compose fixture
Activity (`MixedUiFixtureActivity`):

- `androidView` selectors drive classic Views, and `compose` selectors drive semantics nodes, both
  through the same three tools, each asserting the app's own state changed.
- One agent crosses both backends in a single flow and back.
- Two buttons with identical text and no ids are told apart by the row each sits in, and the tap
  lands on the one that matched.
- `index` counts down the screen and is applied once, by the resolver.
- Tapping a label reaches the clickable row that handles it, and the result says it relocated. Same
  for a Compose button's label in the unmerged tree.
- The hybrid screen state carries both backends' nodes without duplicating the Compose subtree (a
  `View` tag is invisible to accessibility, and a Compose `testTag` is only observable through
  native semantics — seeing both proves neither half is an accessibility projection).
- Every node in the tree has a live handle behind it, or identity dispatch has nothing to act on.
- `androidView` matching is case-sensitive.
- An ambiguous selector fails rather than guessing, and one that matches nothing fails loudly
  instead of acting blind.

The fixture deliberately uses `createEmptyComposeRule()` because it owns no other Compose harness;
a consumer app must pass its existing rule instead.

## Interactive / on-demand execution (planned)

Deterministic in-test replay is the shipped mode. The interactive mode — host CLI or MCP driving a
live in-process session — will reuse the existing on-device RPC seam rather than inventing one:

1. A long-running "harness test" method (a normal `@Test` under the app's own rule chain) starts
   `OnDeviceRpcServer` and then parks in a command pump loop instead of a scripted tool list.
2. The host connects through the existing adb-forwarded RPC channel
   (`trailblaze-android-ondevice-mcp`), exactly as `ANDROID_ONDEVICE_INSTRUMENTATION` does today.
3. Each RPC tool batch is enqueued to the pump and executed on the instrumentation thread by this
   module's `AndroidTestTrailblazeAgent` — Espresso and Compose calls must not run on Ktor IO
   threads.
4. The session ends by an explicit `finish` command or idle timeout, letting the rule chain tear
   down normally.

That gives `trailblaze session` / MCP the same tool surface as recorded replay with no new
transport. It is not part of this change.

**Still owed — the binary RPC codec cannot carry this driver's tree.**
`OnDeviceRpcProtoCodec.toProto` requires an `AndroidAccessibility` or `AndroidMaestro` detail and
throws on anything else; the proto has no field for `androidView` or `compose`. Now that the pump
exists, that tree does cross the wire, so `ANDROID_TEST` is pinned to HTTP/JSON through
`TrailblazeDriverType.protoWireSafe = false`. Without the pin every tree-bearing response
comes back an encode failure — readiness polling included, since its probe asks for the tree by
default — and the symptom is a full readiness timeout against a server that is up and answering.
Teaching the codec both variants (new proto messages plus both mapping directions) removes the pin
and is the real fix.

**Also owed — capture is not serialized against a running trail.** The screen-state captor
deliberately answers on the Ktor worker rather than crossing to the instrumentation thread, so
readiness polling still works while a trail runs. `AndroidViewHierarchyCollector` marshals its
reads through `runOnMainSync`, but `composeRoots()` fetches semantics on the calling thread, so a
capture taken mid-interaction reads a tree the instrumentation thread is recomposing. Today the
host only captures between runs; streaming frames through a running trail needs the two
serialized first.

## Performance expectations and the virtual-clock question

Espresso/Compose tests feel instant for three distinct reasons: (1) Compose test
synchronization can advance the app on a **virtual clock** (`MainTestClock` drives
recompositions, animations and gestures, so animations cost ~0 wall-clock), (2) the test
starts *on the screen under test*, and (3) the app typically fakes the network. This driver
attacks a fourth cost — cross-process IPC and the settle floor — which is real but bounded.
It does not attack the other three, so it should not be expected to close the whole gap to a
hand-written Espresso test. "Start on the screen under test" is separately winnable via
trailheads / shortcuts / deep-links, and on a real trail that is probably the larger term.

**Open question for the benchmark: is the virtual clock actually in play here?** The driver
deliberately takes the app's *existing* `AndroidComposeTestRule` rather than creating one,
so whether Compose's test clock governs the composition depends on whose rule it is and how
the composition was created. The on-device fixture uses `createEmptyComposeRule()`, where
the composition is created by the Activity outside the rule — it is unclear whether the test
clock applies to it. Measure rather than assume: `AndroidTestMetrics` splits `orchestrationMs` /
`nativeExecutionMs` / `loggingMs`, and a tool's own selector resolution — the in-process view walk —
is credited to `orchestrationMs` rather than inflating the native number. Run an animation-heavy
screen with
`mainClock.autoAdvance` on vs off and check whether `nativeExecutionMs` moves. If the clock
is *not* in play, animations still cost real wall-clock time and the driver's ceiling is
lower than a plain Espresso test's. (Even when active, `MainTestClock` does not control
measure/draw passes.)

Related but distinct: the parked "in-process idle detection" experiment only moved a settle
*signal* in-process while
interaction stayed on accessibility over Binder, and showed no measurable win after the
500ms `waitForIdle` floor was removed. This driver moves the *tools* in-process and deletes
the settle floor and tree marshalling entirely — that parked result is not evidence about
this driver in either direction.

## Current boundary

This first slice executes directly inside a ruled instrumentation test. Host-to-device RPC startup
(above), process-destructive reset/reconnect, WebView DOM collection, and system UI delegation are
follow-ups. A tool running in the target process must not force-stop or clear that package: it
would terminate its own instrumentation runner.
