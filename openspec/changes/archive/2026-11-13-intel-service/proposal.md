# Proposal

## Why

The security-audit flow needs current vulnerability intelligence, but no component in this project fetches or serves
it. Cloudflare Radar's Zero Wire RSS feed is a curated, low-volume stream of recent vulnerabilities (about a handful of
items at any time, each a narrative briefing with CVE ids, tags and a recommended action). Applications that audit or
fix code need to ask "what is new?" without each one polling, parsing and trusting an external feed. A single service
that ingests the feed once a day and exposes it through MCP lets the auditor (and any other MCP client, Copilot CLI or
Claude CLI) consume the same data.

## What Changes

- Add a new Spring Boot service, `intel-service`, that fetches the Zero Wire RSS feed once a day on a JobRunr schedule
  and stores new and changed vulnerability records in a database.
- Store each record with its feed identity, CVE ids, tags, the narrative sections (details, risk, affected deployments,
  recommended action), source URLs, and separate published, last-reviewed and first-seen timestamps. Re-ingesting an
  unchanged item is a no-op; a corrected item updates the record without losing its history.
- Expose an MCP server over HTTP (Spring AI) with a read-only tool to list vulnerabilities: all, or recent with a
  look-back window in days, optionally narrowed by CVE id or product/tag. Results are bounded and paginated.
- Treat all feed text as untrusted data: it is stored and returned verbatim as content, never interpreted as
  instructions by the service, and never used to trigger outbound requests.
- Out of scope for this change: the auditor that consumes the tool, publishing issues to NATS, and any change to the
  existing NATS subscriber. The PR-audit agent from the reference document is not part of this work.

## Capabilities

### New Capabilities
- `vulnerability-intel`: scheduled daily ingestion of the Zero Wire feed into storage, and a read-only MCP tool that
  lists stored vulnerabilities with recency, CVE and product filters under bounded, paginated results.

### Modified Capabilities

None. The project has no existing specs.

## Impact

- New code: a Java / Spring Boot service with JobRunr (database-backed scheduling), Spring AI's MCP server starter,
  an RSS parser, and database migrations for the vulnerability store.
- New runtime dependencies: an embedded H2 database kept in a local file (no database server) and outbound HTTPS access
  to `radar.cloudflare.com`.
- New network surface: an HTTP MCP endpoint that must be protected (localhost binding or a token) because it serves
  security data to other applications, and an optional operations dashboard (JobRunr's own) on its own port that is off
  unless enabled with mandatory credentials.
- No changes to `copilot-mordernize-harness`.
- Stack decided in design.md: Java 25, Spring Boot 4.1.2, Spring AI 2.0.2 and JobRunr 9.0.3, all GA releases, built
  with Gradle and storing data in an embedded H2 file database.
