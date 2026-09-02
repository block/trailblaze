package xyz.block.trailblaze.host

import xyz.block.trailblaze.toolcalls.commands.SwitchDeviceTrailblazeTool
import xyz.block.trailblaze.yaml.TrailYamlItem

/**
 * What stops a multi-device session before it opens.
 *
 * Multi-device runs used to be mechanical-replay-only: any AI-driven step, and running with
 * recorded steps disabled at all, was rejected here. That restriction existed because the
 * LLM-facing halves were missing — the model was never told which devices the session held and
 * `switchDevice` was never advertised to it — so an AI step would have driven whichever device
 * happened to be active with no way to reach the others, and no way to know they existed.
 *
 * Both halves are now wired, so an AI-driven step is a supported shape rather than a silent
 * misfire, and the blanket rejections are gone. What remains is one rule that writes to an
 * author's trail source on a path no run has exercised, one that keeps the old rejection alive
 * for the sessions where the second half is missing after all, and one that catches a mistake in
 * the trail itself.
 *
 * Pure so the session-start rule set is testable without a device pair: the caller throws, this
 * decides.
 */
internal object MultiDeviceSessionPreflight {

  /**
   * Why this session must not open, or null to proceed.
   *
   * At most one reason, most session-wide first: a caller fixing a config-level problem changes
   * one flag, whereas the handover findings are a list they work through, and reporting both at
   * once would bury the flag.
   *
   * @param handoverToolAdvertised whether the composed session repo actually offers `switchDevice`
   * to the model — the same read the prompt's handover contract keys on, so the two can't disagree.
   */
  fun rejectionReason(
    configurationName: String,
    selfHealEnabled: Boolean,
    handoverToolAdvertised: Boolean,
    useRecordedSteps: Boolean,
    trailItems: List<TrailYamlItem>,
    boundNames: Set<String>,
  ): String? {
    // Self-heal is rejected because its save-back is BROKEN, not merely unexercised — see #6372,
    // which reproduces on a single device.
    //
    // A FRESH leg from an AI step is safe on a pair, and the recording writer does not have to be
    // device-aware for that to hold: `switchDevice` is itself a recorded tool, every host-local
    // dispatch emits a `TrailblazeToolLog` (`BaseTrailblazeAgent.dispatchTools`), and the generator
    // keeps a window's tool logs in dispatch order with no tool-type filter. The device change
    // therefore lands INSIDE the leg it belongs to, which is exactly what replay reads.
    //
    // A HEAL is different in kind, not degree. It does not rewrite the failed leg in place — it
    // opens a SECOND objective window for the same prompt, and for an ordinary prompt step the
    // generator appends one recorded step per window rather than folding them (only trailheads fold,
    // via `upsertTrailheadItem`). The merge then aligns recorded steps to existing steps by INDEX,
    // so a heal shifts the step→leg alignment of everything after it: the next step inherits the
    // healed step's recovery leg, a duplicate step is appended, and when the replay failed on its
    // first tool the healed step's recording is erased outright.
    //
    // The save-back MECHANISM is fine — `TrailCommand.recordingSlotKey` keys a session's legs by
    // the selected configuration name, and a configuration session routes to a unified merge. The
    // damage is upstream of the device axis entirely, which is why #6372 reproduces on one device.
    // Lifting this branch waits on that fix, not on a live multi-device heal.
    if (selfHealEnabled) {
      return "Multi-device sessions (configuration `$configurationName`) can't run with self-heal " +
        "enabled: healing writes a recovered leg back into your trail source and misaligns the " +
        "steps around it, which on a device pair leaves legs replaying on the wrong display. " +
        "Disable self-heal, or run on a single device."
    }

    // The relaxation above is conditional on the second half actually being there, and for one
    // session shape it isn't: a target's `excluded_tools:` drops `switchDevice` from the composed
    // repo, and then an AI step is back to the pre-relaxation hazard — a brain that can see the
    // roster but can only drive whichever device is active.
    //
    // Exclusion is not consent to that. `excluded_tools:` is declared on a TARGET, by an author
    // who cannot know their app's trails will be cast into a pair — the same asymmetry that stops
    // any target from declaring the `multi_device` toolset in the first place. So this reinstates
    // the old rejection, narrowed to the sessions that still need it.
    //
    // Fully recorded replay is unaffected: a recorded handover dispatches through the runner-util,
    // never through the advertised surface, so it never needed the tool advertised.
    if (!handoverToolAdvertised) {
      val aiDrivenSteps = aiDrivenSteps(trailItems, useRecordedSteps)
      if (aiDrivenSteps.isNotEmpty()) {
        return "Multi-device session (configuration `$configurationName`) can't run AI-driven " +
          "steps: `${SwitchDeviceTrailblazeTool.ADVERTISED_TOOL_NAME}` isn't advertised to the " +
          "model in this session, so a step without a recording could only drive whichever device " +
          "is active. These steps have no recording to replay: " +
          aiDrivenSteps.joinToString("; ") { "\"$it\"" } + ". Stop excluding " +
          "`${SwitchDeviceTrailblazeTool.ADVERTISED_TOOL_NAME}` from the target's `excluded_tools:`, " +
          "record each step under the `$configurationName:` key, or run on a single device."
      }
    }

    // Resolve every recorded handover against the session's bindings before the first step runs.
    // Only literal names are judged — a `{{…}}`/`${…}` name resolves from memory at the dispatch
    // boundary, after the session seeds it, so the guard defers rather than rejecting it here.
    //
    // This one is permanent. It is not a limitation on what a session can do; a recorded leg
    // naming a device the configuration never declared is a mistake in the trail, and catching it
    // at session start beats failing on that step after every earlier step ran on a real pair.
    //
    // Scoped to the legs THIS run will dispatch, which is what that rationale rests on: a leg the
    // run re-blazes past cannot fail on the name. Refusing anyway would block the one command that
    // repairs a stale leg — re-blazing over it — while `trailblaze check` still fails the trail
    // statically, so the mistake is caught either way.
    val unboundSwitchTargets = MultiDeviceHandoverGuard.unboundTargets(
      trailItems = trailItems,
      boundNames = boundNames,
      inspectStepRecording = { useRecordedSteps && it.recordable },
    )
    if (unboundSwitchTargets.isNotEmpty()) {
      return "Multi-device session (configuration `$configurationName`) hands off to devices this " +
        "session didn't bind: " +
        unboundSwitchTargets.joinToString("; ") { (where, target) -> "$where → '$target'" } +
        ". Bound devices: ${boundNames.joinToString()}. Use a name the configuration declares, " +
        "or re-record the step on the pair."
    }

    return null
  }

