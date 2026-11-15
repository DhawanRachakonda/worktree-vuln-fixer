# Spec Delta

## MODIFIED Requirements

### Requirement: Optional vulnerability intelligence
When the intel service's `listVulnerabilities` tool is registered (as the MCP server `intel`), the audit agent SHALL be able to call it: its tool list names the tool, for Copilot and, as
`mcp__intel__listVulnerabilities`, for Claude Code. The audit MAY use it to bring recent advisories that match the repository's technology into the audit. The audit and the CSV SHALL work,
with the same columns, when the tool is absent or unreachable.

#### Scenario: Intel tool not registered
- **WHEN** an audit runs and the tool is not registered
- **THEN** the audit completes and produces the CSV, and the summary notes that no advisory feed was used

#### Scenario: Intel tool unreachable
- **WHEN** the tool is registered but the service does not answer
- **THEN** the audit continues without it and records that fact in the summary rather than failing

#### Scenario: Intel tool registered and reachable
- **WHEN** an audit runs with the server `intel` connected
- **THEN** the agent calls `listVulnerabilities`, gives what it returns to the hunters only as leads, and the summary says the feed was used

#### Scenario: The installed agent can name the tool
- **WHEN** the kit is installed for Claude Code
- **THEN** `.claude/agents/security-audit.md` lists `mcp__intel__listVulnerabilities`
