# Design

## Context

See proposal.md for motivation. Facts about the kit that shape the approach (all observed in
`copilot-mordernize-harness/appmod/offering-1-markdown-kit`):

- Agents are thin: `dependency-security-advisor.agent.md` says "follow the X skill step by step", and the skill holds
  the procedure. Specialists are `user-invocable: false`; only `appmod-harness` is `true`.
- Deterministic work lives in single-file, JDK-only Java tools in `.github/appmod/tools/` (`DependencyAdvisor.java`,
  `CodeGraph.java`), started with `java <file>.java`. `DependencyAdvisor` carries a small embedded JSON reader and
  returns untyped maps.
- Reporting goes through the `harness-reporting` skill (`.appmod/result.json`, `.appmod/summary.md`). The harness
  excludes `.appmod/` from commits.
- `KitInstaller` copies the whole `agents`, `skills`, `prompts` and `appmod/tools` trees, so new files install as they are.
- The front door's routing table is a Markdown table with one row per request type, plus a "Never" list.
- Cloudflare's skill (`skills/security-audit/` in its repository) runs six phases and writes `findings.json`,
  `REPORT.md`, `FINDINGS-DETAIL.md` and `NEEDS-VALIDATION.md`. Verdicts are `confirmed`, `needs_validation` and
  `rejected`. `findings.json` is an array whose records differ by verdict (read from `report-schema.json` at the pinned
  commit): every record has `fingerprint`, `title`, `description`, a `trace` (entrypoint, propagation and sink steps,
  each `file` + one `line`) and `evidence` (`file`, one `line`, `description`). A `confirmed` record adds root cause,
  conditions, execution, `remediation.strategy`, `severity` and `confidence`; a `needs_validation` record has
  `claimed_root_cause`, `blockers` and `validation_plan`, and no severity or confidence. The schema has no CWE, no code
  excerpt, no validator and no commit; locations are single lines, not ranges. The attack class is recorded only in
  the coverage ledger's units (linked to findings by fingerprint), and the reviewed commit only in `run-metadata.json`.
  The skill is built for a coding agent with parallel sub-agents and ships Node validators.

## Goals / Non-Goals

**Goals:**
- One agent, one prompt, one CSV, working in Copilot CLI and in Claude Code from the same Markdown files.
- A CSV that is correct by construction: right columns, valid quoting, no spreadsheet formula injection.
- Provenance: anyone can tell which upstream commit produced an audit.

**Non-Goals:**
- Fixing the issues. Fixing stays with the existing fix routes (and later the NATS subscriber).
- Publishing the CSV on NATS or reading the intel service's database directly (separate `auditor` change).
- Re-implementing or rewriting Cloudflare's methodology. Local edits to the vendored files are limited to what is
  needed to run in the kit, and each one is listed in `UPSTREAM.md`.
- PR review or PR audit (out of scope for the project).

## Decisions

### D1. The agent is user-invocable and the front door routes to it
`security-audit.agent.md` sets `user-invocable: true`, because a person and the NATS subscriber must be able to start it
directly. `appmod-harness` gets one new routing row (audit, vulnerability hunt, code security review → `security-audit`)
so the front door still handles every request. Fix requests after an audit use the existing rows. The front door does
not hand-edit audit output.
*Alternative:* internal-only specialist. Rejected: the subscriber then needs the harness's whole routing for a
read-only job, and users could not run the audit on its own.

### D2. Vendor the skill at a pinned commit
The skill is copied into `.github/skills/security-audit/` with `UPSTREAM.md` (repository URL, commit SHA, licence,
SHA-256 over the vendored file list, local changes). The agent never fetches the skill at run time.
*Alternative:* `npx skills add` at install time. Rejected: not pinned, needs the network and Node on every machine, and
the audit's behaviour could change under a running pipeline. An upstream update becomes a reviewed change to the pin.
If the licence does not permit redistribution, the fallback is a documented install step that checks out the pinned
commit into the same folder, and the agent stops with a clear message when the folder is absent.

### D3. CSV by a deterministic tool, not by the model
`FindingsToCsv.java` reads a skill run directory (`findings.json`, plus `run-metadata.json` and
`coverage-ledger.json` for the commit and the attack class) and writes `issues.csv`. The model is good at finding
issues and bad at quoting multi-line text in CSV. The tool follows the kit's single-file, JDK-only convention.
Following the project's preference for typed models over untyped trees, the tool reads the findings into Java records
(a sealed `Finding` with `Confirmed`, `NeedsValidation` and `Rejected` variants, plus `TraceStep` and `Evidence`) and
writes rows from those records; the embedded reader exists only to fill the records. It accepts the shapes the
skill's `report-schema.json` defines at the pinned commit and fails with the fingerprint on a missing path or line, an
unknown verdict or an unknown shape, instead of guessing. The skill's own Node validator is not required for this.
*Alternative:* Node script next to Cloudflare's validators. Rejected: the kit's other tools are Java, Node is optional
on the target machines, and the project avoids Node for data handling.

### D4. Columns and rows
One row per `evidence` entry (a trace step that is not also an evidence entry is not a row; it is summarized in the
`trace` column). Columns, in order, with their source in the pinned schema:

