package xyz.block.trailblaze.playwright.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.TimeoutError
import com.microsoft.playwright.assertions.LocatorAssertions
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import java.util.UUID
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

@Serializable
@TrailblazeToolClass(
  name = "web_requireTextInViewport",
  surfaceToLlm = false,
  isRecordable = false,
  isVerification = true,
)
@LLMDescription("Requires exact visible text to be scrolled into and intersect the viewport.")
data class PlaywrightNativeRequireTextInViewportTool(
  val text: String,
  val exact: Boolean = true,
  val timeoutMs: Long = 30_000,
) : PlaywrightExecutableTool {
  override suspend fun executeWithPlaywright(
    page: Page,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    if (text.isBlank()) return TrailblazeToolResult.Error.ExceptionThrown("text must not be blank.")
    if (!exact) return TrailblazeToolResult.Error.ExceptionThrown("exact must be true.")
    if (timeoutMs !in 1..MAX_DURATION_MS) {
      return TrailblazeToolResult.Error.ExceptionThrown("timeoutMs must be between 1 and $MAX_DURATION_MS.")
    }
    return try {
      val matches = page.getByText(text, Page.GetByTextOptions().setExact(true))
      val deadline = System.nanoTime() + timeoutMs * NANOS_PER_MILLISECOND
      do {
        currentCoroutineContext().ensureActive()
        for (index in 0 until matches.count()) {
          currentCoroutineContext().ensureActive()
          val remainingMs = (deadline - System.nanoTime()) / NANOS_PER_MILLISECOND.toDouble()
          if (remainingMs <= 0) break
          val candidate = matches.nth(index)
          if (!candidate.isVisible) continue
          try {
            candidate.scrollIntoViewIfNeeded(
              Locator.ScrollIntoViewIfNeededOptions().setTimeout(
                remainingMs.coerceAtMost(POLL_INTERVAL_MS.toDouble()).coerceAtLeast(1.0)
              )
            )
          } catch (_: TimeoutError) {
            currentCoroutineContext().ensureActive()
            continue
          }
          currentCoroutineContext().ensureActive()
          val viewportBudgetMs = (deadline - System.nanoTime()) / NANOS_PER_MILLISECOND.toDouble()
          if (
            viewportBudgetMs > 0 &&
            candidate.intersectsViewport(viewportBudgetMs.coerceAtMost(POLL_INTERVAL_MS.toDouble()))
          ) {
            return TrailblazeToolResult.Success("Required text is visible in the viewport.")
          }
        }
        val remainingMs = (deadline - System.nanoTime()) / NANOS_PER_MILLISECOND.toDouble()
        if (remainingMs > 0) {
          delay(remainingMs.toLong().coerceAtMost(POLL_INTERVAL_MS).coerceAtLeast(1L))
        }
      } while (System.nanoTime() < deadline)
      TrailblazeToolResult.Error.ExceptionThrown("Required text did not become visible in the viewport.")
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      TrailblazeToolResult.Error.ExceptionThrown("Required text could not be verified in the viewport.")
    }
  }
}

