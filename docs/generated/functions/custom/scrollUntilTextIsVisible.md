## Tool `scrollUntilTextIsVisible`

## Description
Scrolls the screen in the specified direction until an element containing the provided text becomes visible
in the view hierarchy. The text does not need to be an exact match - it will find elements where the 
provided text appears anywhere within the element's text.

The text argument is required. Only provide additional fields if multiple elements contain the same text.
In this case the additional fields will be used to identify the specific view to expect to be visible while scrolling.

### Command Class
`xyz.block.trailblaze.toolcalls.commands.ScrollUntilTextIsVisibleTrailblazeTool`

### Registered `ScrollUntilTextIsVisibleTrailblazeTool` in `ToolRegistry`
### Required Parameters
- `text`: `String`
  Text to search for while scrolling.

### Optional Parameters
- `id`: `String`
  The element id to scroll until. REQUIRED: 'text' and/or 'id' parameter.
- `index`: `Integer`
  A 0-based index to disambiguate multiple views with the same text. Default is '0'.
- `direction`: `[
  "UP",
  "DOWN",
  "RIGHT",
  "LEFT"
]`
- `visibilityPercentage`: `Integer`
  Percentage of element visible in viewport. Default is '100'.
- `centerElement`: `Boolean`
  If it will attempt to stop scrolling when the element is closer to the screen center. Default is 'false'.



<hr/>

**NOTE**: THIS IS GENERATED DOCUMENTATION