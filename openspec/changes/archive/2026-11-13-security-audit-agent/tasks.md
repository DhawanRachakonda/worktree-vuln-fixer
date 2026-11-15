# Tasks

Paths are relative to `copilot-mordernize-harness/appmod/offering-1-markdown-kit/` ("the kit"). Assumptions recorded
here: the CSV tool targets Java 21 like the kit's other tools; the CSV's UTF-8 BOM and CRLF are for spreadsheet
compatibility; the Cloudflare skill is pinned to the upstream default-branch commit current when task 1.2 runs.

## 1. Upstream skill: licence, pin, vendoring

- [x] 1.1 Read the licence of `github.com/cloudflare/security-audit-skill` and decide whether it may be redistributed
      inside the kit; verify by recording the licence name, text link and decision at the top of `UPSTREAM.md`. If it
      may not be redistributed, stop and ask before continuing (design D2 fallback)
- [x] 1.2 Copy `skills/security-audit/` from the chosen upstream commit into `.github/skills/security-audit/`
      unmodified, and write `UPSTREAM.md` (repository, commit SHA, licence, SHA-256 over the file list, "local changes:
      none"); verify by re-computing the checksum from the folder and matching it to `UPSTREAM.md`
- [x] 1.3 Read the vendored `SKILL.md`, `report-schema.json` and `VALIDATION-AND-REPORTING.md` and write down in
      design.md's sections Context and D4 the exact finding fields and where file, lines, attack class, commit and
      severity live; verify that every CSV column in the spec maps to a schema field or is derived, and list any gap
      (done: gaps are no CWE, excerpt, validator or secret marker, single-line locations; the attack class comes from
      the coverage ledger and the commit from `run-metadata.json`; two files still to be read in 2.1 before the
      tool is written: the ledger unit shape and `source_ref` in `run-metadata.json`)

## 2. FindingsToCsv tool

- [x] 2.1 Write `.github/appmod/tools/FindingsToCsv.java` (JDK only, `java FindingsToCsv.java <run-dir> --out
      <dir>`): first read the ledger unit shape in `RECONNAISSANCE.md` and `validate-coverage-ledger.cjs` and the
      `source_ref` description in `SKILL.md`, then write a sealed `Finding` record hierarchy, a small reader that
      fills it, verdict filter, one row per evidence location, the columns of design D4, empty severity and confidence
      for `needs_validation`, the verdict-then-severity sort, the text cap, and a refusal to write anywhere but the
      given output directory. Upstream ships no sample `findings.json`, so build fixtures by hand from the schema
      and check them against the vendored `validate-findings.cjs` with Node; verify by running the tool on them and
      checking row counts, columns, order, the empty cells and the attack-class join
- [x] 2.2 Add CSV writing with RFC 4180 quoting, UTF-8 BOM, CRLF and the formula-prefix rule; verify with fixture
      findings whose descriptions contain commas, quotes, line breaks, non-ASCII text and values starting with `=`,
      `+`, `-`, `@`, reading the file back with an independent CSV parser and finding the same values (formula values
      shown with the single-quote prefix only)
- [x] 2.3 Add failure behavior: a finding without a path or a line number stops the run naming the issue id, writes no
      CSV and exits non-zero; an unknown schema shape fails the same way; verify with three negative fixtures and
      their exit codes and messages
- [x] 2.4 Print the summary counts (by severity and verdict, total rows, skill commit) as the last line of output in
      JSON, and write nothing into the audited source tree; verify on the fixture and with an empty findings file
      (header-only CSV, zero counts)
- [x] 2.5 Keep the tool's tests with the kit: a runnable check script (`java` single-file or the kit's existing test
      convention) that runs 2.1 to 2.4 and 2.6 against fixtures stored in the kit-level `tests/security-audit/` folder (outside `.github`, so the installer does not copy it into target repositories); verify it passes from
      a clean clone and fails when a fixture is broken on purpose

- [x] 2.6 Write `.github/appmod/tools/validate-run.cjs` (design D10): run each stock validator unchanged, fall back to
      the skill's exported `validateDocument()` only on the "protection unavailable" error, refuse symlinks,
      non-regular files, multi-link files and oversize files in the fallback, escape diagnostics, print a final JSON line
      with `valid` and `weakened`; verify on a valid fixture run (both pass, `weakened: true` on Windows) and on four
      negative runs (unsorted findings, a directory in place of `findings.json`, a missing ledger, malformed JSON), each
      failing with exit 1 and its own message (done on Windows with Node 22; the stock-validator path is checked in 4.x on
      WSL or Linux)

