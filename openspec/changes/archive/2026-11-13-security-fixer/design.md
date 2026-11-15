# Design

## Context

- The kit (`copilot-mordernize-harness/appmod/offering-1-markdown-kit/.github`) keeps agents in `agents/`, skills in `skills/`,
  prompts in `prompts/`, JDK-only tools in `appmod/tools/` and shared rules in `instructions/appmod-harness.instructions.md`. The
  installer (`install/KitInstaller.java`) copies the tree into a target repository.
- `security-audit` (change `security-audit-agent`) writes `.appmod/security-audit/issues.csv` through `FindingsToCsv.java`: UTF-8
  with a byte order mark, CRLF, RFC 4180 quoting, columns `file, line, title, fingerprint, verdict, severity, description,
  root_cause, trace, remediation, blockers, validation_plan, commit`, one row per code location, cells that a spreadsheet could
  evaluate prefixed with a single quote, and it is read-only by design. Its tests live in `tests/security-audit/ToolTests.java`.
- `appmod-harness` delegates by a routing table; for audits its section 3 says that a request to fix is "a new request" with fix
  routes only for dependencies. `appmod-verifier` builds, tests and writes the report, and its result overrides earlier claims.
  `harness-reporting` defines `.appmod/result.json` (`status`, `build`, `tests`, `commits`, `requiresHumanReview`, `assumptions`, ...).
- subscriber-engine gives each audit message an interactive window (and, with the change `fix-worktrees`, a worktree), its prompt
  (`prompts/securityaudit.issuefixesAgent.md`) already states the fixing rules, and it records a session as `DONE`, `PARTIAL`,
  `NEEDS_INPUT` or `ERROR` from the exit code and a fresh `.appmod/result.json`.

## Goals / Non-Goals

**Goals:**
- An agent that fixes audit findings the way the prompt already asks, so the engine's audit subject can point at a specialist.
- The same agent works when a person starts it by hand, and is reachable from the front door.
- Reading the CSV is deterministic and cheap, not an act of parsing by the model.
- The report tells the truth about what was fixed and left, and the engine's state follows it.

**Non-Goals:**
- Finding issues (that is `security-audit`) or fixing vulnerable dependencies (`dependency-security-advisor` and app modernization).
- Merging, pull requests, pushing, or cleaning up worktrees.
- A Claude Code variant of the agent (the kit is Copilot-first; the neutral-source idea is a separate change).
- Running the repository's code to validate unproven issues beyond what the audit skill's sandbox rules allow.

## Decisions

### D1. A specialist agent, separate from `appmod-harness`
`security-fixer` is its own user-invocable agent. The engine names it directly (a person is in that window; one agent is simpler than
a hand-off, and a sub-agent cannot ask the person questions itself), and `appmod-harness` also routes to it so the front-door rule
("users start with `appmod-harness`") still holds, exactly as for `security-audit`. Tools: `read`, `search`, `edit`, `shell`,
`agent` (to hand the final check to `appmod-verifier`).
*Alternative:* put fixing into `appmod-harness`. Rejected: its job is routing; its file is already long, and the engine's prompt
would carry rules the agent should own.

### D2. Rules in the agent file, task in the prompt
Stable discipline (order, one commit per issue, limits, when to ask, reporting) lives in the agent file so it also holds when a person
starts the agent by hand. The engine's per-session prompt keeps the per-session facts (paths, which report) and can be trimmed later;
until then the two say the same things and the agent file wins on conflict. The prompt file for a person is
`prompts/security-fix.prompt.md` with the CSV path as an input.

### D3. `IssueList.java`: read the CSV with a tool
JDK only (no `package` line, so `java IssueList.java` works on a Java 22+ launcher, which `DependencyAdvisor.java` and
`CodeGraph.java` do not). It reads the file as UTF-8 (BOM removed), parses RFC 4180 properly (quoted commas, doubled quotes, line
breaks inside quotes, CRLF), removes the formula-guard quote, requires the columns `file`, `line`, `fingerprint`, `verdict`, groups
rows by `fingerprint`, orders issues (confirmed before `needs_validation`, then `critical` ... `informational`, then first
appearance), and prints either text or `--json`. A missing column or a row with a non-numeric line stops it with a message naming the
problem and nothing is printed as if valid. Tests extend the kit's runnable-check style in `tests/security-fixer/ToolTests.java`.
*Alternative:* let the model read the CSV text. Rejected: multi-line quoted cells, a BOM and the quote guard are exactly what
a model mishandles silently on a large file.

