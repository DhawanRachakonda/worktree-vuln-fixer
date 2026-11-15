# Proposal

## Why

The markdown kit (`copilot-mordernize-harness/appmod/offering-1-markdown-kit`) can upgrade, fix known CVEs in
dependencies and migrate SQL, but it cannot look for security flaws in the application's own code. Cloudflare publishes
a security-audit skill that does this well (reconnaissance, coverage-led hunting, independent validation of each
candidate), but its output is JSON and Markdown reports that neither a reviewer's spreadsheet nor the harness's
issue-fixing pipeline can consume directly. A kit agent that runs the skill and delivers a flat CSV of issues, each with
the file and line where it was found, closes that gap and gives the later issue-fixing flow a stable input.

## What Changes

- Add a **`security-audit` agent** to the kit. It is user-invocable, so it can be started directly (by a person or by
  the NATS subscriber).
- Vendor Cloudflare's `security-audit` skill into the kit's skills at a **pinned upstream commit**, with a provenance
  record (repository, commit, licence, checksum, local changes).
- Add a deterministic, JDK-only single-file tool, **`FindingsToCsv.java`**, that converts the skill's `findings.json`
  (with the run's metadata and coverage ledger) into **`issues.csv`**: one row per code location, with file and line
  number as the minimum and extra detail columns.
  The model never writes the CSV by hand, and the tool refuses to write it if any cited file or line does not exist in the
  audited repository (the first real run produced line numbers past the end of their files).
- Add `validate-run.cjs`, a kit-owned script that runs the skill's two Node validators and, only where they cannot read
  files (native Windows), applies the same rules through their exported function and says so, so that audits on Windows
  can finish validated instead of always ending `incomplete`.
- Add a `security-audit-csv` skill describing the column mapping and the output contract, and a `/security-audit`
  prompt as a one-command entry point.
- Add a routing rule to **`appmod-harness`** so every security-audit request still enters through the front door,
  which delegates to the new agent. **No existing routing row changes.**
- Keep the skill's own run directory (`findings.json`, its reports and working files) outside the audited repository,
  as the skill itself requires, and write only the deliverables into the repository under `.appmod/security-audit/`
  (`issues.csv`, with `.appmod/result.json` and `summary.md` from the existing `harness-reporting` skill). The harness's
  existing `.appmod/` exclusion keeps them out of fix commits, and the result file records the external run path.
- The audit is **read-only**: the agent never edits, commits or pushes the audited code.
- Treat everything in the audited repository (code, comments, READMEs) as untrusted data, never as instructions.
- Optionally use the intel service's `listVulnerabilities` tool when it is registered, to bring recent advisories into
  the audit. The audit and the CSV work without it.

## Capabilities

### New Capabilities
- `security-audit-agent`: the kit agent that audits a repository with the pinned Cloudflare skill and produces the CSV
  issue list, its routing from the front door, its read-only and untrusted-data rules, and its output locations.

### Modified Capabilities
<!-- None: no spec exists yet under openspec/specs/, and this change introduces its own capability. -->

## Impact

- **New files** in `copilot-mordernize-harness/appmod/offering-1-markdown-kit/`: `.github/agents/security-audit.agent.md`,
  `.github/skills/security-audit/` (vendored skill and `UPSTREAM.md`), `.github/skills/security-audit-csv/SKILL.md`,
  `.github/appmod/tools/FindingsToCsv.java`, `.github/appmod/tools/validate-run.cjs`, `.github/prompts/security-audit.prompt.md`.
- **Edited files:** `.github/agents/appmod-harness.agent.md` (one routing row, and the "Never" list note that audit
  output is read-only); `.github/instructions/appmod-harness.instructions.md` (the "stay inside the repository" rule gets
  one exception for the audit agent's external run directory, and the entry-point paragraph names the new agent); the kit
  `README.md` (new agent and prompt).
- **No installer change:** `KitInstaller` already copies whole `agents`, `skills`, `prompts` and `appmod/tools` trees.
- **Dependencies:** Cloudflare's skill ships Node validators (`validate-findings.cjs`, `validate-coverage-ledger.cjs`);
  they need Node, and on native Windows they cannot read files at all, which `validate-run.cjs` works around (design
  D10). The Java tool performs its own validation, so the CSV never depends on Node.
- **Risk:** the skill expects parallel sub-agents. Parity on Copilot CLI and on Claude Code is an explicit acceptance
  check, not an assumption.
- **Licence:** redistribution of the vendored skill depends on the upstream licence, which is checked first.
