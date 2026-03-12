## Tool `objectiveStatus`

## Description
Use this tool to report the status of the current objective.
First determine if all of the objective's goals have been met, and if they have not return an 'in_progress' status.
If all of the goals have been met successfully, return a 'completed' status.
If you have tried multiple options to complete the objective and are still unsuccessful, then return a 'failed' status.
Returning 'failed' should be a last resort once all options have been tested.

### Command Class
`xyz.block.trailblaze.toolcalls.commands.ObjectiveStatusTrailblazeTool`

### Registered `ObjectiveStatusTrailblazeTool` in `ToolRegistry`
### Required Parameters
- `explanation`: `String`
  A message explaining what was accomplished or the current progress for this objective
- `status`: `[
  "IN_PROGRESS",
  "COMPLETED",
  "FAILED"
]`



<hr/>

**NOTE**: THIS IS GENERATED DOCUMENTATION