| Column | Source |
|---|---|
| `file`, `line`, `title` (the minimum) | `evidence[].file`, `evidence[].line`, `title` |
| `fingerprint` | `fingerprint` (the issue id; repeats on each location row) |
| `verdict` | `verdict` |
| `severity` | `severity.overall_severity` (confirmed only) |
| `confidence` | `confidence.score` (confirmed only) |
| `location_note` | `evidence[].description` |
| `description` | `description` |
| `root_cause` | `root_cause`, or `claimed_root_cause` when unproven |
| `trace` | trace steps joined as `kind file:line`, entry point to sink |
| `remediation` | `remediation.strategy` (confirmed only) |
| `blockers` | `blockers` joined (unproven only) |
| `validation_plan` | `validation_plan.local` and `.deployment` (unproven only) |
| `attack_class` | the coverage-ledger unit whose result fingerprints include this one, text after `#`; empty if none |
| `commit` | `run-metadata.json` `source_ref`, rendered as given (empty if absent) |
| `generated_at` | when the CSV was produced (UTC, ISO-8601) |

There is no `cwe`, `evidence_excerpt` or `validator` column: the pinned schema has none, and the tool does not read the
audited source to invent an excerpt. The line column is a single line number because the schema gives locations as
single lines; the tool does not fake a range. Paths are repository-relative with forward slashes; a path that is
absolute or contains `..` fails the run. Severity and confidence exist only on `confirmed` records (SKILL.md, "Separate
priority from certainty"), so those cells are empty for `needs_validation` rows and the tool never invents values.
Rows are sorted by verdict (`confirmed` first), then severity (critical, high, medium, low, informational), then file
and line, so the top of the file is what matters most. Long text cells are capped (default 4,000 characters, ending
with a visible truncation marker) so one large value cannot blow up a spreadsheet cell.

### D4a. Cited locations are checked against the repository
Observed in the first real run (Claude Code, fixture repository): 10 of 11 rows cited lines far past the end of their file
(for example line 122 of a 31-line file), because the model numbered lines across several files read together. Neither the
skill's validators nor the schema notice this, so a plausible but impossible CSV would have been delivered. `FindingsToCsv`
therefore takes `--repo DIR` (required) and, before writing, checks every evidence and trace location of every listed
finding: a regular file inside the repository after resolving links, and a line number not above the file's line count
(a trailing line without a newline counts). All failures are listed at once, with fingerprint, file, line and file length, exit
code 3, no CSV. The skill tells the agent to cite lines from one file at a time, and on this failure to re-read the files,
correct the findings, re-run the skill's final verification and try again (at most twice). Rejected findings are not
checked, because they are not listed. This proves existence, not that the line is the flaw; the CSV says nothing stronger.
*Alternatives:* flag bad rows in a column and write the CSV anyway (a reader may trust a wrong line; rejected); read the
cited lines and compare them with the description (no excerpt in the schema to compare with, so it would be a guess).

### D5. Spreadsheet safety
RFC 4180 quoting for every field (comma, quote, CR, LF), UTF-8 with a BOM so Excel reads non-ASCII text correctly,
CRLF line ends. A text field that starts with `=`, `+`, `-` or `@` (after trimming, and also tab or CR first) is
prefixed with a single quote so it displays as text. Numeric line columns are never prefixed. This matters because the
fields come from the audited code, which may be hostile.

### D6. Verdict filter
`confirmed` and `needs_validation` are written; `rejected` is not (it stays in `findings.json`). Without a sandbox, the
skill keeps unproven leads as `needs_validation`; dropping them would hide most results. The `verdict` column lets a
reader filter.

### D7. Output location and reporting
The vendored skill requires its run directory to be outside the audited repository (default
`~/security-audit-skill/<repo-name>/run-<N>`) and allows an inside location only if the user selects it and version
control ignores the whole directory; otherwise it stops (SKILL.md, "Full audit setup"). Making `.appmod/` ignored would
mean editing the audited repository, which the read-only rule forbids, so the agent keeps the skill's default:
- **Skill run directory (external):** `findings.json`, `coverage-ledger.json`, `run-metadata.json`, `REPORT.md`,
  `FINDINGS-DETAIL.md`, `NEEDS-VALIDATION.md` and the per-agent folders stay where the skill puts them. The agent never
  passes an in-repo path.
- **Deliverables (in the repository):** `.appmod/security-audit/issues.csv`, plus `.appmod/result.json` and
  `.appmod/summary.md`. `.appmod/` is already excluded from harness commits. `result.json` gets
  `jobType: "security-audit"`, `skillCommit`, `runDirectory` (the external path), issue counts by severity and verdict,
  and the CSV path; `summary.md` follows the reporting skill's headings and names both locations.
Counts are produced by `FindingsToCsv` (printed as JSON on its last line) so the agent copies numbers instead of
counting. `FindingsToCsv` takes the external `findings.json` path and an output directory, and refuses to write the CSV
anywhere that is not the given directory.
*Alternatives:* everything external (the harness and the subscriber look for `.appmod/`, so rejected); everything under
`.appmod/security-audit/` (needs a git-ignore the agent may not create, so it would stop on most repositories).

### D8. Read-only and untrusted input
The agent's tools are `read`, `search`, `edit` (needed to write the run files and deliverables; it cannot be limited to
paths, so the instructions limit it), `shell` and `agent`, with instructions that forbid edits inside the audited
repository except the deliverables under `.appmod/`, allow writes only to the skill's external run directory and those
deliverables, forbid `git commit`/`push`, and forbid network use except the optional intel tool. Repository content and
advisory text are data: instructions found in them are ignored and may be reported as findings. The skill forbids
running any code of the audited repository unless an OS-enforced sandbox provides every control it lists (no network,
allowlisted empty environment, read-only target, resource limits). The agent does not weaken this: when the controls
cannot be enforced, as is likely on a plain Windows workstation, nothing is executed and the affected candidates stay
`needs_validation`, which the summary says.

