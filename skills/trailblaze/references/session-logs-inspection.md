# Trailblaze session log inspection

A session log is the **complete forensic record** of one Trailblaze trail run: every LLM
call (request + response + screenshot + view hierarchy), every tool the agent invoked,
every screen state captured, every step's outcome. The same on-disk layout is produced
locally by `trailblaze` and by any CI system that uploads the `logs/` directory as a
`logs_*.zip` artifact - extract that zip back into the configured logs directory
(`<git root>/logs` in a checkout, `~/.trailblaze/logs` for an installed CLI) and everything
below applies.

This reference is the **inspection layer** — it tells you what's in the files, how to read
them, and the common debugging patterns. It works the same whether the session ran on your
laptop or whether the `logs/` directory was extracted from a CI artifact.

## When to use

- "Why did session `<session-id>` fail?"
- "Show me what the agent saw when step N failed"
- "What tools did the agent try before giving up?"
- "What was the view hierarchy at the moment of failure?"
- "Pull the LLM transcript around the failing step"
- Anytime you have a `logs/<session_id>/` directory and need to make sense of what's inside.

## Layout

After extraction, every session is its own directory:

```
logs/
└── my_trail_name_1a2b3c4d/           # session id: <trail>_<8 hex>
    ├── 001_TrailblazeSessionStatusChangeLog.json
    ├── 002_ObjectiveStartLog.json
    ├── 003_TrailblazeLlmRequestLog.json
    ├── 004_AgentDriverLog.json
    ├── 005_TrailblazeToolLog.json
    ├── …
    ├── *.webp                        # device screenshots per step
    ├── video.webm                    # screen recording (video.mp4 without a VP9 encoder)
    ├── device.log                    # Android logcat, iOS log, or web console
    ├── network.ndjson                # captured network traffic (when capture is on)
    ├── events/*.ndjson               # event streams (per-browser network, app plugins)
    ├── trace.json                    # host and device trace spans
    ├── capture_metadata.json         # which capture artifacts the session holds
    └── recording.trail.yaml          # auto-generated trail YAML (if session completed)
```

JUnit and on-device runs use timestamped ids instead, such as
`2026_05_08_05_56_08_my_trail_name_4815`.

Once extracted into the logs directory, the Trailblaze CLI sees these sessions as if they ran
locally - `trailblaze session list`, `trailblaze session info --id <session_id>` and
`trailblaze report --id <session_id>` all work against the extracted directory.

## Key invariants (read these before writing scripts)

1. **Files are numerically prefixed but not always in strict event order.** Some
   zips/extractions reorder ties (same-millisecond log entries). **Always sort by the
   `timestamp` field inside each JSON, not by filename.** The numeric prefix is a
   convenience for skim-reading, not a contract.
2. **Every numbered event-log file (`<n>_<LogType>.json`) is a single concrete `TrailblazeLog`
   instance** — derived from the
   sealed interface at
   `trailblaze-models/src/commonMain/kotlin/xyz/block/trailblaze/logs/client/TrailblazeLog.kt`.
   The `class` field (fully qualified, e.g.
   `xyz.block.trailblaze.logs.client.TrailblazeLog.ObjectiveStartLog`) discriminates which
   concrete shape it is. If new log types appear that aren't listed below, that sealed
   interface is the source of truth. The unnumbered JSON beside them (`capture_metadata.json`,
   `trace.json`) has its own shape, so glob `[0-9]*_*.json` rather than `*.json`.
3. **`session_id` is unique per invocation.** A CLI or daemon run pins it to the sanitized
   trail name plus 8 random hex characters (`SessionId.pinnedFor`), so multiple log zips
   (from different shards or devices) can be extracted into the same `./logs/` directory
   without collision — each produces its own session subdirectory.
4. **Some recording-replay failures bail before any AgentDriverLog is captured.** When
   `aiEnabled: false`, a session can fail at the very first selector lookup with only
   `MaestroCommandLog.json` + `TrailblazeToolLog.json` in its directory — no view
   hierarchy. This isn't an infra error, just a fast bail. Treat absence of
   AgentDriverLog as "didn't fire," not "no screen state."

## The TrailblazeLog type catalog

Every numbered event-log file has at minimum: `session: SessionId`, `timestamp: Instant`, `class` discriminator.

### Session lifecycle
| Log type | What it carries |
|---|---|
| `TrailblazeSessionStatusChangeLog` | Session start/end transitions in `sessionStatus`. The final one has `class` `...SessionStatus.Ended.<Succeeded\|Failed\|Cancelled\|SucceededWithSelfHeal\|FailedWithSelfHeal\|TimeoutReached\|MaxCallsLimitReached>`. |
| `TrailblazeAgentTaskStatusChangeLog` | Per-agent-task status transitions with duration. |

