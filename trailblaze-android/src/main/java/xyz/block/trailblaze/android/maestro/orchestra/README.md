`Orchestra.kt` in this package is a cut-down copy of Maestro's `Orchestra`, kept for exactly one
reason: selector matching reuses Maestro's own matching logic rather than reimplementing it.

Two private functions survive — `buildFilter` and `findElementViewHierarchy`. Everything that
executed commands is gone; it existed to run Maestro flows on the device, and the driver that did
that has been retired.

Derived from Maestro v2.6.1:
<https://github.com/mobile-dev-inc/Maestro/blob/cli-2.6.1/maestro-orchestra/src/main/java/maestro/orchestra/Orchestra.kt>

## The reflective coupling — read this before you touch anything here

Nothing calls this class. `ElementMatcherUsingMaestro` (in `trailblaze-common`) reaches it through
kotlin-reflect, **by string**:

- the fully-qualified class name `xyz.block.trailblaze.android.maestro.orchestra.Orchestra`,
- `buildFilter` by name, with 2 parameters (receiver + selector), invoked non-suspending,
- `findElementViewHierarchy` by name, with 3 parameters (receiver + selector + timeout), invoked
  through `callSuspend`,
- the primary constructor, called by named parameter `maestro`.

None of that is visible to the compiler. Rename any of it, change an arity, change one function's
`suspend`-ness, or add a constructor parameter without a default, and the build stays green while
every recorded selector stops resolving.

The matcher only picks this fork up when it runs on-device; on the host it falls back to Maestro's
own `Orchestra`. So the breakage only surfaces on a device. `OrchestraReflectiveContractTest`
(`src/test`) exists to catch it on the JVM first — it drives the real matcher against a static
hierarchy with this fork on the classpath. Keep it passing.

## Syncing with a newer Maestro

Diff upstream's `Orchestra.kt` between the two tags and apply only the parts that land inside the two
surviving functions or the `REGEX_OPTIONS` constant; ignore everything else. Then:

1. Update the version/date line above and the KDoc in `Orchestra.kt`.
2. `./gradlew spotlessApply` (upstream is 4-space, we use 2-space).
3. Run `./gradlew :trailblaze-android:testDebugUnitTest` for the reflective contract, and
   `./gradlew :trailblaze-common:jvmTest` for the matcher/selector suites. **Compilation passing is
   not the signal** — `Filters` semantics shift silently between Maestro versions. 2.6.1, for
   example, changed `containsChild` to match *every* parent with a matching direct child, where
   2.3.0 collapsed to the first; that changed the selectors Trailblaze generates, and nothing failed
   to compile.

**The bump breaks the HOST, not this fork.** Upstream's `Orchestra` in 2.10.0 no longer has
`findElementViewHierarchy` — it exposes `findElement(ElementSelector, Boolean, Long)` instead —
while `buildFilter(ElementSelector)` survives. This fork keeps the 2.6.1 shape, so on-device
matching is unaffected; the host fallback is not, because it reflects against upstream's class and
`ElementMatcherUsingMaestro` throws from its **object initializer** when the method is missing, so
every selector match on the host would die with `ExceptionInInitializerError`. The bump must
therefore also adapt `ElementMatcherUsingMaestro` to the new method (or stop depending on
`findElementViewHierarchy`); `TapSelectorV2Test` and `SelectorStrategyTest` in `trailblaze-common`
are the detectors.

`REGEX_OPTIONS` is mirrored by `TrailblazeNodeSelectorResolver.MAESTRO_REGEX_OPTIONS`
(`trailblaze-models`) and `PropertyUniqueness` (`trailblaze-common`) — keep the three in sync.