### D9. Optional intel tool
If a tool named `listVulnerabilities` is available, the recon phase may call it (recent window, product names taken from
the repository's dependency files) and pass advisory titles to hunters as leads. Absence or failure is recorded in the
summary and never stops the audit. No new MCP registration is added by this change (the kit's MCP configs keep their
current content); `docs` explain how to add the intel server alongside the appmod server.

### D10. Degraded tools and the validators on Windows
Copilot CLI and Claude Code differ in sub-agent support. The skill's phases are written to the agent as "use sub-agents
when available, otherwise run the phase yourself". The agent notes in the summary which phases ran reduced (no
sub-agents, no Node validators). Parity is verified by two real runs (one per tool) on a small fixture repository with
planted flaws, recorded in the tasks; the Copilot run is skipped and said so if Copilot CLI is not installed.

The skill's two Node validators (`validate-findings.cjs`, `validate-coverage-ledger.cjs`) refuse to read a file unless
Node exposes `O_NOFOLLOW` and `O_NONBLOCK`, which it does not on native Windows (observed on this machine: both fail
with "OS no-follow and nonblocking input protection is unavailable"). The skill ends a run in one of two states: both
validators pass, or `run_status: "incomplete"`. Without a remedy no Windows audit could finish. The kit therefore
adds `.github/appmod/tools/validate-run.cjs`, a kit-owned script (the vendored files stay byte-identical):
- It runs each stock validator unchanged first. Only when a stock validator fails with that exact "unavailable" message
  does it read the file itself and call the validator's exported `validateDocument()`, so the same rules apply. Any
  other failure (a rule violation, a missing file, bad JSON) is a failure, never a fallback.
- The fallback read keeps the checks Windows allows: not a symlink, a regular file, one hard link, within the
  validator's size limit. It lacks the no-follow open, so a swap between check and read is not prevented; the files are in
  the skill's external run directory, written by the audit itself, which is why this is judged acceptable.
- It prints a final JSON line with `valid`, `weakened` (true when the fallback was used) and the mode per file. The agent
  records `weakened` in `result.json` and says so in `summary.md`; it never reports the stock validators as having passed.
- It lives in `appmod/tools`, so it is installed with the other tools (`--target`: `.github/appmod/tools`; `--user`:
  `~/.copilot/appmod/tools`, where its relative path to the skill still resolves). The skill looks in both places.
  Re-pinning the skill requires re-running its tests (task 2.5), because it depends on the validators'
  exported function names.
*Alternatives:* accept `incomplete` on Windows (every audit there looks unfinished); run audits only under WSL or Linux
(faithful to the skill, but the agent cannot control where it runs).

## Risks / Trade-offs

- **Licence may forbid redistribution** → check first (task 1.1); fallback in D2.
- **Upstream `findings.json` shape drifts** → pinned commit, and `FindingsToCsv` fails loudly on an unknown shape
  instead of writing a partial CSV; a fixture copied from the pinned commit is a test.
- **Weaker validator read on Windows** → D10: same rules, reduced file-read hardening, always disclosed (`weakened`);
  the wrapper falls back only on the one known error.
- **Sub-agent parity on Copilot CLI** → D10; the acceptance run records the result, and the summary reports reduced phases.
- **Audit cost and duration** (many model calls on a large repository) → the prompt exposes a scope argument (paths) and
  the skill's own coverage-led stopping is kept; no custom limits are invented here.
- **False positives in the CSV** → `confidence` and `verdict` columns, and `needs_validation` is labelled as unproven in
  the summary.
- **Excerpts leak secrets** (a finding about a hard-coded key quotes the key) → the CSV sits in `.appmod/` inside the
  repository working tree, excluded from commits, and the README warns that the CSV can quote code in its descriptions
  and location notes; the skill's own run directory is outside the repository. The schema has no secret marker, so the
  tool cannot mask values; this is documented only.
- **Mostly `needs_validation` results** (no sandbox on this kind of machine) → expected and reported plainly; the
  verdict column and the empty severity make clear that these are unproven, not low-severity.
- **A new user-invocable agent weakens the "one front door" rule** → the routing row keeps the front door complete;
  direct use is allowed, not required.
