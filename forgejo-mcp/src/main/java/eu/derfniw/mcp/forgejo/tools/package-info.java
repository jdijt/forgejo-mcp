/**
 * MCP tools exposed to the client. Read-only context-gathering over the Forgejo REST API: each tool
 * forwards the per-request upstream bearer (resolved by the broker) to the typed
 * {@link eu.derfniw.mcp.forgejo.forgejo.ForgejoReposApi} and projects the response into a small
 * model-facing record. Plain JSON output, no MCP UI widgets.
 */
@NullMarked
package eu.derfniw.mcp.forgejo.tools;

import org.jspecify.annotations.NullMarked;
