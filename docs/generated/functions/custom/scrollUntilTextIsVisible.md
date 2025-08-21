## Tool `scrollUntilTextIsVisible`

## Description
Scrolls the screen in the specified direction until an element with the provided text becomes visible
in the view hierarchy. Ensure that you provide the entire string to this function to streamline finding 
the corresponding view.

The text argument is required. Only provide additional fields if the text provided exactly
matches elsewhere on the screen. In this case the additional fields will be used to identify
the specific view to expect to be visible while scrolling.

### Command Class
`xyz.block.trailblaze.toolcalls.commands.ScrollUntilTextIsVisibleTrailblazeTool`

### Registered `ScrollUntilTextIsVisibleTrailblazeTool` in `ToolRegistry`
### Required Parameters
- `text`: `String`
  The text to match on. This is required.
NOTE:
- The text can be a regular expression.
- If more than one view matches the text, other optional properties are required to disambiguate.
- `direction`: `Enum(entries=[UP, DOWN, RIGHT, LEFT])`
  Valid values: UP, DOWN, LEFT, RIGHT. If not provided, it will start scrolling towards the bottom of the screen (DOWN value).

### Optional Parameters
- `index`: `Integer`
  0-based index of the view to select among those that match all other criteria.
- `id`: `String`
  Regex for selecting the view by id. This is helpful to disambiguate when multiple views have the same text.
- `visibilityPercentage`: `Integer`
  Percentage of element visible in viewport.
- `centerElement`: `Boolean`
  Boolean to determine if it will attempt to stop scrolling when the element is closer to the screen center.
- `enabled`: `Boolean`
- `selected`: `Boolean`



<hr/>

**NOTE**: THIS IS GENERATED DOCUMENTATION