### Step / objective tracking
| Log type | What it carries |
|---|---|
| `ObjectiveStartLog` | Step begins. `promptStep` has the natural-language prompt (`step` or `verify`). |
| `ObjectiveCompleteLog` | Step ends. The `class` of `objectiveResult` is the outcome (`ObjectiveComplete`, `ObjectiveFailed`, `MaxCallsLimitReached`). |
| `SelfHealInvokedLog` | Self-heal activated to recover from a recording mismatch. |

### LLM & reasoning
| Log type | What it carries |
|---|---|
| `TrailblazeLlmRequestLog` | **Most useful for debugging.** Full conversation turn: view hierarchy, system + user instructions, tool options, LLM response, actions taken, screenshot ref, model, cost, token usage. |
| `TrailblazeToolCatalogLog` | The tool descriptors offered to the LLM, written once per `toolCatalogId`. Requests point at it through their own `toolCatalogId`, so read it as a dictionary. |
| `TrailblazeDecisionRequestLog` | One decision-engine request: typed questions answered with probabilities (`request`, `response`, `outcome`), linked to the surrounding step by `traceId`. |
| `TrailblazeProgressLog` | Progress + reflection events (subtask progress, exception handling). |

### Tool execution
| Log type | What it carries |
|---|---|
| `TrailblazeToolLog` | One tool invocation: tool name, params, success/failure, duration, exception text. |
| `DelegatingTrailblazeToolLog` | Tool delegation (which tools were available, which was selected). |

### Driver / device actions
| Log type | What it carries |
|---|---|
| `AgentDriverLog` | Low-level driver action with **view hierarchy** + **screenshot** + action details (tap, swipe, input). Its `class` value ends in `MaestroDriverLog`, a legacy serial name. The class strings inside this log are how driver attribution (instrumentation vs accessibility) is decided. |
| `MaestroCommandLog` | Legacy Maestro driver commands. |
| `AccessibilityActionLog` | Deprecated accessibility action record; new logs use `AgentDriverLog`. |
| `TrailblazeSnapshotLog` | Standalone screen snapshot with view hierarchy + screenshot. |

### MCP agent (multi-agent mode)
| Log type | What it carries |
|---|---|
| `McpToolCallRequestLog` / `McpToolCallResponseLog` | Incoming/outgoing MCP tool calls. |
| `McpAgentRunLog` | Full MCP agent run lifecycle. |
| `McpAgentIterationLog` | Individual LLM iteration within an MCP agent run. |
| `McpSamplingLog` | LLM sampling requests during MCP execution. |
| `McpAgentToolLog` | Tool execution during MCP agent runs. |
| `McpAskLog` | Screen question/answer for situational awareness. |

## Debugging patterns

### Find which step failed in a session

```python
from pathlib import Path
import json

session_dir = Path('logs/2026_05_08_…_case_1008_6258')
completes = sorted(session_dir.glob('*_ObjectiveCompleteLog.json'),
                   key=lambda p: json.loads(p.read_text())['timestamp'])
for c in completes:
    d = json.loads(c.read_text())
    result = (d.get('objectiveResult') or {}).get('class', '?').rsplit('.', 1)[-1]
    step = d.get('promptStep') or {}
    prompt = step.get('step') or step.get('verify') or '?'
    print(f"  [{result}] {prompt[:80]}")
```

Only the **last** `ObjectiveCompleteLog`, ignoring setup and teardown steps, can be the step that
failed the run, and only when its result is `ObjectiveFailed` or `MaxCallsLimitReached`. An earlier
failure may be a recording attempt that self-heal then recovered, so a failed line followed by a
passing retry of the same prompt is not the failure. A session can also fail after its last step
passed; confirm against the final `TrailblazeSessionStatusChangeLog` (`Ended.Failed`,
`FailedWithSelfHeal`, ...). For `ObjectiveFailed`, `objectiveResult.llmExplanation` holds the
framework-side failure reason.

### Read the LLM transcript around the failing step

```python
# Once you know which ObjectiveStartLog opens the failed step, read every LlmRequestLog
# whose timestamp falls between that start and the matching ObjectiveCompleteLog.
start_ts = … # from the failed step's ObjectiveStartLog
end_ts   = … # from the failed step's ObjectiveCompleteLog

llms = sorted(session_dir.glob('*_TrailblazeLlmRequestLog.json'),
              key=lambda p: json.loads(p.read_text())['timestamp'])
for l in llms:
    d = json.loads(l.read_text())
    if start_ts <= d['timestamp'] <= end_ts:
        # `d` contains: viewHierarchy, instructions, llmMessages, llmResponse (a list), actions,
        # screenshotFile, trailblazeLlmModel, llmRequestUsageAndCost
        print(f"--- step turn @ {d['timestamp']} ---")
        print(d['instructions'][:500])
        print("LLM response:", json.dumps(d['llmResponse'])[:500])
        print("Actions:", d.get('actions'))
```

