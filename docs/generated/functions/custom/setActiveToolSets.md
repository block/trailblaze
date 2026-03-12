## Tool `setActiveToolSets`

## Description
Enable additional tool sets for device interaction. By default, only core tools (tap, input text) are available.
Use this to enable more tools when needed. You can enable multiple tool sets at once.
Call with an empty list to reset to only the core tools.
The available toolset IDs are listed in your system prompt.

### Command Class
`xyz.block.trailblaze.toolcalls.commands.SetActiveToolSetsTrailblazeTool`

### Registered `SetActiveToolSetsTrailblazeTool` in `ToolRegistry`
### Required Parameters
- `toolSetIds`: `{
  "itemsType": {
    "name": "STRING"
  },
  "name": "ARRAY"
}`



<hr/>

**NOTE**: THIS IS GENERATED DOCUMENTATION