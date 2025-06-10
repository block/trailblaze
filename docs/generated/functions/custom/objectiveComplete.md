# Function `objectiveComplete`

## Description
Use this tool to indicate the status of the current objective item.
Give an explanation of the current status of this specific objective item (not the overall objective).
This allows the system to track progress through individual items in the objective list.

### Command Class
`xyz.block.trailblaze.toolcalls.commands.ObjectiveCompleteTrailblazeTool`

## Registered Tool Call to Open AI
```json
{
    "name": "objectiveComplete",
    "parameters": {
        "type": "object",
        "properties": {
            "description": {
                "type": "string",
                "description": "The text description of the current objective item you're reporting on (copy exactly from the objective list)"
            },
            "explanation": {
                "type": "string",
                "description": "A message explaining what was accomplished or the current progress for this specific objective item"
            },
            "status": {
                "type": "string",
                "description": "Status of this specific objective item: 'completed' (move to next item), 'failed', or 'in_progress' (continuing with this same item)."
            }
        },
        "required": [
            "description",
            "explanation",
            "status"
        ]
    },
    "description": "Use this tool to indicate the status of the current objective item.\nGive an explanation of the current status of this specific objective item (not the overall objective).\nThis allows the system to track progress through individual items in the objective list."
}
```

<hr/>

**NOTE**: THIS IS GENERATED DOCUMENTATION