  /**
   * Every step that will reach the brain rather than replay, described the way its author wrote it.
   *
   * `--no-use-recorded-steps` sends even a fully recorded prompt step to the brain, and
   * `recordable: false` does the same for one step, which is why a step's recording alone doesn't
   * answer this — it mirrors the runtime's own replay predicate
   * (`TrailblazeRunnerUtil.canPromptStepUseRecording`). A trailhead is the exception, and the
   * branch below says why.
   */
  private fun aiDrivenSteps(
    trailItems: List<TrailYamlItem>,
    useRecordedSteps: Boolean,
  ): List<String> = trailItems.flatMap { item ->
    when (item) {
      // `tools == null` means "blaze via AI"; an explicit empty list is a deterministic no-op.
      //
      // `useRecordedSteps` deliberately doesn't enter into it. A trailhead's job is to reach one
      // fixed starting state, so the runner dispatches it with `useRecordedSteps = true` no matter
      // what the request asked for — `--no-use-recorded-steps` re-blazes the trail's steps, not its
      // way in. Reading the request flag here would reject a recorded trailhead that never reaches
      // the model.
      is TrailYamlItem.TrailheadTrailItem ->
        if (item.trailhead.tools == null) {
          listOf("trailhead: ${item.trailhead.step}")
        } else {
          emptyList()
        }

      is TrailYamlItem.PromptsTrailItem ->
        item.promptSteps
          .filterNot { useRecordedSteps && it.recordable && it.recording != null }
          .map { it.prompt }

      // A bare `tools:` item dispatches literal tool calls and never reaches the brain.
      // Enumerated rather than `else` for the same reason the handover guard enumerates.
      is TrailYamlItem.ToolTrailItem, is TrailYamlItem.ConfigTrailItem -> emptyList()
    }
  }
}