### Driver attribution (which driver actually ran?)

The runtime field `SessionStarted.trailblazeDriverType` is **circular** — our code writes
it. To audit what actually ran, count selector class strings inside every
`*_AgentDriverLog.json` for the session:

```python
import re
RE_MAESTRO = re.compile(r'"class"\s*:\s*"androidMaestro"')
RE_ACCESS  = re.compile(r'"class"\s*:\s*"androidAccessibility"')

m = a = 0
for f in session_dir.glob('*AgentDriverLog.json'):
    txt = f.read_text(errors='ignore')
    m += len(RE_MAESTRO.findall(txt))
    a += len(RE_ACCESS.findall(txt))

if a > 0 and m == 0:   driver = 'ACCESSIBILITY'
elif m > 0 and a == 0: driver = 'INSTRUMENTATION'
elif m == 0 and a == 0: driver = 'NO_HIERARCHY'   # session bailed before any driver log
else:                   driver = 'MIXED'           # unusual, worth a closer look
```

> **Tolerant whitespace.** The class field may render as `"class": "androidMaestro"`
> (pretty-printed) or `"class":"androidMaestro"` (compact). Using a literal
> `.count('"class":"androidMaestro"')` silently misses pretty-printed sessions.

### Screen state at failure (heuristic)

The chronologically last `*_AgentDriverLog.json` is usually the screen state at the moment
the session bailed. Grep for landmark strings (swap in your own app's):

| Marker substring | Hint |
|---|---|
| `Enter your PIN` | PIN or passcode entry screen - launch didn't fully complete sign-in. |
| `Select an account` | Account picker - login state mismatch. |
| `"text":"Sign In"` / `"text":"Log in"` | Unauth login screen. |
| `permission` + `Allow` | Runtime permission dialog blocking. |

Absence of a hint means "didn't fire," not "no screen state" — see invariant #4.

### Tool-call sequence inside the failing step

```python
tools = sorted(session_dir.glob('*_TrailblazeToolLog.json'),
               key=lambda p: json.loads(p.read_text())['timestamp'])
for t in tools:
    d = json.loads(t.read_text())
    print(f"  {d['timestamp']}  {d.get('toolName','?'):28} "
          f"{'OK' if d.get('successful') else 'FAIL'}  {(d.get('exceptionMessage') or '')[:80]}")
```

This makes it obvious whether the agent tried 7 alternative selectors before giving up vs.
bailed at the first miss.

### `failure_reason` shapes you'll commonly see

The `failure_reason` in the test report JSON (`trailblaze_test_report_*.json` from CI)
roughly mirrors what you'll find on the failing `ObjectiveCompleteLog`. Common forms:

```
xyz.block.trailblaze.exception.TrailblazeException: Failed to run recording for prompt step:
  prompt: <step text>
  recorded tools: <comma list>
  failed tool: <tool name>
  failure: <error msg, often a Maestro JSON command>
```

| Shape | Meaning |
|---|---|
| `Failed to run recording for prompt step:` | Recording-replay couldn't reproduce — most actionable. |
| `Session was abandoned after N minutes of inactivity` | Infra/flake — agent stalled, no progress. |
| `Max LLM calls limit … reached for objective: <step>` | Agent ran out of corrective calls (recording-correct mode). |
| `Failed to successfully run prompt with AI` | AI-driven path failed (rare on `aiEnabled:false` runs). |
| `RuntimeException: Error while disconnecting UiAutomation` | Host-driver infra error. |
| `Element not found for selector: "X"` | Inside a recording-replay — points at the missing selector. |

## When to use the CLI vs. raw JSON

| Need | Best path |
|---|---|
| Skim the session, look at screenshots | `trailblaze report --id <id>` (HTML report; `trailblaze viewer` writes the standalone viewer page) |
| Get a JSON-shaped overview of pass/fail | `trailblaze session info --id <id>` |
| Find the failing step | Sort `ObjectiveCompleteLog.json` by timestamp (Python snippet above) |
| Read LLM reasoning at a specific step | Open the matching `TrailblazeLlmRequestLog.json` |
| Look at the screen at failure | Open the latest `*.webp` in the session dir |
| Driver attribution audit | Count selector class strings in `AgentDriverLog.json` |

The CLI hides the JSON layout for you, which is great for interactive exploration. For
programmatic analysis (clustering across many sessions, content-based driver audits, etc.)
go straight to the JSON.

## Canonical source

The sealed interface that defines every log type:

```
trailblaze-models/src/commonMain/kotlin/xyz/block/trailblaze/logs/client/TrailblazeLog.kt
```

When a new log type lands or fields change, that file is the source of truth — update this
reference accordingly.

## Related references

- [`drive-device.md`](drive-device.md) / [`save-and-replay.md`](save-and-replay.md) - run a trail
  locally to produce a fresh session log for the patterns above.
