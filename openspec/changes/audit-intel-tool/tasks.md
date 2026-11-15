# Tasks

- [x] 1.1 Name the tool in `security-audit.agent.md` and map `server/tool` to `mcp__server__tool` in `KitInstaller`; verify the generated Claude agent lists it
- [x] 1.2 Test the mapping in `InstallerTests` (the generated agent lists the tool; a `server/*` entry; an unknown plain tool is still refused); verify the test fails without the mapping
- [x] 2.1 A real Claude Code audit with the intel service running against the real feed and registered as `intel`: record whether the agent called the tool and what the summary says; verify with the stream-json log

Recorded results (2026-11-15): `InstallerTests` 69 passed (the audit agent lists `mcp__intel__listVulnerabilities`; `server/*` and `server/tool` map once). Real audit with the service connected and the fix in place: the agent called
`mcp__intel__listVulnerabilities {"scope":"recent","sinceDays":30,"product":"java"}`, got an empty list with `freshness` OK, and wrote "The advisory feed was queried once and returned no records" in `summary.md` (58 turns, $0.94, 3 min 14 s).
Before the fix the same setup never called the tool. Not run: the Copilot CLI with the `intel/listVulnerabilities` tool name (its syntax is taken from GitHub's documentation).
