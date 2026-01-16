# Trailblaze MCP Server
This is an MCP server for Trailblaze that allows you to use Trailblaze from an MCP Client like [Goose](https://github.com/block/goose).

## Transport

This server uses **Streamable HTTP** transport (not SSE). The endpoint accepts JSON-RPC requests via HTTP POST.

## Register the MCP Server with Goose

* ID `trailblaze`
* Name `Trailblaze`
* Description
  `A tool to facilitate the creation and execution of mobile ui tests using natural language using the Trailblaze library.`
* Type `streamablehttp`
* URI `http://localhost:52525/mcp`

## Connection Flow

1. Client sends POST to `/mcp` with JSON-RPC request body
2. Server creates a session (if new) and returns `Mcp-Session-Id` header
3. Client includes `Mcp-Session-Id` header in subsequent requests
4. Optional: Client can GET `/mcp` (with session header) for server-to-client streaming
5. Client can DELETE `/mcp` (with session header) to terminate the session

## Development

Start the [MCP Inspector](https://github.com/modelcontextprotocol/inspector) with

```shell
DANGEROUSLY_OMIT_AUTH=true npm exec --loglevel=verbose  @modelcontextprotocol/inspector
```

for debugging.

Add the Trailblaze MCP Server to the MCP Inspector using the Streamable HTTP transport type.

Now you can explore all the available commands and responses from the Trailblaze MCP Server.