## Tool `objectiveStatus`

## Description
Use this tool to indicate the status of the current objective.
First determine if all of the objective's goals have been met, and if they have not return an 'in_progress' status.
If all of the goals have been met successfully, return a 'completed' status.
If you have tried multiple options to complete the objective and are still unsuccessful, then return a 'failed' status.
Returning 'failed' should be a last resort once all options have been tested.

Give an explanation of the current status of this specific objective item (not the overall objective).
This allows the system to track progress through individual items in the objective list.

### Command Class
`xyz.block.trailblaze.toolcalls.commands.ObjectiveStatusTrailblazeTool`

### Registered `ObjectiveStatusTrailblazeTool` in `ToolRegistry`
### Required Parameters
- `description`: `String`
  The text description of the current objective item you're reporting on (copy exactly from the objective list)
- `explanation`: `String`
  A message explaining what was accomplished or the current progress for this specific objective item
- `status`: `String`
  Status of this specific objective item: 'in_progress' (continuing with this same item), 'completed' (move to next item), or 'failed'



<hr/>

**NOTE**: THIS IS GENERATED DOCUMENTATION