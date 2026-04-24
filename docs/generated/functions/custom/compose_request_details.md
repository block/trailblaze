## Tool `compose_request_details`

## Description
Request additional detail in the next view hierarchy snapshot.
Call this when you need more information than the default compact element list provides.
The next turn's view hierarchy will include the requested details for ALL elements,
then automatically revert to the compact format on subsequent turns.

Available detail types:
- BOUNDS: Include bounding box coordinates {x,y,w,h} for each element.
  Useful for spatial reasoning, determining element positions, checking viewport visibility,
  or disambiguating visually similar elements by location.

### Command Class
`xyz.block.trailblaze.compose.driver.tools.ComposeRequestDetailsTool`

### Registered `ComposeRequestDetailsTool` in `ToolRegistry`
### Required Parameters
- `include`: `{
  "itemsType": {
    "entries": [
      "BOUNDS"
    ],
    "name": "ENUM"
  },
  "name": "ARRAY"
}`



<hr/>

**NOTE**: THIS IS GENERATED DOCUMENTATION