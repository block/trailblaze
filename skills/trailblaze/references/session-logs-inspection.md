# Trailblaze session log inspection

A session log is the **complete forensic record** of one Trailblaze trail run: every LLM
call (request + response + screenshot + view hierarchy), every tool the agent invoked,
every screen state captured, every step's outcome. The same on-disk layout is produced
locally by `./trailblaze` and by any CI system that uploads the `logs/` directory as a
`logs_*.zip` artifact — extract that zip back into `./logs/` and everything below applies.

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
└── 2026_05_08_05_56_08_my_trail_name_4815162342/
    ├── 001_TrailblazeSessionStatusChangeLog.json
    ├── 002_ObjectiveStartLog.json
    ├── 003_TrailblazeLlmRequestLog.json
    ├── 004_AgentDriverLog.json
    ├── 005_TrailblazeToolLog.json
    ├── …
    ├── *.webp                        # device screenshots per step
    ├── *.mp4                         # screen recording (when enabled)
    ├── *.log                         # raw device / process log
    └── recording.trail.yaml          # auto-generated trail YAML (if session completed)
```

Once extracted under `./logs/`, the Trailblaze CLI sees these sessions as if they ran
locally — `./trailblaze session list`, `./trailblaze session info --id <session_id>` and
`./trailblaze session view --id <session_id>` all work against the extracted directory.

## Key invariants (read these before writing scripts)

1. **Files are numerically prefixed but not always in strict event order.** Some
   zips/extractions reorder ties (same-millisecond log entries). **Always sort by the
   `timestamp` field inside each JSON, not by filename.** The numeric prefix is a
   convenience for skim-reading, not a contract.
2. **Every JSON file is a single concrete `TrailblazeLog` instance** — derived from the
   sealed interface at
   `trailblaze-models/src/commonMain/kotlin/xyz/block/trailblaze/logs/client/TrailblazeLog.kt`.
   The `type` field discriminates which concrete shape it is. If new log types appear
   that aren't listed below, that sealed interface is the source of truth.
3. **`session_id` is unique by timestamp.** Multiple log zips (from different shards or
   devices) can be extracted into the same `./logs/` directory without collision — each
   produces its own session subdirectory.
4. **Some recording-replay failures bail before any AgentDriverLog is captured.** When
   `aiEnabled: false`, a session can fail at the very first selector lookup with only
   `MaestroCommandLog.json` + `TrailblazeToolLog.json` in its directory — no view
   hierarchy. This isn't an infra error, just a fast bail. Treat absence of
   AgentDriverLog as "didn't fire," not "no screen state."

## The TrailblazeLog type catalog

Every JSON has at minimum: `session: SessionId`, `timestamp: Instant`, `type` discriminator.

### Session lifecycle
| Log type | What it carries |
|---|---|
| `TrailblazeSessionStatusChangeLog` | Session start/end transitions; final `outcome` (PASSED/FAILED/ERROR/TIMEOUT). |
| `TrailblazeAgentTaskStatusChangeLog` | Per-agent-task status transitions with duration. |

### Step / objective tracking
| Log type | What it carries |
|---|---|
| `ObjectiveStartLog` | Step begins. `promptStep` has the natural-language prompt. |
| `ObjectiveCompleteLog` | Step ends. `objectiveResult` has pass/fail + reason. |
| `SelfHealInvokedLog` | Self-heal activated to recover from a recording mismatch. |

### LLM & reasoning
| Log type | What it carries |
|---|---|
| `TrailblazeLlmRequestLog` | **Most useful for debugging.** Full conversation turn: view hierarchy, system + user instructions, tool options, LLM response, actions taken, screenshot ref, model, cost, token usage. |
| `TrailblazeProgressLog` | Progress + reflection events (subtask progress, exception handling). |

### Tool execution
| Log type | What it carries |
|---|---|
| `TrailblazeToolLog` | One tool invocation: tool name, params, success/failure, duration, exception text. |
| `DelegatingTrailblazeToolLog` | Tool delegation (which tools were available, which was selected). |

### Driver / device actions
| Log type | What it carries |
|---|---|
| `AgentDriverLog` | Low-level driver action with **view hierarchy** + **screenshot** + action details (tap, swipe, input). The class strings inside this log are how driver attribution (instrumentation vs accessibility) is decided. |
| `MaestroCommandLog` | Legacy Maestro driver commands. |
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

session_dir = Path('logs/2026_05_08_…_case_4837769_6258')
completes = sorted(session_dir.glob('*_ObjectiveCompleteLog.json'),
                   key=lambda p: json.loads(p.read_text())['timestamp'])
for c in completes:
    d = json.loads(c.read_text())
    result = (d.get('objectiveResult') or {}).get('status', '?')
    prompt = (d.get('promptStep') or {}).get('promptText', '?')
    print(f"  [{result}] {prompt[:80]}")
```

The first non-PASSED `ObjectiveCompleteLog` is your failing step. Its `objectiveResult`
contains the framework-side failure reason.

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
        # `d` contains: viewHierarchy, instructions, llmResponse, actionsTaken, screenshot, model, usage
        print(f"--- step turn @ {d['timestamp']} ---")
        print(d['instructions'][:500])
        print("LLM response:", d['llmResponse'][:500])
        print("Actions:", d.get('actionsTaken'))
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
the session bailed. Grep for landmark strings:

| Marker substring | Hint |
|---|---|
| `Enter passcode` | App passcode entry screen — launch didn't fully complete sign-in. |
| `Choose an account` | Account picker — login state mismatch. |
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
          f"{'OK' if d.get('success') else 'FAIL'}  {(d.get('exceptionMessage') or '')[:80]}")
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
| Skim the session, look at screenshots | `./trailblaze session view --id <id>` (HTML report) |
| Get a JSON-shaped overview of pass/fail | `./trailblaze session info --id <id>` |
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

- `references/drive-device.md` / `references/save-and-replay.md` — run a trail locally to
  produce a fresh session log for the patterns above.
