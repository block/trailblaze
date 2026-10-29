// Conditional for recorded trails. A recording replays a flat tool list and can't branch on runtime
// state, so "dismiss the popup if it's there" or "turn the toggle on if it's off" otherwise becomes
// an unconditional tap that is wrong whenever the screen differs from the recording run. `runIf`
// records VERBATIM (the wrapper, not the inner tools it ran), so every replay re-evaluates the
// condition. The inner tool-calls it dispatches are logged as nested calls and are not recorded.
//
// `surfaceToLlm: false`: this is a composition primitive for recorded trails and scripted tools,
// not an agent verb. The agent already branches on what it observes; a recording is what needs an
// explicit conditional. It stays recordable and dispatchable by name, just not advertised to the LLM.
// It ships in the always-enabled `meta` toolset with no `supportedPlatforms`, so every target
// registers it on Android, iOS and web sessions that launch scripted tools (the CLI, the daemon and
// the on-device runner). Compose and Revyl sessions launch no scripted tools, so it is unavailable
// there. A `{ selector }` condition also needs a view tree, which screenshot-only drivers lack.
//
// CONDITION forms:
//   - { selector } → true iff `findSelectorMatches` returns at least one match for it.
//   - { tool: { <name>: <args> } } → true when the tool passes, false when it fails, so any assertion
//     or probe tool can be the predicate without its failure aborting the trail. Only pass/fail
//     counts: a tool that answers "no" in its return value without failing is still true. A
//     condition that cannot run at all (an unknown tool, arguments it rejects, a tool cycle) fails
//     the step instead of reading as false, so a typo can't quietly take `else` forever.
// `negate` flips the resolved condition. The chosen branch's tool-calls run in order.
//
// The whole tree is checked before anything runs, both branches included, so a malformed branch
// fails on the first replay rather than the day the screen first takes it. Nesting stops at
// MAX_NESTING levels: past that the logic belongs in a `.ts` tool, which can be named and tested.
//
// A `then`/`else` entry (or a `{ tool }` condition) that is itself a conditional recurses as a
// local call, NOT through `ctx.tools`: re-dispatching `runIf` while its own call is still running
// re-enters the same QuickJS engine, which the framework refuses. The nested branch's own tool-calls
// still dispatch through `ctx.tools` and are logged as usual.
//
// Dispatch by recorded name: `ctx.tools` is typed to the known tool names, but a recorded tool-call
// carries an arbitrary string key. The runtime Proxy accepts any name, so we cast through
// `Record<string, ...>`. Dispatch resolves against the session's full tool repo (framework plus
// target tools), so a branch can call whatever tools the current target delivers.

import { trailblaze } from "@trailblaze/scripting";
import { runIfHandler, type RunIfArgs } from "../lib/runIf";
export type { ToolCall, RunIfCondition, RunIfArgs } from "../lib/runIf";

/**
 * Runs `then` when the condition holds and `else` otherwise, re-evaluating on every replay. Use it
 * when an action only makes sense in one screen state (e.g. dismiss a popup only if it is showing).
 * `condition` is `{ selector: <TrailblazeNodeSelector> }` (true when it matches a node) or
 * `{ tool: { <toolName>: <args> } }` (true when that call succeeds, false when it fails). `then`/`else`
 * entries use the `recording.tools` shape `{ <toolName>: <args> }` and may nest `runIf` two deep.
 */
export const runIf = trailblaze.tool<RunIfArgs>(
  { surfaceToLlm: false },
  runIfHandler,
);
