# Proposal

## Why

The intel service exists to bring the latest advisories into an audit, but a real Claude Code audit with the service registered and connected never called it ("No advisory feed was
used"). The cause was in the kit, not in the service: the audit agent's tool list names only Read, Search, Edit, Shell and Agent, and a Claude Code agent with an explicit tool list
cannot use an MCP tool that is not named in it. The earlier live checks called the tool directly, not through the audit agent, so they could not see this.

## What Changes

- The `security-audit` agent lists the intel tool: `intel/listVulnerabilities` for Copilot, written `mcp__intel__listVulnerabilities` in the generated Claude Code agent.
  The server is registered under the name `intel`, as `intel-service/docs/connecting.md` already says.
- The installer maps a `server/tool` entry of an agent's tool list to Claude Code's `mcp__server__tool` name (`server/*` becomes `mcp__server`).
- The requirement "Optional vulnerability intelligence" says the agent's tool list includes the tool, so that a registered server is usable; it stays optional.

## Capabilities

### New Capabilities
None.

### Modified Capabilities
- `security-audit-agent`: the requirement "Optional vulnerability intelligence".

## Impact

- `kit`: `security-audit.agent.md` (one tool name), `KitInstaller.java` (the mapping), `InstallerTests`.
- No change to the intel service, the engine, the CSV or the other agents.
