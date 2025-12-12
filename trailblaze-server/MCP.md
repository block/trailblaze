# Trailblaze MCP Server
This is an MCP server for Trailblaze that allows you to use Trailblaze from an MCP Client like [Goose](https://github.com/block/goose).

## Register the MCP Server with Goose

* ID `trailblaze`
* Name `Trailblaze`
* Description
  `A tool to facilitate the creation and execution of mobile ui tests using natural language using the Trailblaze library.`
* URI `http://localhost:52525/sse`

## Development

Start the [MCP Inspector](https://github.com/modelcontextprotocol/inspector) with

```shell
DANGEROUSLY_OMIT_AUTH=true npm exec --loglevel=verbose  @modelcontextprotocol/inspector
```

for debugging.

Add the Trailblaze MCP Server to the MCP Inspector.

Now you can explore all the available commands and responses from the Trailblaze MCP Server.