## Tool `tapOnElementByNodeId`

## Description
Provide the nodeId of the element you want to tap on in the nodeId parameter.

### Command Class
`xyz.block.trailblaze.toolcalls.commands.TapOnElementByNodeIdTrailblazeTool`

### Registered `TapOnElementByNodeIdTrailblazeTool` in `ToolRegistry`
### Required Parameters
- `nodeId`: `Integer`
  The nodeId of the element in the view hierarchy that will be tapped on. Do NOT use the nodeId 0.

### Optional Parameters
- `reason`: `String`
  Reasoning on why this element was chosen. Do NOT restate the nodeId.
- `longPress`: `Boolean`
  A standard tap is default, but return 'true' to perform a long press instead.



<hr/>

**NOTE**: THIS IS GENERATED DOCUMENTATION