@Serializable
@TrailblazeToolClass(name = "web_verifyTextAbsentForDuration", isVerification = true)
@LLMDescription(
  "Verifies exact text remains absent for a bounded duration while a required readiness element stays visible."
)
data class PlaywrightNativeVerifyTextAbsentForDurationTool(
  val text: String,
  val exact: Boolean = true,
  val durationMs: Long,
  @param:LLMDescription(
    "Readiness element ID (for example 'e5'), ARIA descriptor, or css= selector that must remain visible."
  )
  val ref: String? = null,
  val requiredVisibleNodeSelector: TrailblazeNodeSelector? = null,
) : PlaywrightExecutableTool {
  override val targetRef: String? get() = ref
  override val targetNodeSelector: TrailblazeNodeSelector? get() = requiredVisibleNodeSelector
  override fun withNodeSelector(selector: TrailblazeNodeSelector): PlaywrightExecutableTool =
    copy(ref = null, requiredVisibleNodeSelector = selector)

  override suspend fun executeWithPlaywright(
    page: Page,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    if (text.isBlank()) return TrailblazeToolResult.Error.ExceptionThrown("text must not be blank.")
    if (!exact) return TrailblazeToolResult.Error.ExceptionThrown("exact must be true.")
    if (durationMs !in 1..MAX_DURATION_MS) {
      return TrailblazeToolResult.Error.ExceptionThrown("durationMs must be between 1 and $MAX_DURATION_MS.")
    }
    val readinessDescription = PlaywrightExecutableTool.describeTarget(requiredVisibleNodeSelector, ref)
    val (readiness, readinessError) = PlaywrightExecutableTool.validateAndResolveRef(
      page = page,
      ref = ref,
      description = readinessDescription,
      context = context,
      nodeSelector = requiredVisibleNodeSelector,
    )
    if (readinessError != null) return readinessError
    val observationId = UUID.randomUUID().toString()
    try {
      readiness!!.evaluate(
        INSTALL_TEXT_ABSENCE_OBSERVER_SCRIPT,
        mapOf("id" to observationId, "text" to text, "durationMs" to durationMs.toDouble()),
      )
      while (true) {
        currentCoroutineContext().ensureActive()
        when (page.evaluate(TEXT_ABSENCE_OBSERVER_STATUS_SCRIPT, observationId)) {
          "missing" -> {
            return TrailblazeToolResult.Error.ExceptionThrown(
              "The absence observer was lost during verification, likely because the page navigated or reloaded."
            )
          }
          "readiness_lost" -> {
            return TrailblazeToolResult.Error.ExceptionThrown(
              "The required readiness anchor stopped being visible during absence verification."
            )
          }
          "forbidden_visible" -> {
            return TrailblazeToolResult.Error.ExceptionThrown(
              "The forbidden text became visible during absence verification."
            )
          }
          "satisfied" -> {
            return TrailblazeToolResult.Success(
              "Text remained absent while the page stayed ready for ${durationMs}ms."
            )
          }
        }
        delay(POLL_INTERVAL_MS)
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      return TrailblazeToolResult.Error.ExceptionThrown(
        "Sustained text absence could not be verified: ${e.message ?: e::class.simpleName}."
      )
    } finally {
      try {
        page.evaluate(REMOVE_TEXT_ABSENCE_OBSERVER_SCRIPT, observationId)
      } catch (_: Exception) {
        // The page may have navigated or closed; its execution context and observer are already gone.
      }
    }
  }
}

private val INSTALL_TEXT_ABSENCE_OBSERVER_SCRIPT =
  """
  (readiness, args) => {
    window.__trailblazeTextAbsenceRuntime ??= {
      observations: new Map(),
      originalAttachShadow: Element.prototype.attachShadow,
      patchedAttachShadow: null,
    };
    const runtime = window.__trailblazeTextAbsenceRuntime;
    if (!runtime.patchedAttachShadow) {
      runtime.patchedAttachShadow = function(init) {
        const root = runtime.originalAttachShadow.call(this, init);
        if (init?.mode === 'open') {
          for (const observation of runtime.observations.values()) {
            observation.onShadowRoot(root);
          }
        }
        return root;
      };
      Element.prototype.attachShadow = runtime.patchedAttachShadow;
    }
    const normalize = value => (value || '').replace(/\s+/g, ' ').trim();
    const isVisible = element => {
      if (!element || !element.isConnected) return false;
      const style = window.getComputedStyle(element);
      if (style.visibility === 'hidden' || style.display === 'none') return false;
      const rect = element.getBoundingClientRect();
      return rect.width > 0 && rect.height > 0;
    };
    const expected = normalize(args.text);
    let candidates = [];
    const openRoots = [];
    const collectOpenRoots = root => {
      openRoots.push(root);
      for (const element of root.querySelectorAll('*')) {
        if (element.shadowRoot) collectOpenRoots(element.shadowRoot);
      }
    };
    const refreshCandidates = () => {
      openRoots.length = 0;
      collectOpenRoots(document);
      candidates = openRoots.flatMap(root =>
        Array.from(root.querySelectorAll('*')).filter(
          element => normalize(element.innerText) === expected
        )
      );
    };
    const forbiddenTextIsVisible = () => candidates.some(isVisible);
    const state = {
      status: 'observing',
      observer: null,
      frame: 0,
      timer: 0,
      deadline: null,
      finish: null,
    };
    const finish = status => {
      if (state.status !== 'observing') return;
      state.status = status;
      state.observer?.disconnect();
      if (state.frame) cancelAnimationFrame(state.frame);
      if (state.timer) clearTimeout(state.timer);
    };
    state.finish = finish;
    const inspect = () => {
      if (state.deadline !== null && performance.now() >= state.deadline) finish('satisfied');
      else if (!isVisible(readiness)) finish('readiness_lost');
      else if (forbiddenTextIsVisible()) finish('forbidden_visible');
    };
    const inspectMutation = () => {
      refreshCandidates();
      observeOpenRoots();
      inspect();
    };
    const inspectFrame = () => {
      inspect();
      if (state.status === 'observing') state.frame = requestAnimationFrame(inspectFrame);
    };
    refreshCandidates();
    state.observer = new MutationObserver(inspectMutation);
    const observedRoots = new WeakSet();
    const observeOpenRoots = () => {
      for (const root of openRoots) {
        if (observedRoots.has(root)) continue;
        state.observer.observe(root, {
          attributes: true,
          childList: true,
          characterData: true,
          subtree: true,
        });
        observedRoots.add(root);
      }
    };
    observeOpenRoots();
    state.onShadowRoot = root => {
      openRoots.length = 0;
      collectOpenRoots(root);
      observeOpenRoots();
      refreshCandidates();
      inspect();
    };
    runtime.observations.set(args.id, state);
    inspect();
    if (state.status === 'observing') {
      state.deadline = performance.now() + args.durationMs;
      state.frame = requestAnimationFrame(inspectFrame);
      state.timer = setTimeout(() => finish('satisfied'), args.durationMs);
    }
  }
  """.trimIndent()

private val TEXT_ABSENCE_OBSERVER_STATUS_SCRIPT =
  """
  id => {
    const state = window.__trailblazeTextAbsenceRuntime?.observations.get(id);
    if (!state) return 'missing';
    if (
      state.status === 'observing' &&
      state.deadline !== null &&
      performance.now() >= state.deadline
    ) {
      state.finish('satisfied');
    }
    return state.status;
  }
  """.trimIndent()

private val REMOVE_TEXT_ABSENCE_OBSERVER_SCRIPT =
  """
  id => {
    const runtime = window.__trailblazeTextAbsenceRuntime;
    const state = runtime?.observations.get(id);
    state?.observer?.disconnect();
    if (state?.frame) cancelAnimationFrame(state.frame);
    if (state?.timer) clearTimeout(state.timer);
    runtime?.observations.delete(id);
    if (runtime?.observations.size === 0 && Element.prototype.attachShadow === runtime.patchedAttachShadow) {
      Element.prototype.attachShadow = runtime.originalAttachShadow;
      delete window.__trailblazeTextAbsenceRuntime;
    }
  }
  """.trimIndent()

private fun Locator.intersectsViewport(timeoutMs: Double): Boolean =
  try {
    assertThat(this).isInViewport(
      LocatorAssertions.IsInViewportOptions().setTimeout(timeoutMs.coerceAtLeast(1.0))
    )
    true
  } catch (_: AssertionError) {
    false
  }

private const val MAX_DURATION_MS = 30_000L
private const val POLL_INTERVAL_MS = 100L
private const val NANOS_PER_MILLISECOND = 1_000_000L