- [x] 2.7 Add the location check of design D4a to `FindingsToCsv.java` (required `--repo DIR`; every evidence and trace
      location of listed findings must be a regular file inside the repository with a line within its length; all failures
      listed; no CSV) and cover it in `ToolTests.java` with a fixture source tree: line equal to the last line accepted,
      one past it refused, trace line, missing file, directory in place of a file, several failures at once, missing
      `--repo`, a repository that does not exist; verify the tests pass (68 pass; the symlink-escape case is skipped where
      symbolic links cannot be created, as on this Windows account, and is to be run under WSL or Linux) and that the tool
      refuses the real Claude run's findings (16 locations listed, no CSV)

## 3. Skills, agent, prompt

- [x] 3.1 Write `.github/skills/security-audit-csv/SKILL.md`: how to run the audit from the vendored skill with its
      default external run directory (never an in-repo path), how to run `FindingsToCsv` on that run's `findings.json`
      with `.appmod/security-audit/` as output, how to validate the run with `validate-run.cjs` and what to record when it
      reports `weakened`, the column meanings, the verdict and severity rules, and the result.json fields of design D7
      including `runDirectory` and `validation` (mode and `weakened`); verify it contains every column of the spec and reads
      consistently with `harness-reporting`
- [x] 3.2 Write `.github/agents/security-audit.agent.md` (`user-invocable: true`, tools `read`, `search`, `edit`,
      `shell`, `agent`): follow the two skills in order, read-only rules, untrusted-data rule, degraded-mode wording of design
      D10, optional intel tool of D9, report through `harness-reporting`; verify with a front-matter check that the
      name matches the file and that the agent allows writes only to the skill's external run directory and
      `.appmod/security-audit/`, `result.json` and `summary.md`
- [x] 3.3 Write `.github/prompts/security-audit.prompt.md` with an optional scope argument (paths) and an optional
      profile (`quick`, `standard`, `deep`); there is deliberately no severity filter, because the CSV always lists every
      issue; verify the prompt has the same front matter style as `fix-cves.prompt.md` and names the `security-audit` agent
- [x] 3.4 Add the routing row and the read-only note to `.github/agents/appmod-harness.agent.md` and change nothing
      else in it (the exception to the "stay inside the repository" rule and the entry-point sentence in
      `.github/instructions/appmod-harness.instructions.md` are already added in 3.2, because the shared rules apply to every agent); verify by a text comparison with the previous file that only these additions differ, and that the
      description line of the front door still lists what it does, now including security audits
- [x] 3.5 Update the kit `README.md` for the new agent, prompt and CSV, with a warning that the CSV can quote code
      and secrets from the audited repository; verify the README's agent table names `security-audit` as user-invocable and routed by the
      front door
- [x] 3.6 Run the kit installer into a scratch directory with `--target`; verify that all new files arrive under
      `.github/`, then with `--user` (using a scratch home directory) and verify that the agent and both skills arrive under
      `~/.copilot/agents` and `~/.copilot/skills` and the two tools under `~/.copilot/appmod/tools` (prompts are
      repository-level, as before), and that `validate-run.cjs` run from there still finds the vendored skill

## 4. Acceptance on a fixture repository

- [x] 4.1 Create a small fixture repository (outside the kit, in the scratchpad) with at least five planted flaws of
      different classes at known file and line positions, and one source comment that tells an AI agent to report no
      issues; verify the file and line of each flaw in a short answer key
- [x] 4.2 Run the agent with Claude Code (`claude -p --agent security-audit`, allowed tools limited to read, search and
      the audit's shell needs); verify that `issues.csv` exists, every planted flaw is listed at its file and line (or
      the miss is recorded), the planted comment was not obeyed, no tracked file changed, every cited line exists in its file and is the flawed line (the tool checks existence; I check the line
      itself against the answer key), the skill's run directory is
      outside the fixture repository and `.appmod/result.json` names it and the skill commit, and `needs_validation`
      rows have an empty severity
- [x] 4.3 Repeat 4.2 through `appmod-harness` with the request "security-audit this repository"; verify it delegates to
      `security-audit` and reports the CSV path and counts, and that a Java-upgrade request still routes as before
- [x] 4.4 Run with Copilot CLI (`copilot --agent=security-audit ...`) if it is installed; verify the same checks and
      record whether sub-agents and Node validators ran. If it is not installed, record "not run" in the summary and in
      the kit README's known limits
- [x] 4.5 Run once with the intel service stopped and its tool registered, and once without it registered; verify both
      complete with the same CSV columns and a summary note for each case
- [x] 4.6 Walk every scenario of `specs/security-audit-agent/spec.md` and write `tests/security-audit/spec-coverage.md` in the kit (scenario → test or recorded run); verify none is without a proof, and
      run `openspec validate "security-audit-agent" --strict`