### D4. Where it works
The agent checks `git rev-parse --git-dir` against `--git-common-dir`: different means a linked worktree, so it works there and
does not touch branches. Equal means a primary checkout: it creates `<repository folder>-fix-<id8>` next to it on
`appmod/fix-<id8>` (`<id8>` = the first 8 characters of its own session id if it has one, otherwise 8 hexadecimal characters of the
audited commit or of `HEAD`), starting from the audited commit (the CSV's `commit` column, when `git cat-file -e` finds it) or
`HEAD`, and tells the person the folder and branch. A person can say "work in place", then it creates a new branch in the checkout
after saying the files will change. The naming matches `fix-worktrees` so a person sees one convention.

### D5. Order and granularity
Work list = issues from `IssueList.java`. One commit per fingerprint, even when the issue has several locations; the message is
`fix(security): <title> [<fingerprint>]`. Commits never include `.appmod/` (it is untracked output) and never `git add -A`; files are
added by name. After each fix: build and test (tool detection as `appmod-verifier` does); a failure means correct or discard that
fix, so the branch is never left worse than the baseline. The baseline is measured first: build and test once before any change, and
if it is already red the report says so and the agent only guards against making it worse.

### D6. Asking and deciding
Interactive (the task says `mode: interactive`, or the prompt does not say headless and a question tool exists): ask, with concrete
options, before a public-contract change, a new dependency, a test-framework change, a schema change, or a choice between fixes of
different risk. Headless (`mode: headless`): never ask; conservative option; leave the issue unfixed if none; record each decision
under `assumptions`. The engine's prompt already has a line for this and keeps it.

### D7. Status and report
`harness-reporting` gains two optional fields: `issuesFixed: [{fingerprint, commit}]` and `issuesLeft: [{fingerprint, reason}]`. Status
for a fix job: `succeeded` = every confirmed issue fixed and the verifier's build and tests pass; `partially_succeeded` = some
confirmed issue left, or the final build or tests fail; `needs_input` = stopped on a decision it may not take; `failed` = nothing
fixed. The agent delegates the last build and test to `appmod-verifier` and keeps its result over its own. A sub-agent
does not get a person's approval in a headless session, so when the verifier cannot run (found in the first real run: it was denied
because it changed directory in a compound command) the agent runs the build and tests itself after its last commit, uses that, and
says in the report that the verifier did not run. The engine needs no change
for this: it already maps these statuses to `DONE`, `PARTIAL`, `NEEDS_INPUT` and `ERROR`.

### D8. Routing and engine default
`appmod-harness.agent.md` gets a row "fix the findings of a security audit, issues.csv" -> `security-fixer` and its section 3 text
changes from "that is a new request: use the fix routes above (dependency issues -> ...)" to name the new route for code issues,
keeping `security-audit` read-only and `needs_validation` unproven. The shared instructions list the agent. In the engine,
`SUBSCRIBER_AUDIT_AGENT` defaults to `security-fixer`; the other two subjects keep `appmod-harness`.

### D9. Testing an agent
An agent's judgement cannot be unit-tested. What is tested: `IssueList.java` (checks, as for `FindingsToCsv.java`), the agent and
prompt files (front matter, tools, the required rules present by phrase, the same checks as the audit agent's), the harness routing
row, the installer (a scratch install holds the new files), the engine default, and the reporting skill's fields. What is run: a
fixture repository with planted flaws and a matching CSV, the agent started with Copilot CLI once by hand (interactive, one decision
asked) and once as the engine does it (a window, with the engine's prompt), and the results written down against expectations set
before the run, including: the checkout untouched, one commit per issue, a regression test for at least one, the unproven issue left
alone, an instruction planted in the CSV not obeyed, and a report that matches the commits.

## Risks / Trade-offs

- **The agent edits code and runs builds.** It is bounded by the worktree, the limits in the spec and the permission mode of the
  session, but a build script is arbitrary code. Documented; the engine's `ask` permission mode exists for repositories that are not
  trusted.
- **Quality of fixes varies.** The report states what was fixed and what was left, the verifier's result overrides, and every fix is
  a separate commit that can be dropped. It is a first reviewer's draft, not an approved fix.
- **Two copies of the rules** (agent file and engine prompt) can drift. D2 says the agent file wins and a test compares the phrases
  that matter in both.
- **Cold worktree builds** are slow and may need network; the agent may install dependencies from the lockfile in the worktree and
  records that it did.
- **Base commit older than HEAD**: later merge conflicts. Intended; the summary states the base.
- **Changing the engine's default agent** breaks nobody who sets `SUBSCRIBER_AUDIT_AGENT`; for the others the first run with the new
  agent is the change. Documented as BREAKING in the proposal.
