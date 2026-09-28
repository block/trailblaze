package xyz.block.trailblaze.android.accessibility

/**
 * The last tree the capture-time settle gate proved stable, and how many UI-mutation events the
 * service had seen before the sample that proved it.
 *
 * The gate needs an 80 ms window of unchanged samples to call a screen stable, and it paid that on
 * every capture — including one straight after a capture that had just proved the same screen
 * stable, with nothing happening in between. That is the common case for an agent driving the CLI:
 * it reads the screen, thinks, and acts on a screen that has not moved.
 *
 * A tree may skip the quiet window when BOTH hold:
 *  - its signature equals the proven one, so it is the same tree, and
 *  - no UI-mutation event arrived since the proof, so nothing started moving since.
 *
 * An animation that has started changes the signature, and anything that changes the tree
 * without moving it (text, content) raises an event. Either way the gate runs in full. An event
 * can lag the change it reports, so the memo is also dropped whenever the driver dispatches input:
 * the capture right after an action always runs the full gate. It is NOT dropped when a tool call
 * starts, because the saving is exactly that call's pre-action capture on a screen the previous
 * call already proved. Input the driver doesn't dispatch itself (a scripted tool's own shell
 * command) is covered only by the signature and events.
 */
internal data class StableTreeMemo<T>(
  val signature: Long,
  val uiEventCountBeforeSample: Long,
  val verdict: T,
)

/**
 * The remembered verdict when [signature] is the proven tree and no UI event has arrived since.
 * The event count is read before and after the tree is sampled: an event that lands during the
 * sample may be for a change the walk missed, so it refuses reuse too.
 */
internal fun <T> StableTreeMemo<T>?.reusableFor(
  signature: Long,
  uiEventCountBeforeSample: Long,
  uiEventCountAfterSample: Long,
): T? = this?.takeIf {
  it.signature == signature &&
    it.uiEventCountBeforeSample == uiEventCountBeforeSample &&
    uiEventCountAfterSample == uiEventCountBeforeSample
}?.verdict
