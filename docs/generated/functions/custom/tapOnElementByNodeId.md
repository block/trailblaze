## Tool `tapOnElementByNodeId`

## Description
Tap/click on a specific view target by it's `nodeId`.

### Command Class
`xyz.block.trailblaze.toolcalls.commands.TapOnElementByNodeIdTrailblazeTool`

### Registered `TapOnElementByNodeIdTrailblazeTool` in `ToolRegistry`
### Required Parameters
- `reason`: `String`
  Reasoning on why this view was chosen. Do NOT restate the nodeId.
- `nodeId`: `Integer`
  The `nodeId` of the tap target in the view hierarchy.

### Optional Parameters
- `relativelyPositionedViews`: `{
  "itemsType": {
    "properties": [
      {
        "name": "otherNodeId",
        "description": "otherNodeId",
        "type": {
          "name": "INT"
        }
      },
      {
        "name": "position",
        "description": "position",
        "type": {
          "entries": [
            "LEFT_OF",
            "RIGHT_OF",
            "ABOVE",
            "BELOW"
          ],
          "name": "ENUM"
        }
      }
    ],
    "requiredProperties": [],
    "name": "OBJECT"
  },
  "name": "ARRAY"
}`
- `longPress`: `Boolean`
  A standard tap is default, but return 'true' to perform a long press instead.



<hr/>

**NOTE**: THIS IS GENERATED DOCUMENTATION