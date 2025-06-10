# Function `tapOnElementWithAccessibilityText`

## Description
Invoking this function will trigger a tap on a view with the provided accessibility text.
This will commonly be used when tapping on visual elements on the screen that do not have
text to identify it. This includes images or avatars with text, prefer to use the
accessibility text for these views because the text for an image or avatar will not resolve.
Ensure that you provide the entire accessibilityText string to this function to streamline
finding the corresponding view.

The text argument is required. Only provide additional fields if the accessibility text
provided exactly matches elsewhere on the screen. In this case the additional fields will be
used to identify the specific view to tap on.

### Command Class
`xyz.block.trailblaze.toolcalls.commands.TapOnElementWithAccessiblityTextTrailblazeTool`

## Registered Tool Call to Open AI
```json
{
    "name": "tapOnElementWithAccessibilityText",
    "parameters": {
        "type": "object",
        "properties": {
            "accessibilityText": {
                "type": "string",
                "description": "The accessibilityText to match on. This is required.\nNOTE:\n- The text can be a regular expression.\n- If more than one view matches the text, other optional properties are required to disambiguate."
            },
            "id": {
                "type": "string"
            },
            "index": {
                "type": "string"
            },
            "enabled": {
                "type": "boolean"
            },
            "selected": {
                "type": "boolean"
            }
        },
        "required": [
            "accessibilityText"
        ]
    },
    "description": "Invoking this function will trigger a tap on a view with the provided accessibility text.\nThis will commonly be used when tapping on visual elements on the screen that do not have\ntext to identify it. This includes images or avatars with text, prefer to use the\naccessibility text for these views because the text for an image or avatar will not resolve.\nEnsure that you provide the entire accessibilityText string to this function to streamline\nfinding the corresponding view.\n\nThe text argument is required. Only provide additional fields if the accessibility text\nprovided exactly matches elsewhere on the screen. In this case the additional fields will be\nused to identify the specific view to tap on."
}
```

<hr/>

**NOTE**: THIS IS GENERATED DOCUMENTATION